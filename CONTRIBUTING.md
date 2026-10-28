# Contributing

[简体中文](CONTRIBUTING.zh-CN.md)

WebViewIDE is an on-device editor for HTML, CSS, and JavaScript. Read the [README](README.md) before changing the preview or the packager.

## Quick start

```bash
./gradlew :app:testDebugUnitTest :runtime:syncRuntimeTemplate :app:assembleDebug :runtime:assembleDebug
```

JDK 17 and an Android SDK. After touching `runtime/`, the IDE picks up a new template on the next `:app:assembleDebug` (`mergeDebugAssets` depends on `syncRuntimeTemplate`).

## Delivery loop (Issue → PR → CI → merge)

1. **Open an Issue first** (or claim an existing one): state the problem, the goal, and the acceptance criteria.
2. Branch from the latest `main`.
3. Open a PR against `main`. Use the PR template. The body **must contain `Fixes #N`**.
4. Wait for the required check `android-build` (`.github/workflows/ci.yml`).
5. A maintainer merges once CI is green. The Issue closes on merge. Never merge red, and never close an Issue early.

## Conventions

- Commit messages explain why.
- Never commit `local.properties`, keystores (`*.jks`, `*.p12`), IDE caches, or build outputs. `*.apk` is generated.
- **No root.** Do not add `su`, Magisk, or device-root dependencies.
- The packaged page is `assets/www/` plus `assets/webcfg.json`. Preview and the installed app both use the system WebView.
- `:runtime` stays a WebView shell. The editor and the packager stay in `:app`.

## Reporting bugs

Include the device model, the Android version, the HTML/CSS/JS that reproduces it, and what you expected to see.
