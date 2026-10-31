package cn.appler.mediasaver.android.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Downloader {
  suspend fun save(
    context: Context,
    media: ResolvedMedia,
    variant: MediaVariant,
    onProgress: (DownloadProgress) -> Unit,
  ): Uri =
    withContext(Dispatchers.IO) {
      val filename = filename(media, variant)
      if (variant.url != null) {
        val uri = createPending(context, filename, "video/mp4")
        try {
          ApiHttp.download(variant.url, variant.headers) { received, total ->
            onProgress(DownloadProgress("video", received, total))
          }.use { input ->
            context.contentResolver.openOutputStream(uri)?.use { output ->
              copy(input, output)
            } ?: throw IOException("无法创建下载文件")
          }
          finish(context, uri)
          uri
        } catch (error: Exception) {
          context.contentResolver.delete(uri, null, null)
          throw error
        }
      } else {
        val videoUrl = variant.videoUrl ?: throw ResolveException("没有视频下载地址")
        val audioUrl = variant.audioUrl
        val cacheDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val videoFile = File(cacheDir, "video-${System.currentTimeMillis()}.m4s")
        val audioFile = audioUrl?.let { File(cacheDir, "audio-${System.currentTimeMillis()}.m4s") }
        val mergedFile = File(cacheDir, "merged-${System.currentTimeMillis()}.mp4")
        try {
          downloadToFile(videoUrl, videoFile, variant.headers, "video", onProgress)
          if (audioUrl != null && audioFile != null) {
            downloadToFile(audioUrl, audioFile, variant.headers, "audio", onProgress)
          }
          mux(videoFile, audioFile, mergedFile, onProgress)
          val uri = createPending(context, filename, "video/mp4")
          try {
            mergedFile.inputStream().use { input ->
              context.contentResolver.openOutputStream(uri)?.use { output ->
                onProgress(DownloadProgress("save"))
                copy(input, output)
              } ?: throw IOException("无法创建下载文件")
            }
            finish(context, uri)
            uri
          } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
          }
        } finally {
          videoFile.delete()
          audioFile?.delete()
          mergedFile.delete()
        }
      }
    }

  suspend fun saveImage(
    context: Context,
    media: ResolvedMedia,
    image: MediaImage,
    onProgress: (DownloadProgress) -> Unit,
  ): Uri =
    withContext(Dispatchers.IO) {
      val ext = if (image.ext == "jpeg") "jpg" else image.ext
      val raw = "${media.title}-${image.id}"
        .replace(Regex("""[\\/:*?"<>|]"""), " ")
        .trim()
        .take(110)
      val uri = createPending(context, "$raw.$ext", mimeFor(image.ext))
      try {
        ApiHttp.download(image.url, image.headers) { received, total ->
          onProgress(DownloadProgress("image", received, total))
        }.use { input ->
          context.contentResolver.openOutputStream(uri)?.use { output ->
            copy(input, output)
          } ?: throw IOException("无法创建图片文件")
        }
        finish(context, uri)
        uri
      } catch (error: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw error
      }
    }

  private fun downloadToFile(
    url: String,
    file: File,
    headers: Map<String, String>,
    phase: String,
    onProgress: (DownloadProgress) -> Unit,
  ) {
    FileOutputStream(file).use { output ->
      ApiHttp.download(url, headers) { received, total ->
        onProgress(DownloadProgress(phase, received, total))
      }.use { input -> copy(input, output) }
    }
  }

  private fun createPending(context: Context, filename: String, mimeType: String): Uri {
    val values =
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, filename)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(
          MediaStore.Downloads.RELATIVE_PATH,
          "${Environment.DIRECTORY_DOWNLOADS}/MediaSaver",
        )
        put(MediaStore.Downloads.IS_PENDING, 1)
      }
    return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: throw IOException("无法写入系统下载目录")
  }

  private fun finish(context: Context, uri: Uri) {
    val values =
      ContentValues().apply {
        put(MediaStore.Downloads.IS_PENDING, 0)
      }
    context.contentResolver.update(uri, values, null, null)
  }

  private fun copy(input: java.io.InputStream, output: OutputStream) {
    val buffer = ByteArray(256 * 1024)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      output.write(buffer, 0, count)
    }
    output.flush()
  }

  private fun mux(
    videoFile: File,
    audioFile: File?,
    outputFile: File,
    onProgress: (DownloadProgress) -> Unit,
  ) {
    val videoExtractor = MediaExtractor()
    val audioExtractor = audioFile?.let { MediaExtractor() }
    var muxer: MediaMuxer? = null
    try {
      videoExtractor.setDataSource(videoFile.absolutePath)
      val videoTrack = findTrack(videoExtractor, "video/")
      if (videoTrack < 0) throw ResolveException("视频流没有可合并的轨道")
      videoExtractor.selectTrack(videoTrack)

      val audioTrack =
        if (audioFile != null && audioExtractor != null) {
          audioExtractor.setDataSource(audioFile.absolutePath)
          val track = findTrack(audioExtractor, "audio/")
          if (track >= 0) {
            audioExtractor.selectTrack(track)
            track
          } else {
            -1
          }
        } else {
          -1
        }

      muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val videoOut = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
      val audioOut =
        if (audioTrack >= 0 && audioExtractor != null) {
          muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
        } else {
          -1
        }
      muxer.start()
      onProgress(DownloadProgress("merge"))
      copySamples(videoExtractor, videoTrack, muxer, videoOut)
      if (audioTrack >= 0 && audioExtractor != null && audioOut >= 0) {
        copySamples(audioExtractor, audioTrack, muxer, audioOut)
      }
      muxer.stop()
    } finally {
      runCatching { muxer?.stop() }
      muxer?.release()
      videoExtractor.release()
      audioExtractor?.release()
    }
  }

  private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
    for (index in 0 until extractor.trackCount) {
      val mime = extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
      if (mime?.startsWith(prefix) == true) return index
    }
    return -1
  }

  private fun copySamples(
    extractor: MediaExtractor,
    trackIndex: Int,
    muxer: MediaMuxer,
    outputTrack: Int,
  ) {
    val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
    val info = MediaCodec.BufferInfo()
    while (true) {
      val size = extractor.readSampleData(buffer, 0)
      if (size < 0) break
      info.offset = 0
      info.size = size
      info.presentationTimeUs = extractor.sampleTime
      info.flags =
        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
          MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
          0
        }
      muxer.writeSampleData(outputTrack, buffer, info)
      extractor.advance()
    }
  }

  private fun filename(media: ResolvedMedia, variant: MediaVariant): String {
    val raw = "${media.title}-${variant.quality}"
      .replace(Regex("""[\\/:*?"<>|]"""), " ")
      .trim()
      .take(110)
    return "$raw.mp4"
  }

  private fun mimeFor(ext: String): String =
    when (ext.lowercase()) {
      "jpg", "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      else -> "application/octet-stream"
    }
}
