# AGENTS.md — WebViewIDE

Android IDE for HTML, CSS, and JavaScript. Edit a project, preview it in the system WebView, and package it into a signed APK. No root required.

## Layout

- `app/` — the IDE (Kotlin, Jetpack Compose, package `dev.webviewide`). Preview uses `androidx.webkit.WebViewAssetLoader` against the project `src/` directory.
- `runtime/` — the packaged app. A single `RuntimeActivity` loads `assets/www/` plus `assets/webcfg.json`. Its release APK is copied to `app/src/main/assets/runtime/template.apk` by the `syncRuntimeTemplate` Gradle task. `*.apk` is gitignored; CI generates the template.
- Packaging rewrites that template with ARSCLib and apksig (`app/src/main/java/dev/webviewide/build/`).

## Build & verify

```bash
./gradlew :app:testDebugUnitTest :runtime:syncRuntimeTemplate :app:assembleDebug :runtime:assembleDebug
```

`:app:mergeDebugAssets` depends on `:runtime:syncRuntimeTemplate`, so a plain `:app:assembleDebug` also refreshes the template. JDK 17. Do not commit `local.properties`, keystores, or build outputs.

## Delivery loop

1. Feature PRs target `main` only; never push feature work directly to `main`.
2. Every intentional change starts from a GitHub Issue (reuse an open one when it exists).
3. PR body must include `Fixes #N` (or `Closes #N`). Issues close on merge only — never on PR open, never while CI is red.
4. **CI is the merge gate.** Required check: `android-build` (`.github/workflows/ci.yml`). Do not merge red; CI never closes Issues.
5. One primary Issue per PR; link extra Issues without closing keywords.
6. Never commit secrets or machine-local junk: `local.properties`, keystores (`*.jks`, `*.p12`), IDE caches, build outputs.
7. If merge permission is missing: open the PR, comment on the Issue with links, leave the Issue open, hand off to a maintainer.

## Project invariants

- **No-root policy**: never introduce `su` / Magisk / device-root dependencies. The IDE and the packaged app run as a normal app.
- The packaged page lives at `assets/www/`. `assets/webcfg.json` names the entry file (default `index.html`).
- Preview and the packaged app both render with the system WebView. Do not add a second UI description format.
- `:runtime` is only the WebView shell. Do not put the editor or the packager into it.
- Material 3 comes from the Compose BOM. Do not override it with an alpha.
