package cn.appler.mediasaver.android.data

data class MediaVariant(
  val id: String,
  val label: String,
  val quality: String,
  val requiresMerge: Boolean = false,
  val url: String? = null,
  val videoUrl: String? = null,
  val audioUrl: String? = null,
  val headers: Map<String, String> = emptyMap(),
)

data class ResolvedMedia(
  val platform: String,
  val title: String,
  val author: String?,
  val pageUrl: String,
  val variants: List<MediaVariant>,
)

class ResolveException(message: String) : Exception(message)

data class DownloadProgress(
  val phase: String,
  val received: Long = 0,
  val total: Long? = null,
)
