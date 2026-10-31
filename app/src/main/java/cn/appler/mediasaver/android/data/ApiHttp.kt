package cn.appler.mediasaver.android.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object ApiHttp {
  const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

  val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .writeTimeout(90, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .retryOnConnectionFailure(true)
      .build()

  fun request(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ): Response {
    val builder =
      Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json, text/plain, */*")
    headers.forEach { (key, value) -> builder.header(key, value) }
    return client.newCall(builder.build()).execute()
  }

  fun getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ): String =
    request(url, headers).use { response ->
      if (!response.isSuccessful) {
        throw ResolveException("请求失败：HTTP ${response.code}")
      }
      response.body?.string() ?: throw ResolveException("响应为空")
    }

  fun finalUrl(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ): String =
    request(url, headers).use { response ->
      if (!response.isSuccessful) {
        throw ResolveException("短链解析失败：HTTP ${response.code}")
      }
      response.request.url.toString()
    }

  fun download(
    url: String,
    headers: Map<String, String>,
    onChunk: (received: Long, total: Long?) -> Unit,
  ): java.io.InputStream {
    val response = request(url, headers)
    if (!response.isSuccessful) {
      response.close()
      throw IOException("下载失败：HTTP ${response.code}")
    }
    val body = response.body ?: run {
      response.close()
      throw IOException("下载响应为空")
    }
    val total = body.contentLength().takeIf { it > 0 }
    val stream = body.byteStream()
    return object : java.io.FilterInputStream(stream) {
      private var received = 0L

      override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
          received += 1
          onChunk(received, total)
        }
        return value
      }

      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) {
          received += count
          onChunk(received, total)
        }
        return count
      }

      override fun close() {
        super.close()
        response.close()
      }
    }
  }
}
