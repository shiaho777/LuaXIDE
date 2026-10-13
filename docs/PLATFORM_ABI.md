# LuaX Platform ABI (engine ↔ host)

[简体中文](PLATFORM_ABI.zh-CN.md)

> This file is the **host contract** between the LuaX engine (`engine/lx.c`, `lx.h`) and the Android host (`EngineAdapter` / `EngineHost`). Changing run / invoke / cancel / tree-JSON behavior must update this file in the same change and keep `engine/tests/t26_invoke_tree.c` passing. Language dialect: [LUAX.md](LUAX.md). Engine internals: [ENGINE.md](ENGINE.md).

## 1. Platform layers

```
LuaX script
   │ drives
   ▼
UI tree: {type, props, children} JSON   ← the renderer only speaks this
   ▲
   │ run(src) / invoke(id, payload) / cancel / step-limit
host adapter (EngineAdapter @ Kotlin / lx.h)
   │
   └── lx.h     LuaX engine     t1–t28 tests
```

**Core principle: the renderer follows the engine's JSON tree.** Script syntax lives in LUAX.md; tree structure, event semantics, and host behavior live here.

## 2. Host API surface (C contract)

| Contract | lx.h |
|---|---|
| create/destroy | `lx_new` / `lx_close` |
| run | `lx_run(S, src, err, errlen)` |
| event callback | `lx_invoke(S, id, arg, err, errlen)` |
| tree/output | `lx_last_json` / `lx_last_output` |
| cancel | `lx_cancel` / `lx_clear_cancel` |
| step limit | `lx_set_step_limit` |
| module root | `lx_set_modroot` / `lx_modroot` |

The Kotlin side is [`EngineAdapter`](../app/src/main/java/dev/luaxide/engine/EngineAdapter.kt) (`state / run / invoke / cancel / close`). REPL, blocking stdin, debugger, and rootfs are **outside this ABI** — they live on `EngineHost` and generic callers must not touch them.

## 3. run semantics

1. `run(src)` executes synchronously on a dedicated engine thread; returns 0 on success, non-zero on failure with err filled.
2. `run` resets the output buffer and step counter at start, and clears any stale cancel flag (a new run is unaffected by an old cancel).
3. The script's return value decides the first frame:
   - returns a ui tree (Lua table tagged `__ui`) → serialized as tree JSON;
   - returns a **function** → remembered as the live view, called immediately to produce the first frame; on later invokes, if the handler does not return a tree it is re-called;
   - anything else / no return → tree is `null`; the host returns to the idle state.
4. `print` is captured into the output buffer, pulled by the host via `last_output` — the engine never writes to the terminal directly.

## 4. invoke & re-render contract

`invoke(handlerId, payload)`:

1. Function props serialize in the tree JSON as `{"__handler": N}`; **ids are re-assigned on every tree rebuild** — the host re-reads them each round.
2. A non-null `payload` is passed as the handler's first (and only) argument: `input.onChange(text)` / `input.onSubmit(text)` receive the field text as a string; `slider.onChange(v)` receives the numeric value as a string; `switch.onToggle(v)` receives `"true"`/`"false"`; all other events take no argument.
3. **Re-render decision**:
   - handler returns a ui tree → the new tree replaces the current view (and any stored view function is dropped);
   - handler returns a function → it becomes the new live view and is called immediately to produce the new tree;
   - nil / non-tree return → **the old tree is retained**; if a view function is stored, it is re-called first and then serialized (LUAX.md §4.2 path A).
4. Handler-internal errors: return non-zero + error message (prefixed `line N:` when a line is known); the engine stays usable and **the current tree stays unchanged** — including the case where the handler succeeded but the view function threw during re-serialization (the json buffer and handler table are retained as a whole).

## 5. Runtime guards

- **Cancel**: `cancel()` sets a cooperative cancel flag (Lua polls via the STEP macro); hitting it reports `"cancelled by user"`. `invoke` does **not** clear the cancel flag — an event handler fired after a mid-run cancel is cancelled too; `run` does clear it.
- **Step limit**: `set_step_limit` (App-side default 50,000,000); exceeding reports `"execution step limit exceeded (possible infinite loop)"`. The host does no re-translation of that message.

## 6. UI tree

The engine exposes 24 constructors (`app column row text button card input image icon spacer divider scrollview list listitem stack page switch checkbox box slider progress dialog tabs tab`), producing `{type, props, children}` nodes. Prop semantics (canonical names only — removed alias chains are a documented breaking migration, see LUAX.md §5; defaults, event names, string-constructor sugar for `text`, string children wrapping) follow the prop table in LUAX.md §5. Conformance tests assert per type (§7).

## 7. Conformance tests

| Assertion | Test |
|---|---|
| invoke tri-state (adopt/retain/error) | `t26_invoke_tree.c` |
| payload reaches the handler | t26 |
| all 24 component types serialize | t15 (constructors); t22 (new types / JSON) |
| text-only string constructor / string children | t15 / t22 |
| print capture / error line numbers | t22 / t23 |
| cancel / step-limit semantics | t19 |

Adding a contract capability extends these tests in the same change (AGENTS.md).

## 8. Packaging & standalone run

- The template runtime (`:runtime` release APK → `template.apk`) embeds `libluax.so` for each ABI.
- `entryFile` in `luaxcfg.json` is a Lua path; `RuntimeActivity` always uses `EngineHost`.
- Pre-packaging smoke validation runs the entry through `EngineHost` (`BuildPipeline.validateEntry`).
- Project sources ship under `assets/lua/`.
