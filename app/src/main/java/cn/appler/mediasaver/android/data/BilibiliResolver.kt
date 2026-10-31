package cn.appler.mediasaver.android.data

import android.net.Uri
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

object BilibiliResolver {
  private const val API = "https://api.bilibili.com"
  private const val REFERER = "https://www.bilibili.com/"
  private const val BVID_PATTERN = "BV[0-9A-Za-z]{10}"
  private const val AID_PATTERN = "av([0-9]+)"

  private val headers = mapOf("Referer" to REFERER, "User-Agent" to ApiHttp.USER_AGENT)
  private val cdnHeaders = mapOf("Referer" to REFERER, "User-Agent" to ApiHttp.USER_AGENT)
  private val bvidRegex = Regex(BVID_PATTERN)
  private val aidRegex = Regex(AID_PATTERN, RegexOption.IGNORE_CASE)
  private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

  private val qualityLabels =
    mapOf(
      127 to "8K 超高清",
      126 to "杜比视界",
      125 to "HDR 真彩",
      120 to "4K 超清",
      116 to "1080P60 高帧率",
      112 to "1080P+ 高码率",
      80 to "1080P 高清",
      74 to "720P60 高帧率",
      64 to "720P 高清",
      32 to "480P 标清",
      16 to "360P 流畅",
      6 to "240P 极速",
    )

  private data class Page(
    val cid: Long,
    val page: Int,
    val part: String?,
    val duration: Long?,
  )

  private data class DashStream(
    val id: Int,
    val baseUrl: String?,
    val backupUrls: List<String>,
    val codecs: String?,
    val bandwidth: Long,
    val height: Int,
  )

  private data class DurlStream(
    val url: String?,
    val backupUrls: List<String>,
  )

  private data class PlayData(
    val dashVideos: List<DashStream>,
    val audioUrl: String?,
    val durl: List<DurlStream>,
    val quality: Int,
  )

  @Volatile private var wbiKeys: Pair<String, String>? = null

  fun resolve(input: String): ResolvedMedia {
    val rawUrl =
      urlRegex.find(input)?.value?.trimEnd(')', ']', ',', '.', ';', '!', '?', '，', '。', '；', '！', '？', '、')
        ?: throw ResolveException("没有找到 B 站链接")
    val pageUrl = normalizeUrl(rawUrl)
    val bvid = bvidRegex.find(pageUrl)?.value
    val aid = if (bvid == null) aidRegex.find(pageUrl)?.groupValues?.getOrNull(1) else null
    if (bvid == null && aid == null) {
      throw ResolveException("没有识别到 B 站视频 ID")
    }

    val view = fetchView(bvid, aid)
    val viewData = view.optJSONObject("data") ?: throw ResolveException("B 站返回数据为空")
    val resolvedBvid = viewData.optString("bvid").ifBlank { bvid ?: throw ResolveException("没有解析到 BV 号") }
    val pages = parsePages(viewData.optJSONArray("pages"))
    val requestedPage = Uri.parse(pageUrl).getQueryParameter("p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val selected = pages.getOrNull(requestedPage - 1) ?: pages.firstOrNull()
      ?: throw ResolveException("没有找到视频分 P 信息")
    val multiPart = pages.size > 1
    val title =
      if (multiPart && !selected.part.isNullOrBlank()) {
        "${viewData.optString("title", resolvedBvid)} - ${selected.part}"
      } else {
        viewData.optString("title", resolvedBvid)
      }
    val author = viewData.optJSONObject("owner")?.optString("name")?.takeIf { it.isNotBlank() }

    val dash = runCatching { fetchPlay(resolvedBvid, selected.cid, "4048") }.getOrNull()
    val durl = runCatching { fetchPlay(resolvedBvid, selected.cid, "0") }.getOrNull()
    val variants = buildVariants(dash, durl)
    if (variants.isEmpty()) {
      throw ResolveException("没有拿到可下载的清晰度，可能需要登录 Cookie 或该视频有地区限制")
    }

    return ResolvedMedia(
      platform = "bilibili",
      title = title,
      author = author,
      pageUrl = "https://www.bilibili.com/video/$resolvedBvid/?p=${selected.page}",
      variants = variants,
    )
  }

  private fun normalizeUrl(url: String): String {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
    if (host == "b23.tv" || host.endsWith(".b23.tv") || host == "bili2233.cn") {
      return ApiHttp.finalUrl(url, headers)
    }
    return url
  }

  private fun fetchView(bvid: String?, aid: String?): JSONObject {
    val query =
      if (bvid != null) "bvid=${encode(bvid)}" else "aid=${encode(aid.orEmpty())}"
    val json = JSONObject(ApiHttp.getText("$API/x/web-interface/view?$query", headers))
    val code = json.optInt("code", -1)
    if (code != 0) {
      throw ResolveException("B 站解析失败：${json.optString("message", "code=$code")}")
    }
    return json
  }

  private fun fetchPlay(bvid: String, cid: Long, fnval: String): PlayData? {
    val params =
      linkedMapOf(
        "bvid" to bvid,
        "cid" to cid.toString(),
        "qn" to "120",
        "fnval" to fnval,
        "fnver" to "0",
        "fourk" to "1",
      )
    val rawQuery = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    var json = JSONObject(ApiHttp.getText("$API/x/player/playurl?$rawQuery", headers))
    val firstCode = json.optInt("code", -1)
    if (firstCode != 0) {
      if (firstCode == -403 || firstCode == -352 || firstCode == -401) {
        val keys = getWbiKeys()
        val signed = signWbiParams(params, keys)
        json = JSONObject(ApiHttp.getText("$API/x/player/wbi/playurl?$signed", headers))
      } else {
        return null
      }
    }
    if (json.optInt("code", -1) != 0) return null
    val data = json.optJSONObject("data") ?: return null
    val dash = data.optJSONObject("dash")
    val dashVideos = parseDashVideos(dash?.optJSONArray("video"))
    val audioUrl = bestAudio(dash?.optJSONArray("audio"))
    val durl = parseDurl(data.optJSONArray("durl"))
    return PlayData(
      dashVideos = dashVideos,
      audioUrl = audioUrl,
      durl = durl,
      quality = data.optInt("quality", 64),
    )
  }

  private fun parsePages(array: JSONArray?): List<Page> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val cid = item.optLong("cid", 0)
        if (cid <= 0) continue
        add(
          Page(
            cid = cid,
            page = item.optInt("page", index + 1),
            part = item.optString("part").takeIf { it.isNotBlank() },
            duration = item.optLong("duration", 0).takeIf { it > 0 },
          ),
        )
      }
    }
  }

  private fun parseDashVideos(array: JSONArray?): List<DashStream> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val base = item.optString("baseUrl").ifBlank { item.optString("base_url") }.takeIf { it.isNotBlank() }
        add(
          DashStream(
            id = item.optInt("id", 0),
            baseUrl = base,
            backupUrls = stringList(item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url")),
            codecs = item.optString("codecs").takeIf { it.isNotBlank() },
            bandwidth = item.optLong("bandwidth", 0),
            height = item.optInt("height", 0),
          ),
        )
      }
    }
  }

  private fun bestAudio(array: JSONArray?): String? {
    if (array == null) return null
    var best: JSONObject? = null
    var bestBandwidth = -1L
    for (index in 0 until array.length()) {
      val item = array.optJSONObject(index) ?: continue
      val bandwidth = item.optLong("bandwidth", 0)
      if (bandwidth > bestBandwidth) {
        best = item
        bestBandwidth = bandwidth
      }
    }
    val url = best?.optString("baseUrl")?.ifBlank { best?.optString("base_url").orEmpty() }
    return url?.takeIf { it.isNotBlank() }
  }

  private fun parseDurl(array: JSONArray?): List<DurlStream> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(
          DurlStream(
            url = item.optString("url").takeIf { it.isNotBlank() },
            backupUrls = stringList(item.optJSONArray("backup_url")),
          ),
        )
      }
    }
  }

  private fun buildVariants(dash: PlayData?, durl: PlayData?): List<MediaVariant> {
    val variants = mutableListOf<MediaVariant>()
    if (dash != null) {
      val grouped = dash.dashVideos.groupBy { it.id }
      for ((id, streams) in grouped) {
        val stream =
          streams.sortedWith(
            compareBy<DashStream> { codecRank(it.codecs) }.thenByDescending { it.bandwidth },
          ).firstOrNull() ?: continue
        val videoUrl = stream.baseUrl ?: continue
        val label =
          qualityLabels[id] ?: if (stream.height > 0) "${stream.height}P" else "清晰度 $id"
        val codec = codecLabel(stream.codecs)
        variants.add(
          MediaVariant(
            id = "dash-$id",
            label = if (codec.isBlank()) label else "$label · $codec",
            quality = "q$id",
            requiresMerge = dash.audioUrl != null,
            videoUrl = absoluteUrl(videoUrl),
            audioUrl = dash.audioUrl?.let(::absoluteUrl),
            headers = cdnHeaders,
          ),
        )
      }
    }
    if (durl != null && durl.durl.size == 1) {
      val direct = durl.durl.first()
      val url = direct.url ?: direct.backupUrls.firstOrNull()
      if (url != null) {
        val quality = durl.quality
        val label = qualityLabels[quality] ?: "$quality P"
        variants.add(
          MediaVariant(
            id = "durl-$quality",
            label = "$label · 单文件",
            quality = "q$quality",
            url = absoluteUrl(url),
            headers = cdnHeaders,
          ),
        )
      }
    }
    return variants.sortedWith(
      compareByDescending<MediaVariant> { qualityRank(it) }.thenBy { it.requiresMerge },
    )
  }

  private fun qualityRank(variant: MediaVariant): Int {
    val key = variant.quality.removePrefix("q").toIntOrNull()
    if (key != null) return key
    return 0
  }

  private fun codecRank(codecs: String?): Int =
    when {
      codecs == null -> 9
      codecs.startsWith("avc1") -> 0
      codecs.startsWith("hev1") || codecs.startsWith("hvc1") -> 1
      codecs.startsWith("av01") -> 2
      else -> 3
    }

  private fun codecLabel(codecs: String?): String =
    when {
      codecs == null -> ""
      codecs.startsWith("avc1") -> "AVC"
      codecs.startsWith("hev1") || codecs.startsWith("hvc1") -> "HEVC"
      codecs.startsWith("av01") -> "AV1"
      else -> ""
    }

  private fun absoluteUrl(url: String): String =
    when {
      url.startsWith("//") -> "https:$url"
      url.startsWith("/") -> "https://www.bilibili.com$url"
      else -> url
    }

  private fun stringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val value = array.optString(index)
        if (value.isNotBlank()) add(absoluteUrl(value))
      }
    }
  }

  private fun getWbiKeys(): Pair<String, String> {
    wbiKeys?.let { return it }
    val json = JSONObject(ApiHttp.getText("$API/x/web-interface/nav", headers))
    val data = json.optJSONObject("data") ?: throw ResolveException("无法获取 B 站 WBI 密钥")
    val imgUrl = data.optJSONObject("wbi_img")?.optString("img_url").orEmpty()
    val subUrl = data.optJSONObject("wbi_img")?.optString("sub_url").orEmpty()
    val keys = fileKey(imgUrl) to fileKey(subUrl)
    if (keys.first.isBlank() || keys.second.isBlank()) {
      throw ResolveException("无法获取 B 站 WBI 密钥")
    }
    wbiKeys = keys
    return keys
  }

  private fun fileKey(url: String): String =
    url.substringAfterLast('/').substringBefore('.').ifBlank { "" }

  private fun signWbiParams(params: Map<String, String>, keys: Pair<String, String>): String {
    val all = linkedMapOf<String, String>().apply {
      putAll(params)
      put("wts", (System.currentTimeMillis() / 1000).toString())
    }
    val query =
      all.toSortedMap().entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value.replace(Regex("[!'()*]"), ""))}"
      }
    val mixin = mixinKey(keys.first + keys.second)
    return "$query&w_rid=${md5(query + mixin)}"
  }

  private fun mixinKey(original: String): String =
    intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
      )
      .map { index -> original.getOrElse(index) { '0' } }
      .joinToString("")
      .take(32)

  private fun md5(value: String): String =
    MessageDigest.getInstance("MD5")
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  private fun encode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
