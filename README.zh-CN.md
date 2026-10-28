# WebViewIDE

手机上的 HTML / CSS / JavaScript 编辑器。用系统 WebView 预览，打成一个已签名的 APK。不需要 root。

[English](README.md)

## 能做什么

- 编辑项目 `src/` 里的文件。第一次打开会放好 `index.html`、`styles.css`、`main.js`。
- 代码和预览可以分屏，拖中间的铰链改比例。
- **运行**会先保存，再刷新预览。
- **打包**把 `src/` 放进一个只有 WebView 的应用，路径是 `assets/www/`，同时写入 `assets/webcfg.json`，签名后打开安装界面。

页面就是普通网页。预览和安装包都由系统 WebView 绘制。

## 构建

需要 JDK 17 和 Android SDK。

```bash
./gradlew :app:testDebugUnitTest :runtime:syncRuntimeTemplate :app:assembleDebug :runtime:assembleDebug
```

`:app:assembleDebug` 会先编出运行时模板，再打进 IDE。

仓库在 [shiaho777/WebViewIDE](https://github.com/shiaho777/WebViewIDE)。原来的 LuaXIDE 会跳转到这里。

## 目录

```
app/       IDE（编辑、分屏、打包）
runtime/   打进 APK 的 WebView 壳
```

以 [Apache-2.0](LICENSE) 发布。第三方组件见 [THIRD_PARTY_NOTICES.zh-CN.md](THIRD_PARTY_NOTICES.zh-CN.md)。
