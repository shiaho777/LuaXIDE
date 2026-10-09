# LuaX Platform ABI (authoritative cross-language contract)

[简体中文](PLATFORM_ABI.zh-CN.md)

> This file defines the host contract. Three reference implementations exist today: `engine/lx.c` (LuaX, Lua), `engine-js/qjs_x.c` (QuickJS, JavaScript), `engine-py/mpy_x.c` (MicroPython, Python — pilot, limitations in §10). **JavaScript and Python share the JSON UI tree.** LuaX does not: its page is HTML and CSS, specified in [LUAX.md](LUAX.md) §4–§5 and asserted by `engine/tests/t26_invoke_tree.c`. A tree-contract change updates this file and `j6` together. A Lua HTML change updates LUAX.md and `t26` together. `t26` and `j6` are not the same suite. LuaX engine internals: [ENGINE.md](ENGINE.md).

## 1. Platform layers

```
script
   │
   ├── LuaX (lx.h)     HTML op JSON  →  WebView          t26
   └── JS / Python     UI tree JSON  →  Compose          j6 (JS); Python pilot
         {type, props, children}
```

**Core principle: JavaScript and Python produce the same tree.** Differences between those two may only exist in *how the driving script is written*. LuaX renders the project's HTML and CSS. `lx_invoke` on LuaX always fails; DOM events go through `lx_html_event`.

## 2. Host API surface (C contract)

Every engine facade must provide these functions (names may follow language conventions; semantics must match):

| Contract | lx.h (Lua) | qjs_x.h (JS) | mpy_x.h (Python, pilot) |
|---|---|---|---|
| create/destroy | `lx_new` / `lx_close` | `qjsx_new` / `qjsx_free` | `mpyx_new` / `mpyx_free` |
| run | `lx_run(S, src, err, errlen)` | `qjsx_run(x, src, err, errlen)` | `mpyx_run(x, src, err, errlen)` |
| event callback | `lx_html_event` (DOM). `lx_invoke` always errors | `qjsx_invoke(x, id, arg, err, errlen)` | `mpyx_invoke(x, id, arg, err, errlen)` |
| tree or ops / output | `lx_last_json` is a JSON array of DOM ops; `lx_html_clear_ops` drops it. `lx_last_output` | `qjsx_last_json` is the tree / `qjsx_last_output` | `mpyx_last_json` is the tree / `mpyx_last_output` |
| cancel | `lx_cancel` / `lx_clear_cancel` | `qjsx_cancel` / `qjsx_clear_cancel` | `mpyx_cancel` / `mpyx_clear_cancel` (§10 note) |
| step limit | `lx_set_step_limit` | `qjsx_set_step_limit` | `mpyx_set_step_limit` (§10 note) |
| module root | `lx_set_modroot` / `lx_modroot` | — | `mpyx_set_modroot` / `mpyx_modroot` |

The Kotlin side unifies everything as [`EngineAdapter`](../app/src/main/java/dev/luaxide/engine/EngineAdapter.kt) (`state / run / invoke / cancel / close`); Lua-only capabilities (REPL, blocking stdin, debugger, rootfs) are **outside the ABI** — they live in `EngineHost` and language-neutral callers must not touch them.

## 3. run semantics

1. `run(src)` executes synchronously on a dedicated engine thread; returns 0 on success, non-zero on failure with err filled.
2. **Per-engine guarantee**: `run` resets the output buffer and step counter at start, and clears any stale cancel flag (a new run is unaffected by an old cancel).
3. The script's return value decides the first frame for **JavaScript and Python**:
   - returns a ui tree (`{type: string, ...}`) → serialized as tree JSON;
   - returns a **function** → remembered as the live view (`__lx_view` / `view()` convention), called immediately to produce the first frame; on later invokes, if the handler does not return a tree it is re-called. Python discovers a global `view()` or `_lx_tree` instead (§10);
   - anything else / no return → tree is `null` (or, on JS, the previous tree may be kept; see the engine). The host returns to the idle state.
   - **LuaX** does not build a tree. `run` clears the op buffer, then `html.*` calls append DOM operations. `lx_last_json` is that array (`[]` when empty). A failed `run` drops the buffer.
4. `print` (and equivalent output) is captured into the output buffer, pulled by the host via `last_output` — engines never write to the terminal directly.

## 4. invoke & re-render contract (JSON tree: JavaScript and Python)

LuaX events are [LUAX.md](LUAX.md) §4.2: `html.on(id, event, fn)`, payload is one string, and a handler error rewinds the op batch from that call. This section is the tree contract only.

`invoke(handlerId, payload)`:

1. Function props serialize in the tree JSON as `{"__handler": N}`; **ids are re-assigned on every tree rebuild** — the host re-reads them each round.
2. A non-null `payload` is passed as the handler's first (and only) argument: `input.onChange(text)` / `input.onSubmit(text)` receive the field text as a string; `slider.onChange(v)` receives the numeric value as a string; `switch.onToggle(v)` receives `"true"`/`"false"`; all other events take no argument.
3. **Re-render decision** (JavaScript conformance target; Python limitations in §10):
   - handler returns a ui tree → the new tree replaces the current view (and any stored view function is dropped);
   - handler returns a function → it becomes the new live view and is called immediately to produce the new tree;
   - nil/undefined/non-tree return → **the old tree is retained** (the JS side must not clear the json early); if a view function is stored, it is re-called first and then serialized.
4. Handler-internal errors: return non-zero + error message (prefixed `line N:` when a line is known); the engine stays usable and **the current tree stays unchanged** — including the case where the handler succeeded but the view function threw during re-serialization (the json buffer and handler table are retained as a whole).

## 5. Runtime guards

- **Cancel**: `cancel()` sets a cooperative cancel flag (poll points are engine-specific: the STEP macro for Lua, an interrupt handler for JS); hitting it reports `"cancelled by user"`. `invoke` does **not** clear the cancel flag — an event handler fired after a mid-run cancel is cancelled too; `run` does clear it.
- **Step limit**: `set_step_limit` (App-side default 50,000,000); exceeding reports `"execution step limit exceeded (possible infinite loop)"`. The message is byte-identical across the wired engines — the host does no re-translation.
- QuickJS extras: 64 MB memory limit, 4 MB stack limit (fixed in `qjsx_new`).

## 6. UI component parity (JavaScript and Python)

**JavaScript exposes 19 constructors** (`app column row text button card input image spacer divider scrollview list listitem stack page switch box slider progress`). Python aims at the same node shapes. Prop names, defaults, event names, string-constructor sugar for `text`, and string-children wrapping live in the two `ComponentRegistry.kt` copies (they must stay byte-identical) and are asserted by `j6`. LuaX has none of these constructors. LUAX.md §5 is the HTML host, not this table.

## 7. Conformance tests (the anti-drift mechanism)

`t26` asserts the Lua HTML host. `j6` asserts the JavaScript tree. They are not mirrors.

| Assertion | Lua HTML (`t26` unless noted) | JS tree (`j6` unless noted) |
|---|---|---|
| DOM event payload, unknown id, error rewind, re-register | `t26_invoke_tree.c` | — |
| `lx_invoke` is retired | t26 | — |
| invoke tri-state (adopt/retain/error) | — | `j6_conformance.c` |
| payload reaches the handler | t26 (string payload) | j6 |
| all 19 component types serialize | — (`require("ui")` errors; t15 / t28) | j6 |
| print capture / error line numbers | t22/t23 | j4/j6 |
| cancel / step-limit semantics | t19 (stdio/cancel) | j5_cancel.c |

**A tree-contract capability extends `j6` in the same change. A Lua HTML capability extends `t26` and LUAX.md in the same change.**

## 8. Packaging & standalone run (from phase three)

The packaging chain's multi-language support is closed-loop:

- The template runtime (`:runtime` release APK → `template.apk`) embeds **multiple engines** (libluax.so + libluaxjs.so × each ABI)
- `entryFile` in `luaxcfg.json` decides the language: `.js` → `JsEngineHost`, `.py` → `PyEngineHost`, otherwise `EngineHost`. A Lua entry with a project HTML page (`index.html`, or `<script>.html` beside the entry) renders in a WebView. A Lua entry with no page keeps the print terminal. JS and Python still render the tree.
- Pre-packaging smoke validation picks the engine by entry suffix the same way (BuildPipeline.validateEntry)
- Project sources ship whole under `assets/lua/` (historical directory name, language-neutral)

## 9. New-engine onboarding checklist

1. Pick a language engine (embed-friendly, cooperatively interruptible); write a facade implementing the §2 API surface. Tree languages implement §3–§6. A language that renders HTML instead follows LUAX.md §4–§5 and does not emit the tree;
2. Register in `Language.kt` (must pass 1–4 before supported=true);
3. Kotlin `EngineAdapter` implementation + JNI bridge;
4. **Write a conformance driver test** (copy j6's assertion structure); add to Makefile + `ci.yml` + branch protection;
5. Wire packaging: module CMake adds the .so → RuntimeActivity routing gains a branch → BuildPipeline.validateEntry gains a branch → syncRuntimeTemplate;
6. Update the §2/§7 tables in this file and cross-link LUAX.md/ENGINE.md.

## 10. Python engine (engine-py) status & limits

`engine-py/mpy_x.c` is built on the MicroPython v1.25.0 embed port (self-contained generated package) and is **wired into the App**: JNI bridge `mpy_jni.c` ×2, `PyEngineHost` (implements EngineAdapter; IDE and packaged-runtime dual variants), `Language.PYTHON.supported = true`, entry routing (`.py` → PyEngineHost) and pre-packaging validation are all live; emulator E2E: create a Python project → run renders → tap +1 re-renders (taps 0→1→2).

Implementation notes (aligned with the JavaScript tree engine): run → tree JSON + print capture; invoke(id, payload) → handler + re-render (view() first); `MICROPY_VM_HOOK_LOOP`-driven cooperative cancel and step limit (error messages byte-identical to Lua/JS, see p2); globals survive across invokes. Python does not use the Lua HTML host.

Module root: `mpyx_set_modroot` mirrors `lx_set_modroot` — the dir is rebuilt into `sys.path` before each `run`, so `import helper` / `import pkg.mod` resolve sibling files next to the entry point (builtin modules still win). Wired through `PyNative.nativeSetModroot` → `PyEngineHost.setModuleRoot`, applied by the IDE run path, packaging validation, and `RuntimeActivity` (p3 covers flat modules, packages, in-handler imports and the no-modroot failure).

Multiple engines per process: each `MpyX` owns a private MicroPython ctx — `mp_state_ctx` is memcpy-swapped under a mutex around every VM window, so heaps, globals, handler tables and `sys.path` stay independent (p4 covers interleaved and concurrent use; windows serialize across engines).

Stdlib: `json` (vendored `extmod/modjson.c`), `re` (`extmod/modre.c` + `lib/re1.5`), `io.StringIO`/`BytesIO` are in; `open()` exists but raises `OSError` (no FileIO in the embed tree — by design). `pystack` is enabled for `re`'s local allocations.

Python is not covered by j6: p1_smoke.c and p2_cancel.c cover only their tested subset, not full component/event parity. Its global `view()` takes precedence over handler-returned trees; callable handler returns do not implement the JS live-view replacement. Do not infer full ABI parity from the counter smoke test. `t26` does not apply to Python.

**Remaining limits (to be handled by follow-up Issues)**:
- debugger/REPL/stdin do not apply to Python (allowed by contract: those are Lua-only extensions)
- no filesystem file objects: `open()` raises `OSError`; `io` is limited to in-memory streams (would need `extmod/vfs_*` vendored)
- `sys.modules` persists across `run()` calls in one engine (module cache is VM-level, not cleared with globals)
- engine windows serialize process-wide (the ctx swap mutex); CPU-bound scripts in different engines do not run truly concurrently
