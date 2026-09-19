#!/usr/bin/env bash
# check_sync.sh — enforce the :app ↔ :runtime byte-identical file invariant
# (AGENTS.md "Project invariants"). Paired files that must stay identical are
# listed explicitly; the known dual-variant files (debug API in :app, logging
# sink in :app, IDE chrome springs) are whitelisted.
set -u
cd "$(dirname "$0")/.."

# These MUST be byte-identical between app/ and runtime/.
PAIRED="
src/main/cpp/luax_jni.c
src/main/cpp/mpy_jni.c
src/main/cpp/qjs_jni.c
src/main/java/dev/luaxide/assets/AssetResolver.kt
src/main/java/dev/luaxide/engine/EngineAdapter.kt
src/main/java/dev/luaxide/engine/JsNative.kt
src/main/java/dev/luaxide/engine/PyNative.kt
src/main/java/dev/luaxide/engine/UiNode.kt
src/main/java/dev/luaxide/ui/runtime/ComponentRegistry.kt
src/main/java/dev/luaxide/ui/runtime/NodeProps.kt
src/main/java/dev/luaxide/ui/runtime/TreeReconciliation.kt
src/main/java/dev/luaxide/ui/runtime/UiTreeRenderer.kt
src/main/java/dev/luaxide/ui/theme/Color.kt
src/main/java/dev/luaxide/ui/theme/Theme.kt
src/main/java/dev/luaxide/ui/theme/Type.kt
"

# These are intentionally different (app-only debug API / LogSink / IDE chrome).
DUAL_VARIANT="
src/main/java/dev/luaxide/engine/EngineHost.kt
src/main/java/dev/luaxide/engine/JsEngineHost.kt
src/main/java/dev/luaxide/engine/PyEngineHost.kt
src/main/java/dev/luaxide/engine/LuaxNative.kt
src/main/java/dev/luaxide/ui/runtime/Motion.kt
"

fail=0
for f in $PAIRED; do
  if [ ! -f "app/$f" ] || [ ! -f "runtime/$f" ]; then
    echo "MISSING: $f (must exist in both modules)"
    fail=1
    continue
  fi
  if ! diff -q "app/$f" "runtime/$f" >/dev/null; then
    echo "DRIFT:   $f — app and runtime copies differ"
    fail=1
  fi
done

# also flag any file that is shared but not accounted for in either list
SHARED=$(comm -12 \
  <(cd app && find src -type f \( -name '*.kt' -o -name '*.c' -o -name '*.h' \) | sort) \
  <(cd runtime && find src -type f \( -name '*.kt' -o -name '*.c' -o -name '*.h' \) | sort))
for f in $SHARED; do
  if ! printf '%s\n%s\n' "$PAIRED" "$DUAL_VARIANT" | grep -Fxq "$f"; then
    echo "UNTRACKED: $f is shared but not in PAIRED or DUAL_VARIANT — add it to scripts/check_sync.sh"
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "app/runtime sync check ok ($(echo "$PAIRED" | wc -w | tr -d ' ') paired files identical, $(echo "$DUAL_VARIANT" | wc -w | tr -d ' ') dual-variant)"
fi
exit $fail
