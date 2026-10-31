package cn.appler.mediasaver.android.data

import android.net.Uri
import org.json.JSONObject

object PageJson {
  fun extractJsonAfter(html: String, marker: String): JSONObject? {
    val markerIndex = html.indexOf(marker)
    if (markerIndex < 0) return null
    val start = html.indexOf('{', markerIndex + marker.length)
    if (start < 0) return null
    val raw = sliceBalancedJson(html, start) ?: return null
    return runCatching { JSONObject(replaceBareUndefined(raw)) }.getOrNull()
  }

  fun extractEncodedJson(html: String, pattern: Regex): JSONObject? {
    val match = pattern.find(html) ?: return null
    val encoded = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    val decoded = runCatching { Uri.decode(encoded) }.getOrNull() ?: return null
    return runCatching { JSONObject(replaceBareUndefined(decoded)) }.getOrNull()
  }

  private fun sliceBalancedJson(text: String, start: Int): String? {
    if (text.getOrNull(start) != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
      val char = text[index]
      if (inString) {
        if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') inString = false
        continue
      }
      if (char == '"') {
        inString = true
      } else if (char == '{' || char == '[') {
        depth += 1
      } else if (char == '}' || char == ']') {
        depth -= 1
        if (depth == 0) return text.substring(start, index + 1)
      }
    }
    return null
  }

  private fun replaceBareUndefined(raw: String): String {
    val output = StringBuilder(raw.length)
    var inString = false
    var escaped = false
    var index = 0
    while (index < raw.length) {
      val char = raw[index]
      if (inString) {
        output.append(char)
        if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') inString = false
        index += 1
        continue
      }
      if (char == '"') {
        inString = true
        output.append(char)
        index += 1
        continue
      }
      if (
        raw.startsWith("undefined", index) &&
          (index == 0 || !raw[index - 1].isLetterOrDigit()) &&
          (index + 9 >= raw.length || !raw[index + 9].isLetterOrDigit())
      ) {
        output.append("null")
        index += 9
        continue
      }
      output.append(char)
      index += 1
    }
    return output.toString()
  }
}
