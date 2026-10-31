# MediaSaver Android

抖音、哔哩哔哩、小红书的独立 Android 下载器，目标是不依赖电脑和本地服务，直接在手机上解析并保存内容。

> 仅限个人学习、备份和已获授权的内容。请遵守平台服务条款与著作权法。

## 当前状态

这是与桌面版 [media-saver](https://github.com/Appler-D/media-saver) 分开的新项目。

[下载 v0.1.0-alpha.2 测试 APK](https://github.com/Appler-D/media-saver-android/releases/tag/v0.1.0-alpha.2)（Android 10+，调试签名，目前仅支持哔哩哔哩）。

- [x] Kotlin + Jetpack Compose 项目骨架
- [x] 平台识别与解析接口
- [x] 哔哩哔哩：原生 API、WBI 签名、清晰度选择、DASH 合并，已在 Android 12 模拟器实测
- [~] 小红书：分享页解析、视频/图集保存代码已接入，缺少带 xsec_token 的有效链接做实测
- [~] 抖音：分享页/WebView 数据捕获代码已接入，但当前测试链接被登录/安全验证拦截
- [x] App 内 B 站/小红书 Cookie 设置，只保存在本机
- [x] 图集缩略图、多选、全部保存/保存选中
- [ ] 抖音 WebView 登录态和深链拦截
- [ ] 手机下载历史、批量任务

## 技术方向

- Android 原生 Kotlin，最低 Android 10（API 29）。
- 哔哩哔哩优先使用原生 API，不依赖电脑浏览器。
- 抖音使用应用内 WebView 完成页面签名和数据提取；小红书优先解析分享页状态。
- 视频和图片保存到手机 `Download/MediaSaver/YYYY-MM-DD/`，按本地日期自动分文件夹；DASH 分离流优先使用 Android MediaMuxer 合并。
- 不使用账号 Cookie 上传、不做云端转发。

## Cookie

App 内的“Cookie”入口可以填写：

- B 站：`SESSDATA=...; bili_jct=...;`，用于 1080P+ 清晰度
- 小红书：`a1=...; web_session=...;`，用于部分需要登录的笔记

Cookie 只保存在 App 的私有 SharedPreferences 里，不会上传到云端。抖音使用 WebView 页面登录态，不使用这里的 Cookie。

## 构建

需要 JDK 17、Android SDK 35：

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

## 维护说明

平台接口和反爬会变化，抖音尤其不稳定。这个项目与桌面版独立维护；桌面版继续保留 Node/ffmpeg 方案。
