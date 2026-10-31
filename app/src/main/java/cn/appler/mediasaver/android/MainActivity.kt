package cn.appler.mediasaver.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appler.mediasaver.android.ui.theme.MediaSaverTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val sharedText = intent?.let { intent ->
      if (intent.action == Intent.ACTION_SEND) {
        intent.getStringExtra(Intent.EXTRA_TEXT)
      } else {
        null
      }
    }
    setContent {
      MediaSaverTheme {
        MediaSaverScreen(initialText = sharedText.orEmpty())
      }
    }
  }
}

@Composable
private fun MediaSaverScreen(initialText: String) {
  val context = LocalContext.current
  var input by rememberSaveable { mutableStateOf(initialText) }
  var status by remember { mutableStateOf("") }
  val platform = remember(input) { detectPlatform(input) }

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
        text = "手机独立下载 · 抖音 / 哔哩哔哩 / 小红书",
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
        placeholder = { Text("粘贴一个链接，后续支持批量") },
        minLines = 3,
        maxLines = 6,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
          onClick = {
            status =
              when (platform) {
                "bilibili" -> "B 站解析模块正在接入，下一阶段支持清晰度选择和下载。"
                "xiaohongshu" -> "小红书解析模块正在开发。"
                "douyin" -> "抖音 WebView 解析模块正在开发。"
                else -> "没有识别到支持的平台链接。"
              }
          },
          enabled = input.isNotBlank(),
        ) {
          Text("解析")
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
          color = MaterialTheme.colorScheme.primary,
        )
      }

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text("开发状态", style = MaterialTheme.typography.titleMedium)
          Text(
            "当前是 Android 工程骨架；B 站、小红书、抖音解析会按顺序接入。",
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
