# AGENTS.md — LuaXIDE

Android IDE for Lua: edit, run, debug, and package Lua projects into installable APKs — no root required.

## Layout

- `engine/` — single-file C Lua interpreter (`lx.c`) + desktop CLI (`lx_main.c`); tests `t1`–`t24` in `engine/tests/`, driven by `engine/Makefile`
- `app/` — the IDE (Kotlin, Jetpack Compose, package `dev.luaxide`); JNI bridge in `app/src/main/cpp/` builds `libluax.so` from `engine/lx.c`
- `runtime/` — minimal template app; its release APK becomes `app/src/main/assets/runtime/template.apk` via the `syncRuntimeTemplate` Gradle task
- `docs/` — design notes: modules/UI, program mode, proot/stdin

## Build & verify

```bash
make -C engine test                                  # must print ALL TESTS PASSED
./gradlew :app:assembleDebug :runtime:assembleDebug  # Android build
./gradlew :runtime:syncRuntimeTemplate               # after touching runtime UI/assets
```

Engine changes must keep `make -C engine test` green; Kotlin changes must compile in both modules. Add a `t*` test when adding engine capability.
8. **docs/ENGINE.md is the engine's authoritative spec** — any change to `engine/lx.c`, the duplicated `luax_jni.c` pair, component render behavior, or the event/re-render contract must update ENGINE.md in the same change (see its §7 extension checklists).

## Delivery loop (hard rules)

1. Feature PRs target `main` only; never push feature work directly to `main`.
2. Every intentional change starts from a GitHub Issue (reuse an open one when it exists).
3. PR body must include `Fixes #N` (or `Closes #N`). Issues close on merge only — never on PR open, never while CI is red.
4. **CI is the merge gate.** Required checks: `engine-tests` and `android-build` (`.github/workflows/ci.yml`). Do not merge red; CI never closes Issues.
5. One primary Issue per PR; link extra Issues without closing keywords.
6. Never commit secrets or machine-local junk: `local.properties`, keystores (`*.jks`, `*.p12`), IDE caches, build outputs.
7. If merge permission is missing: open the PR, comment on the Issue with links, leave the Issue open, hand off to a maintainer.

## Project invariants

- **No-root policy**: never introduce `su` / Magisk / device-root dependencies. Execution stays sandboxed under app private storage; proot is unprivileged userland only.
- `engine/lx.c` is the single engine source for the CLI, `:app`, and `:runtime` (each Android module carries an identical `luax_jni.c`) — keep both copies in sync when touching the JNI layer.
- `:app` and `:runtime` each have an `EngineHost`: the app variant carries the debug API (`lx_debug_*`), the runtime variant does not — do not copy debug calls across modules.
- "Execution is truth": UI rendering follows the engine's JSON tree (`UiNode`), never a parallel schema.
