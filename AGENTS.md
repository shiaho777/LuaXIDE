# AGENTS.md — LuaXIDE

Android IDE for Lua: edit, run, debug, and package Lua projects into installable APKs — no root required.

## Layout

- `engine/` — single-file C Lua interpreter (`lx.c`) + desktop CLI (`lx_main.c`); tests `t1`–`t27` in `engine/tests/`, driven by `engine/Makefile`
- `engine-js/` — QuickJS-based JavaScript engine facade (`qjs_x.c` mirrors the `lx.h` host contract); tests `j1`–`j6` in `engine-js/tests/`; vendored upstream QuickJS in `engine-js/quickjs/`
- `engine-py/` — MicroPython engine facade (`mpy_x.c`, same host contract); tests in `engine-py/tests/`; `micropython_embed/` is the generated embed-port package (v1.25.0)
- `docs/PLATFORM_ABI.md` — the language-neutral host contract both engines implement; `engine/tests/t26_invoke_tree.c` and `engine-js/tests/j6_conformance.c` assert the SAME contract on both sides
- `app/` — the IDE (Kotlin, Jetpack Compose, package `dev.luaxide`); JNI bridge in `app/src/main/cpp/` builds `libluax.so` from `engine/lx.c`
- `runtime/` — minimal template app carrying BOTH engines (libluax + libluaxjs); its release APK becomes `app/src/main/assets/runtime/template.apk` via the `syncRuntimeTemplate` Gradle task (run it after touching runtime code)
- `docs/` — design notes: modules/UI, program mode, proot/stdin

## Build & verify

```bash
make -C engine test                                  # must print ALL TESTS PASSED
make -C engine-js test                               # must print ALL JS ENGINE TESTS DONE
make -C engine-py test                               # must print ALL PY ENGINE TESTS DONE
./gradlew :app:assembleDebug :runtime:assembleDebug  # Android build
./gradlew :runtime:syncRuntimeTemplate               # after touching runtime UI/assets
```

Engine changes must keep `make -C engine test` green; JS engine changes must keep `make -C engine-js test` green; Kotlin changes must compile in both modules. Add a `t*`/`j*` test when adding engine capability.
8. **docs/ENGINE.md is the engine's authoritative spec** — any change to `engine/lx.c`, the duplicated `luax_jni.c` pair, component render behavior, or the event/re-render contract must update ENGINE.md in the same change (see its §7 extension checklists).
9. **docs/PLATFORM_ABI.md is the cross-engine contract** — contract-level changes must update it AND extend the conformance assertions on BOTH engines (t26 for Lua, j6 for JS) in the same change. One-sided contract changes are forbidden.

## Delivery loop (hard rules)

1. Feature PRs target `main` only; never push feature work directly to `main`.
2. Every intentional change starts from a GitHub Issue (reuse an open one when it exists).
3. PR body must include `Fixes #N` (or `Closes #N`). Issues close on merge only — never on PR open, never while CI is red.
4. **CI is the merge gate.** Required checks: `engine-tests`, `engine-js-tests`, `engine-py-tests` and `android-build` (`.github/workflows/ci.yml`). Do not merge red; CI never closes Issues.
5. One primary Issue per PR; link extra Issues without closing keywords.
6. Never commit secrets or machine-local junk: `local.properties`, keystores (`*.jks`, `*.p12`), IDE caches, build outputs.
7. If merge permission is missing: open the PR, comment on the Issue with links, leave the Issue open, hand off to a maintainer.

## Project invariants

- **No-root policy**: never introduce `su` / Magisk / device-root dependencies. Execution stays sandboxed under app private storage; proot is unprivileged userland only.
- `engine/lx.c` is the single engine source for the CLI, `:app`, and `:runtime` (each Android module carries an identical `luax_jni.c`) — keep both copies in sync when touching the JNI layer.
- `:app` and `:runtime` each have an `EngineHost`: the app variant carries the debug API (`lx_debug_*`), the runtime variant does not — do not copy debug calls across modules.
- "Execution is truth": UI rendering follows the engine's JSON tree (`UiNode`), never a parallel schema.
