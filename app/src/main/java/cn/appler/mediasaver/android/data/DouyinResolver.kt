package cn.appler.mediasaver.android.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object DouyinResolver {
  const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
      "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
  private const val REFERER = "https://www.douyin.com/"

  private val headers =
    mapOf(
      "Referer" to REFERER,
      "User-Agent" to MOBILE_USER_AGENT,
      "Accept" to "text/html,application/xhtml+xml,application/json",
    )
  private val desktopHeaders =
    mapOf("Referer" to REFERER, "User-Agent" to ApiHttp.USER_AGENT)
  private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
  private val idRegex = Regex("""/(?:video|note|slides)/(\d{15,25})""")
  private val ratioRegex = Regex("""ratio=(\d{3,4})p""", RegexOption.IGNORE_CASE)

  private data class Candidate(
    val height: Int,
    val h265: Boolean,
    val bitRate: Long,
    val urls: List<String>,
    val index: Int,
  )

  fun pageUrl(input: String): String {
    val extracted =
      urlRegex.find(input)?.value?.trimEnd(')', ']', ',', '.', ';', '!', '?', '，', '。', '；', '！', '？', '、')
        ?: throw ResolveException("没有找到抖音链接")
    return ApiHttp.finalUrl(extracted, headers)
  }

  fun sharePageUrl(input: String): String {
    val finalUrl = pageUrl(input)
    val awemeId = extractAwemeId(finalUrl) ?: throw ResolveException("没有识别到抖音作品 ID")
    val kind = if (finalUrl.contains("/note/") || finalUrl.contains("/slides/")) "note" else "video"
    return "https://www.iesdouyin.com/share/$kind/$awemeId/"
  }

  fun fromCaptured(raw: String, pageUrl: String): ResolvedMedia {
    val root =
      runCatching { JSONObject(raw) }.getOrNull()
        ?: throw ResolveException("抖音页面数据不是有效 JSON")
    val item = findAwemeItem(root) ?: throw ResolveException("捕获到的抖音页面里没有作品数据")
    return buildMedia(item, item.optString("aweme_id"), pageUrl)
  }

  fun resolve(input: String): ResolvedMedia {
    val finalUrl = pageUrl(input)
    val awemeId = extractAwemeId(finalUrl) ?: throw ResolveException("没有识别到抖音作品 ID")
    val pageKind = if (finalUrl.contains("/note/") || finalUrl.contains("/slides/")) "note" else "video"

    var sawChallenge = false
    val shareUrls =
      if (pageKind == "note") {
        listOf("note", "slides", "video")
      } else {
        listOf("video", "note", "slides")
      }
    for (kind in shareUrls) {
      val shareUrl = "https://www.iesdouyin.com/share/$kind/$awemeId/"
      val html =
        try {
          ApiHttp.getText(shareUrl, headers)
        } catch (error: Exception) {
          if (
            Regex("验证码|captcha|secsdk|verify-container")
              .containsMatchIn(error.message.orEmpty())
          ) {
            sawChallenge = true
          }
          continue
        }
      if (Regex("验证码|captcha|secsdk|verify-container").containsMatchIn(html)) {
        sawChallenge = true
      }
      val item = parseSharePage(html) ?: continue
      return buildMedia(item, awemeId, finalUrl)
    }

    if (sawChallenge) {
      throw ResolveException("抖音触发了安全验证，请稍后重试或使用手机 App 分享链接")
    }
    throw ResolveException("抖音分享页没有返回作品数据，可能需要 Cookie 或 WebView 解析")
  }

  private fun extractAwemeId(url: String): String? {
    val uri = Uri.parse(url)
    val queryId =
      uri.getQueryParameter("modal_id")
        ?: uri.getQueryParameter("aweme_id")
        ?: uri.getQueryParameter("item_id")
    if (!queryId.isNullOrBlank() && Regex("""\d{15,25}""").matches(queryId)) return queryId
    return idRegex.find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
  }

  private fun parseSharePage(html: String): JSONObject? {
    PageJson.extractJsonAfter(html, "window._ROUTER_DATA")?.let { data ->
      findAwemeItem(data)?.let { return it }
    }
    PageJson.extractEncodedJson(
      html,
      Regex("""<script[^>]+id="RENDER_DATA"[^>]*>([^<]+)</script>""", RegexOption.IGNORE_CASE),
    )?.let { data ->
      findAwemeItem(data)?.let { return it }
    }
    PageJson.extractJsonAfter(html, "window.__INITIAL_STATE__")?.let { data ->
      findAwemeItem(data)?.let { return it }
    }
    return null
  }

  private fun findAwemeItem(node: Any?, depth: Int = 0): JSONObject? {
    if (depth > 15 || node == null) return null
    if (node is JSONArray) {
      for (index in 0 until node.length()) {
        findAwemeItem(node.opt(index), depth + 1)?.let { return it }
      }
      return null
    }
    if (node !is JSONObject) return null

    if (
      node.optString("aweme_id").isNotBlank() &&
        (node.optJSONObject("video") != null || node.optJSONArray("images") != null)
    ) {
      return node
    }
    for (key in listOf("item_list", "note_list", "items", "aweme_list")) {
      node.optJSONArray(key)?.let { list ->
        findAwemeItem(list, depth + 1)?.let { return it }
      }
    }
    val keys = node.keys()
    while (keys.hasNext()) {
      findAwemeItem(node.opt(keys.next()), depth + 1)?.let { return it }
    }
    return null
  }

  private fun buildMedia(item: JSONObject, fallbackId: String, pageUrl: String): ResolvedMedia {
    val id = item.optString("aweme_id").ifBlank { fallbackId }
    val images = buildImages(item)
    val variants = buildVideoVariants(item)
    val kind = if (images.isNotEmpty()) "images" else "video"
    if (kind == "video" && variants.isEmpty()) {
      throw ResolveException("这条抖音作品没有可下载的视频地址")
    }
    if (kind == "images" && images.isEmpty()) {
      throw ResolveException("这条抖音作品没有可下载的图片")
    }
    val title = item.optString("desc").trim().ifBlank { "抖音作品 $id" }
    val author = item.optJSONObject("author")?.optString("nickname")?.takeIf { it.isNotBlank() }
    return ResolvedMedia(
      platform = "douyin",
      title = title,
      author = author,
      pageUrl = pageUrl,
      variants = variants,
      kind = kind,
      images = if (kind == "images") images else emptyList(),
    )
  }

  private fun buildVideoVariants(item: JSONObject): List<MediaVariant> {
    val video = item.optJSONObject("video") ?: return emptyList()
    val bitRates = video.optJSONArray("bit_rate")
    val candidates = mutableListOf<Candidate>()
    if (bitRates != null) {
      for (index in 0 until bitRates.length()) {
        val entry = bitRates.optJSONObject(index) ?: continue
        val play = entry.optJSONObject("play_addr") ?: entry.optJSONObject("playAddr") ?: continue
        val urls = urlList(play).map(::unwatermark).distinct()
        if (urls.isEmpty()) continue
        val height = gearHeight(entry, urls.first())
        candidates.add(
          Candidate(
            height = height,
            h265 = entry.optInt("is_h265", 0) == 1,
            bitRate = entry.optLong("bit_rate", 0),
            urls = urls,
            index = index,
          ),
        )
      }
    }
    if (candidates.isEmpty()) {
      val play = video.optJSONObject("play_addr") ?: video.optJSONObject("playAddr")
      val urls = play?.let(::urlList)?.map(::unwatermark)?.distinct().orEmpty()
      val first = urls.firstOrNull() ?: return emptyList()
      candidates.add(Candidate(gearHeight(null, first), false, 0, urls, 0))
    }

    val preferred = linkedMapOf<String, Candidate>()
    for (candidate in candidates) {
      val key = if (candidate.height > 0) candidate.height.toString() else "unknown-${candidate.index}"
      val existing = preferred[key]
      if (existing == null || better(candidate, existing)) preferred[key] = candidate
    }
    return preferred.values
      .sortedWith(compareByDescending<Candidate> { it.height }.thenByDescending { it.bitRate })
      .mapNotNull { candidate ->
        val url = candidate.urls.firstOrNull() ?: return@mapNotNull null
        val quality = if (candidate.height > 0) "${candidate.height}p" else "source"
        MediaVariant(
          id = "douyin-$quality${if (candidate.h265) "-h265" else ""}-${candidate.index}",
          label = "${qualityLabel(candidate.height)}${if (candidate.h265) " · H.265" else " · 无水印"}",
          quality = quality,
          url = url,
          headers = desktopHeaders,
        )
      }
  }

  private fun buildImages(item: JSONObject): List<MediaImage> {
    val array = item.optJSONArray("images") ?: return emptyList()
    val images = mutableListOf<MediaImage>()
    for (index in 0 until array.length()) {
      val image = array.optJSONObject(index) ?: continue
      val urls = urlList(image).map(::unwatermark)
      val url = urls.firstOrNull { Regex("""\.jpe?g(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        ?: urls.firstOrNull()
        ?: continue
      val ext = url.substringBefore('?').substringAfterLast('.', "jpeg").lowercase()
        .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpeg"
      images.add(
        MediaImage(
          id = "image-${index + 1}",
          url = url,
          ext = ext,
          label = "图片 ${index + 1}",
          headers = desktopHeaders,
        ),
      )
    }
    return images
  }

  private fun urlList(record: JSONObject): List<String> {
    val arrays =
      listOf(
        record.optJSONArray("url_list"),
        record.optJSONArray("download_url_list"),
        record.optJSONArray("urlList"),
        record.optJSONArray("downloadUrlList"),
      )
    val urls = mutableListOf<String>()
    for (array in arrays) {
      if (array == null) continue
      for (index in 0 until array.length()) {
        val value = array.optString(index)
        if (value.startsWith("http://") || value.startsWith("https://")) urls.add(value)
      }
    }
    return urls
  }

  private fun gearHeight(entry: JSONObject?, url: String): Int {
    val name = entry?.optString("gear_name").orEmpty()
    val fromName =
      Regex("""(?:^|_)(\d{3,4})(?:_|$)""").find(name)?.groupValues?.getOrNull(1)
        ?: Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)
    if (!fromName.isNullOrBlank()) return fromName.toIntOrNull() ?: 0
    val height = entry?.optInt("height", 0) ?: 0
    if (height > 0) return height
    return ratioRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
  }

  private fun better(a: Candidate, b: Candidate): Boolean {
    if (a.h265 != b.h265) return !a.h265
    return a.bitRate > b.bitRate
  }

  private fun qualityLabel(height: Int): String =
    when {
      height >= 2160 -> "4K 超清"
      height >= 1440 -> "2K 超清"
      height >= 1080 -> "1080P 高清"
      height >= 720 -> "720P 高清"
      height >= 540 -> "540P 标清"
      height >= 480 -> "480P 标清"
      height > 0 -> "${height}P"
      else -> "原画"
    }

  private fun unwatermark(url: String): String =
    url.replace("playwm", "play").replaceFirst("http://", "https://")
}
