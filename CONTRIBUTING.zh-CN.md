# 贡献指南

[English](CONTRIBUTING.md)

WebViewIDE 是在手机上编辑 HTML、CSS、JavaScript 的 IDE。改预览或打包前先读 [README](README.zh-CN.md)。

## 开始

```bash
./gradlew :app:testDebugUnitTest :runtime:syncRuntimeTemplate :app:assembleDebug :runtime:assembleDebug
```

需要 JDK 17 和 Android SDK。改过 `runtime/` 之后，下一次 `:app:assembleDebug` 会重新带上模板（`mergeDebugAssets` 依赖 `syncRuntimeTemplate`）。

## 交付（Issue → PR → CI → 合并）

1. **先开 Issue**（或认领已有的）：写清问题、目标和验收标准。
2. 从最新的 `main` 拉分支。
3. 向 `main` 开 PR，使用 PR 模板，正文**必须包含 `Fixes #N`**。
4. 等必需检查 `android-build` 变绿（`.github/workflows/ci.yml`）。
5. 维护者在 CI 变绿后合并。Issue 在合并时关闭。不要合并红的检查，也不要提前关 Issue。

## 约定

- 提交说明写为什么。
- 不要提交 `local.properties`、密钥库（`*.jks`、`*.p12`）、IDE 缓存或构建产物。`*.apk` 是生成的。
- **不需要 root。** 不要加入 `su`、Magisk 或设备 root 依赖。
- 打包进去的页面在 `assets/www/`，入口写在 `assets/webcfg.json`。预览和安装包都用系统 WebView。
- `:runtime` 只做 WebView 壳。编辑器和打包留在 `:app`。

## 报 bug

请附上设备型号、Android 版本、能复现的 HTML/CSS/JS，以及你期望看到的结果。
