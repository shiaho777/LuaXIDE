# Contributing Guide

[简体中文](CONTRIBUTING.zh-CN.md)

Thanks for your interest in LuaXIDE — a pure on-device, root-free Lua IDE for Android. Before starting, read the [README](README.md) for the architecture.

## Quick start

```bash
# Engine test suite (requires clang)
make -C engine test

# Android build (requires JDK 17 + Android SDK)
./gradlew :app:assembleDebug :runtime:assembleDebug

# Sync the template APK after touching runtime
./gradlew :runtime:syncRuntimeTemplate
```

On-device self-check: after installing the debug build, open the in-app "Self-check" panel (sandbox / proot / stdin / cancel / template / install — six items), or use `scripts/device-checklist.sh` with adb to inspect logs.

## Delivery loop (Issue → PR → CI → merge)

1. **Open an Issue first** (or claim an existing one): state the problem / goal / acceptance criteria.
2. Branch from the latest `main`, named like `codex/topic` or `feat/topic`.
3. Open a PR against `main`, use the PR template, and **it must contain `Fixes #N`**.
4. Wait for the required CI checks to go green: `engine-tests`, `engine-js-tests`, `engine-py-tests`, `android-build` (see `.github/workflows/ci.yml`).
5. A maintainer merges once CI is green; the Issue closes automatically on merge. Never merge red, never close an Issue early.

## Conventions

- Commit messages explain the "why", not just the "what".
- Never commit machine-local files or secrets: `local.properties`, any `*.jks` / `*.p12` keystore, IDE caches, build outputs.
- Engine (`engine/lx.c`) changes must keep `make -C engine test` fully green; when adding capability, add a `t*` test alongside.
- `:app` and `:runtime` each carry an `EngineHost`: the app variant has the debug API, the runtime variant does not — never copy debug calls across modules.
- **The no-root policy is inviolable**: no su / Magisk / device-root dependencies; proot stays unprivileged userland only.

## Reporting bugs

When opening an Issue please include: device model and Android version, a reproducing Lua script, complete output from the terminal or log panel, and expected vs. actual behavior.
