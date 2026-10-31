package cn.appler.mediasaver.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appler.mediasaver.android.data.BilibiliResolver
import cn.appler.mediasaver.android.data.DouyinResolver
import cn.appler.mediasaver.android.data.DownloadProgress
import cn.appler.mediasaver.android.data.Downloader
import cn.appler.mediasaver.android.data.MediaVariant
import cn.appler.mediasaver.android.data.ResolvedMedia
import cn.appler.mediasaver.android.data.XiaohongshuResolver
import cn.appler.mediasaver.android.ui.theme.MediaSaverTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private val sharedTextState = mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    sharedTextState.value = extractSharedText(intent)
    setContent {
      MediaSaverTheme {
        MediaSaverScreen(sharedText = sharedTextState.value)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    sharedTextState.value = extractSharedText(intent)
  }

  private fun extractSharedText(intent: Intent?): String? =
    if (intent?.action == Intent.ACTION_SEND) {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    } else {
      null
    }
}

@Composable
private fun MediaSaverScreen(sharedText: String?) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val mainHandler = remember { Handler(Looper.getMainLooper()) }
  var input by rememberSaveable { mutableStateOf(sharedText.orEmpty()) }
  var status by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<ResolvedMedia?>(null) }
  var downloadingId by remember { mutableStateOf<String?>(null) }
  var progress by remember { mutableStateOf<DownloadProgress?>(null) }
  var douyinPageUrl by remember { mutableStateOf("") }
  var showDouyinWebView by remember { mutableStateOf(false) }
  val platform = remember(input) { detectPlatform(input) }

  LaunchedEffect(sharedText) {
    if (!sharedText.isNullOrBlank() && sharedText != input) {
      input = sharedText
      status = ""
      result = null
      progress = null
    }
  }

  fun startResolve() {
    if (input.isBlank() || busy) return
    val detected = detectPlatform(input)
    if (detected == null) {
      result = null
      status = "没有识别到支持的平台链接。"
      return
    }
    if (detected == "douyin") {
      result = null
      progress = null
      showDouyinWebView = false
      busy = true
      status = "正在打开抖音页面…"
      scope.launch {
        try {
          val page = withContext(Dispatchers.IO) { DouyinResolver.sharePageUrl(input) }
          douyinPageUrl = page
          showDouyinWebView = true
          status = "请在下方完成抖音页面验证；捕获到作品后会自动解析。"
        } catch (error: Exception) {
          status = error.message ?: "抖音页面打开失败"
        } finally {
          busy = false
        }
      }
      return
    }
    busy = true
    result = null
    progress = null
    status = "正在解析${platformLabel(detected)}…"
    scope.launch {
      try {
        val data =
          withContext(Dispatchers.IO) {
            when (detected) {
              "bilibili" -> BilibiliResolver.resolve(input)
              "xiaohongshu" -> XiaohongshuResolver.resolve(input)
              else -> DouyinResolver.resolve(input)
            }
          }
        result = data
        status =
          if (data.kind == "images") {
            "解析完成，共 ${data.images.size} 张图片。"
          } else {
            "解析完成，选择清晰度下载。"
          }
      } catch (error: Exception) {
        status = error.message ?: "解析失败"
      } finally {
        busy = false
      }
    }
  }

  fun startDownload(variant: MediaVariant) {
    val media = result ?: return
    if (downloadingId != null) return
    downloadingId = variant.id
    progress = DownloadProgress("准备下载")
    status = "准备下载：${variant.label}"
    scope.launch {
      try {
        Downloader.save(context, media, variant) { update ->
          mainHandler.post {
            progress = update
            status = progressLabel(update)
          }
        }
        status = "已保存到手机“下载/MediaSaver”目录"
      } catch (error: Exception) {
        status = error.message ?: "下载失败"
      } finally {
        downloadingId = null
      }
    }
  }

  fun startImageDownload(media: ResolvedMedia) {
    if (downloadingId != null || media.images.isEmpty()) return
    downloadingId = "images"
    progress = DownloadProgress("准备下载图片")
    scope.launch {
      var saved = 0
      try {
        for ((index, image) in media.images.withIndex()) {
          mainHandler.post { status = "保存图片 ${index + 1}/${media.images.size}…" }
          Downloader.saveImage(context, media, image) { update ->
            mainHandler.post {
              progress = update
              status = "保存图片 ${index + 1}/${media.images.size} ${formatProgress(update)}"
            }
          }
          saved += 1
        }
        status = "已保存 $saved 张图片到手机“下载/MediaSaver”目录"
      } catch (error: Exception) {
        status = error.message ?: "图片保存失败"
      } finally {
        downloadingId = null
      }
    }
  }

  fun handleDouyinPayload(payload: String) {
    if (!showDouyinWebView) return
    try {
      val data = DouyinResolver.fromCaptured(payload, douyinPageUrl)
      result = data
      showDouyinWebView = false
      status =
        if (data.kind == "images") {
          "抖音解析完成，共 ${data.images.size} 张图片。"
        } else {
          "抖音解析完成，选择清晰度下载。"
        }
    } catch (error: Exception) {
      status = "已捕获抖音页面数据，但解析失败：${error.message}"
    }
  }

  Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = "MediaSaver",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = "手机独立下载 · B站可用，小红书 / 抖音开发中",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      OutlinedTextField(
        value = input,
        onValueChange = {
          input = it
          status = ""
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("作品链接或分享文本") },
        placeholder = { Text("粘贴 B 站视频链接") },
        minLines = 3,
        maxLines = 6,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
          onClick = { startResolve() },
          enabled = input.isNotBlank() && !busy,
        ) {
          Text(if (busy) "解析中…" else "解析")
        }
        TextButton(
          onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrBlank()) {
              input = text
              status = ""
            }
          },
        ) {
          Text("粘贴")
        }
        if (platform != null) {
          SuggestionChip(
            onClick = {},
            label = { Text(platformLabel(platform)) },
          )
        }
      }

      if (status.isNotBlank()) {
        Text(
          text = status,
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (status.contains("失败") || status.contains("没有")) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.primary
            },
        )
      }

      val currentProgress = progress
      if (currentProgress != null && downloadingId != null) {
        LinearProgressIndicator(
          progress = {
            val total = currentProgress.total
            if (total != null && total > 0) {
              (currentProgress.received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            } else {
              0f
            }
          },
          modifier = Modifier.fillMaxWidth(),
        )
      }

      if (showDouyinWebView) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text("抖音页面", style = MaterialTheme.typography.titleMedium)
            Text(
              "如果出现登录或安全验证，请在下方页面完成；捕获到作品数据后会自动解析。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AndroidView(
              modifier = Modifier.fillMaxWidth().height(460.dp),
              factory = { webContext ->
                WebView(webContext).apply {
                  settings.javaScriptEnabled = true
                  settings.domStorageEnabled = true
                  settings.userAgentString = DouyinResolver.MOBILE_USER_AGENT
                  addJavascriptInterface(
                    object {
                      @JavascriptInterface
                      fun onPayload(payload: String) {
                        mainHandler.post { handleDouyinPayload(payload) }
                      }
                    },
                    "MediaSaverBridge",
                  )
                  webViewClient =
                    object : WebViewClient() {
                      override fun onPageStarted(
                        view: WebView,
                        url: String?,
                        favicon: Bitmap?,
                      ) {
                        view.evaluateJavascript(DOUYIN_HOOK_JS, null)
                      }

                      override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(DOUYIN_HOOK_JS, null)
                        view.evaluateJavascript(DOUYIN_READ_STATE_JS, null)
                      }
                    }
                  loadUrl(douyinPageUrl)
                }
              },
            )
            TextButton(
              onClick = {
                showDouyinWebView = false
                status = "已关闭抖音页面"
              },
            ) {
              Text("关闭页面")
            }
          }
        }
      }

      result?.let { media ->
        ResultCard(
          media = media,
          downloadingId = downloadingId,
          onDownload = ::startDownload,
          onDownloadImages = ::startImageDownload,
        )
      }

      if (result == null) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text("Android 独立版", style = MaterialTheme.typography.titleMedium)
            Text(
              "B 站使用原生 API + WBI + 手机端音视频合并；小红书和抖音优先解析分享页，下载直接保存到手机。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              lineHeight = 22.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              "不依赖电脑或本地服务 · 下载到手机",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ResultCard(
  media: ResolvedMedia,
  downloadingId: String?,
  onDownload: (MediaVariant) -> Unit,
  onDownloadImages: (ResolvedMedia) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(media.title, style = MaterialTheme.typography.titleMedium)
      Text(
        media.author ?: platformLabel(media.platform),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (media.kind == "images") {
        Text(
          "共 ${media.images.size} 张图片",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
          onClick = { onDownloadImages(media) },
          enabled = downloadingId == null,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (downloadingId == "images") "保存中…" else "保存全部图片")
        }
      } else {
        media.variants.forEach { variant ->
          OutlinedButton(
            onClick = { onDownload(variant) },
            enabled = downloadingId == null,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              Text(variant.label, fontWeight = FontWeight.SemiBold)
              val note = if (variant.requiresMerge) "下载后合并音视频" else "单文件下载"
              Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

private fun progressLabel(progress: DownloadProgress): String =
  when (progress.phase) {
    "video" -> "下载视频 ${formatProgress(progress)}"
    "audio" -> "下载音频 ${formatProgress(progress)}"
    "image" -> "保存图片 ${formatProgress(progress)}"
    "merge" -> "合并音视频…"
    "save" -> "保存到下载目录…"
    else -> progress.phase
  }

private fun formatProgress(progress: DownloadProgress): String {
  val total = progress.total
  return if (total != null && total > 0) {
    "${(progress.received * 100 / total).coerceIn(0, 100)}%"
  } else {
    "${progress.received / 1024 / 1024} MB"
  }
}

private const val DOUYIN_HOOK_JS = """
(function() {
  if (window.__mediaSaverHooked) return;
  window.__mediaSaverHooked = true;
  function send(text) {
    try {
      if (text && (text.indexOf('aweme_detail') >= 0 || text.indexOf('aweme_list') >= 0)) {
        window.MediaSaverBridge.onPayload(String(text));
      }
    } catch (e) {}
  }
  var originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function() {
      return originalFetch.apply(this, arguments).then(function(response) {
        try { response.clone().text().then(send); } catch (e) {}
        return response;
      });
    };
  }
  var originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function() {
    this.addEventListener('load', function() {
      try { send(this.responseText); } catch (e) {}
    });
    return originalOpen.apply(this, arguments);
  };
})();
"""

private const val DOUYIN_READ_STATE_JS = """
(function() {
  try {
    var data = window._ROUTER_DATA || window.__INITIAL_STATE__;
    if (data) window.MediaSaverBridge.onPayload(JSON.stringify(data));
    var scripts = document.scripts || [];
    for (var i = 0; i < scripts.length; i++) {
      var text = scripts[i].textContent || '';
      if (text.indexOf('aweme_detail') >= 0 || text.indexOf('aweme_list') >= 0) {
        window.MediaSaverBridge.onPayload(text);
      }
    }
  } catch (e) {}
})();
"""

private fun detectPlatform(text: String): String? {
  val lower = text.lowercase()
  return when {
    lower.contains("douyin.com") || lower.contains("v.douyin.com") -> "douyin"
    lower.contains("bilibili.com") || lower.contains("b23.tv") -> "bilibili"
    lower.contains("xiaohongshu.com") || lower.contains("xhslink.com") -> "xiaohongshu"
    else -> null
  }
}

private fun platformLabel(platform: String): String =
  when (platform) {
    "douyin" -> "抖音"
    "bilibili" -> "哔哩哔哩"
    "xiaohongshu" -> "小红书"
    else -> platform
  }
