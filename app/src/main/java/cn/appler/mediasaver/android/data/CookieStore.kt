package cn.appler.mediasaver.android.data

import android.content.Context

object CookieStore {
  private const val PREFS = "media_saver_cookies"
  private const val BILIBILI = "bilibili"
  private const val XIAOHONGSHU = "xiaohongshu"

  fun bilibili(context: Context): String = prefs(context).getString(BILIBILI, "").orEmpty()

  fun xiaohongshu(context: Context): String = prefs(context).getString(XIAOHONGSHU, "").orEmpty()

  fun save(context: Context, bilibili: String, xiaohongshu: String) {
    prefs(context).edit()
      .putString(BILIBILI, bilibili.trim())
      .putString(XIAOHONGSHU, xiaohongshu.trim())
      .apply()
  }

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
