#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/runtime/build/outputs/apk/release/runtime-release.apk"
if [[ ! -f "$APK" ]]; then
  APK=$(ls "$ROOT"/runtime/build/outputs/apk/release/*.apk | head -1)
fi
DEST_DIR="$ROOT/app/src/main/assets/runtime"
mkdir -p "$DEST_DIR"
cp "$APK" "$DEST_DIR/template.apk"
echo "synced $(ls -lh "$DEST_DIR/template.apk")"
