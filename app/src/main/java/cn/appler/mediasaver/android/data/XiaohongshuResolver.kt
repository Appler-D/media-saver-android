package cn.appler.mediasaver.android.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object XiaohongshuResolver {
  private const val REFERER = "https://www.xiaohongshu.com/"
  private val headers =
    mapOf(
      "Referer" to REFERER,
      "User-Agent" to ApiHttp.USER_AGENT,
      "Accept" to "text/html,application/xhtml+xml",
    )
  private val noteIdRegex = Regex("""/(?:explore|discovery/item|note)/([0-9a-f]{24})""", RegexOption.IGNORE_CASE)
  private val httpRegex = Regex("""^https?://""", RegexOption.IGNORE_CASE)

  fun resolve(input: String, cookie: String = ""): ResolvedMedia {
    val requestHeaders =
      buildMap {
        putAll(headers)
        if (cookie.isNotBlank()) put("Cookie", cookie)
      }
    val extracted =
      Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(input)?.value
        ?.trimEnd(')', ']', ',', '.', ';', '!', '?', '，', '。', '；', '！', '？', '、')
        ?: throw ResolveException("没有找到小红书链接")

    var pageUrl = extracted
    var noteId = noteIdFrom(pageUrl)
    pageUrl = ApiHttp.finalUrl(pageUrl, requestHeaders)
    val finalUri = Uri.parse(pageUrl)
    if (
      finalUri.path.orEmpty().startsWith("/404") ||
        finalUri.getQueryParameter("error_code") == "300031"
    ) {
      throw ResolveException("小红书分享链接已失效，请从 App 重新复制分享链接")
    }
    pageUrl = normalizeSecurityRedirect(pageUrl)
    noteId = noteIdFrom(pageUrl) ?: noteId
    if (noteId == null) {
      throw ResolveException("没有识别到小红书笔记 ID，请复制笔记页或分享链接")
    }
    if (Uri.parse(pageUrl).path?.contains("/login") == true) {
      throw ResolveException("小红书要求登录，请在 Cookie 设置中配置小红书 Cookie 后重试")
    }

    val html =
      try {
        ApiHttp.getText(pageUrl, requestHeaders)
      } catch (error: Exception) {
        throw ResolveException("小红书页面读取失败：${error.message}")
      }

    if (
      html.contains("当前笔记暂时无法浏览") ||
        html.contains("This Page Isn't Available") ||
        html.contains("error_code=300031")
    ) {
      throw ResolveException("小红书笔记不存在或分享链接已失效")
    }

    val state = PageJson.extractJsonAfter(html, "window.__INITIAL_STATE__")
    val note = state?.let { findNote(it) }
    if (note == null) {
      if (Regex("登录后查看|请先登录|扫码登录|login-container|reds-login").containsMatchIn(html)) {
        throw ResolveException("小红书要求登录或链接缺少 xsec_token，请从 App 重新复制链接并配置 Cookie")
      }
      throw ResolveException("没有在小红书页面里找到笔记数据")
    }

    val resolvedNoteId = note.optString("noteId").ifBlank { noteId.orEmpty() }
    val images = buildImages(note, requestHeaders)
    val variants = buildVideoVariants(note, requestHeaders)
    val kind = if (variants.isNotEmpty() || note.optString("type") == "video") "video" else "images"
    if (kind == "video" && variants.isEmpty()) {
      throw ResolveException("这条小红书笔记没有可下载的视频地址")
    }
    if (kind == "images" && images.isEmpty()) {
      throw ResolveException("这条小红书笔记没有可下载的图片")
    }

    val desc = note.optString("desc")
    val firstLine = desc.lineSequence().firstOrNull()?.trim().orEmpty()
    val title =
      note.optString("title").trim().ifBlank { firstLine.take(60) }
        .ifBlank { "小红书笔记 $resolvedNoteId" }
    val author = note.optJSONObject("user")?.optString("nickname")?.takeIf { it.isNotBlank() }

    return ResolvedMedia(
      platform = "xiaohongshu",
      title = title,
      author = author,
      pageUrl = pageUrl,
      variants = variants,
      kind = kind,
      images = if (kind == "images") images else emptyList(),
    )
  }

  private fun noteIdFrom(url: String): String? =
    noteIdRegex.find(Uri.parse(url).path.orEmpty())?.groupValues?.getOrNull(1)

  private fun normalizeSecurityRedirect(url: String): String {
    val uri = Uri.parse(url)
    if (!uri.path.orEmpty().startsWith("/404")) return url
    val direct = uri.getQueryParameter("originalUrl") ?: uri.getQueryParameter("redirectPath")
    val nested =
      Regex("""[?&]redirectPath=([^&]+)""").find(uri.toString())?.groupValues?.getOrNull(1)
    val target = (direct ?: nested?.let { runCatching { Uri.decode(it) }.getOrNull() }) ?: return url
    return target.replace("http://", "https://")
  }

  private fun findNote(node: Any?, depth: Int = 0): JSONObject? {
    if (depth > 15 || node == null) return null
    if (node is JSONArray) {
      for (index in 0 until node.length()) {
        findNote(node.opt(index), depth + 1)?.let { return it }
      }
      return null
    }
    if (node !is JSONObject) return null

    val detailMap = node.optJSONObject("noteDetailMap")
    if (detailMap != null) {
      val keys = detailMap.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val value = detailMap.optJSONObject(key) ?: continue
        val note = value.optJSONObject("note")
        if (note != null) {
          if (note.optString("noteId").isBlank()) note.put("noteId", key)
          return note
        }
      }
    }

    if (
      node.optString("noteId").isNotBlank() &&
        (node.optJSONArray("imageList") != null || node.optJSONObject("video") != null)
    ) {
      return node
    }

    val keys = node.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      findNote(node.opt(key), depth + 1)?.let { return it }
    }
    return null
  }

  private fun buildImages(note: JSONObject, requestHeaders: Map<String, String>): List<MediaImage> {
    val array = note.optJSONArray("imageList") ?: return emptyList()
    val images = mutableListOf<MediaImage>()
    for (index in 0 until array.length()) {
      val item = array.optJSONObject(index) ?: continue
      val url = pickImageUrl(item) ?: continue
      val ext = extensionFromUrl(url)
      images.add(
        MediaImage(
          id = "image-${index + 1}",
          url = toHttps(url),
          ext = ext,
          label = "图片 ${index + 1}",
          headers = requestHeaders,
        ),
      )
    }
    return images
  }

  private fun pickImageUrl(image: JSONObject): String? {
    val candidates = mutableListOf<Pair<String, Int>>()
    val infoList = image.optJSONArray("infoList")
    if (infoList != null) {
      for (index in 0 until infoList.length()) {
        val info = infoList.optJSONObject(index) ?: continue
        val url = info.optString("url")
        if (isHttp(url)) {
          var score = 0
          if (info.optString("imageScene") == "WB_DFT") score += 4
          if (url.contains("sns-img")) score += 3
          if (Regex("""\.jpe?g(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) score += 2
          if (url.contains("sns-webpic")) score += 1
          candidates.add(url to score)
        }
      }
    }
    for (url in listOf(image.optString("urlDefault"), image.optString("url"), image.optString("urlPre"))) {
      if (isHttp(url)) {
        var score = 0
        if (url.contains("sns-img")) score += 3
        if (Regex("""\.jpe?g(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) score += 2
        if (url.contains("sns-webpic")) score += 1
        candidates.add(url to score)
      }
    }
    return candidates.maxByOrNull { it.second }?.first
  }

  private fun buildVideoVariants(note: JSONObject, requestHeaders: Map<String, String>): List<MediaVariant> {
    val stream = note.optJSONObject("video")?.optJSONObject("media")?.optJSONObject("stream") ?: return emptyList()
    val h264 = parseStreams(stream.optJSONArray("h264"), "H.264", requestHeaders)
    if (h264.isNotEmpty()) return h264
    val h265 = parseStreams(stream.optJSONArray("h265"), "H.265", requestHeaders)
    if (h265.isNotEmpty()) return h265
    return parseStreams(stream.optJSONArray("av1"), "AV1", requestHeaders)
  }

  private fun parseStreams(array: JSONArray?, codec: String, requestHeaders: Map<String, String>): List<MediaVariant> {
    if (array == null) return emptyList()
    val variants = mutableListOf<MediaVariant>()
    for (index in 0 until array.length()) {
      val stream = array.optJSONObject(index) ?: continue
      val url = stream.optString("masterUrl")
      if (!isHttp(url)) continue
      val height = stream.optInt("height", 0)
      val quality = if (height > 0) "${height}p" else "source"
      variants.add(
        MediaVariant(
          id = "xhs-$quality-$index",
          label = "${if (height > 0) "${height}P" else "原画"} · $codec",
          quality = quality,
          url = toHttps(url),
          headers = requestHeaders,
        ),
      )
    }
    return variants.sortedByDescending { variant ->
      variant.quality.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
  }

  private fun extensionFromUrl(url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    val ext = path.substringAfterLast('.', "").lowercase()
    return if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) ext else "jpeg"
  }

  private fun isHttp(value: String): Boolean = httpRegex.containsMatchIn(value)

  private fun toHttps(url: String): String = url.replaceFirst("http://", "https://")
}
