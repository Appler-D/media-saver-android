# MediaSaver Android

抖音、哔哩哔哩、小红书的独立 Android 下载器，目标是不依赖电脑和本地服务，直接在手机上解析并保存内容。

> 仅限个人学习、备份和已获授权的内容。请遵守平台服务条款与著作权法。

## 当前状态

这是与桌面版 [media-saver](https://github.com/Appler-D/media-saver) 分开的新项目，正在开发中，尚未发布可用安装包。

- [x] Kotlin + Jetpack Compose 项目骨架
- [x] 平台识别与解析接口
- [x] 哔哩哔哩：原生 API、WBI 签名、清晰度选择、DASH 合并，已在 Android 12 模拟器实测
- [ ] 哔哩哔哩登录 Cookie（1080P+）
- [ ] 小红书：分享页解析、登录态、视频/图集下载
- [ ] 抖音：WebView 页面数据提取与下载
- [ ] 手机下载历史、批量任务

## 技术方向

- Android 原生 Kotlin，最低 Android 10（API 29）。
- 哔哩哔哩优先使用原生 API，不依赖电脑浏览器。
- 抖音使用应用内 WebView 完成页面签名和数据提取；小红书优先解析分享页状态。
- 视频下载到手机公共下载目录；DASH 分离流优先使用 Android MediaMuxer 合并。
- 不使用账号 Cookie 上传、不做云端转发。

## 构建

需要 JDK 17、Android SDK 35：

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

## 维护说明

平台接口和反爬会变化，抖音尤其不稳定。这个项目与桌面版独立维护；桌面版继续保留 Node/ffmpeg 方案。
