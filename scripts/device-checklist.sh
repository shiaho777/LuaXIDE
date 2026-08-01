#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG="dev.luaxide"

echo "== LuaXIDE device checklist =="
echo "1) assemble"
cd "$ROOT"
./gradlew :app:assembleDebug --console=plain

echo "2) install"
adb wait-for-device
adb install -r "$APK"

echo "3) launch"
adb shell am start -n "$PKG/.MainActivity" >/dev/null

echo "4) logcat filter (20s) — 在应用内点「自检」全部检查"
timeout 20 adb logcat -v time LuaXIDE:D ENGINE:D proot:D *:S || true

echo "Done. In-app path: 侧栏/顶栏 → 自检 → 全部检查"
echo "Manual matrix:"
echo "  [ ] .proot"
echo "  [ ] stdin block + cancel"
echo "  [ ] Build APK + install"
