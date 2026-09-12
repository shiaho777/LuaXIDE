# LuaX Platform ABI (authoritative cross-language contract)

[简体中文](PLATFORM_ABI.zh-CN.md)

> This file defines the **language-neutral host contract**: any script engine that plugs into the LuaXIDE platform must implement the API surface and semantics below. Three reference implementations exist today: `engine/lx.c` (LuaX, Lua), `engine-js/qjs_x.c` (QuickJS, JavaScript), `engine-py/mpy_x.c` (MicroPython, Python — pilot, limitations in §10). **Changing contract behavior in any engine must update this file in the same change, and keep the conformance tests on both sides (t26 / j6) passing together** — the two suites assert the same contract, and any one-sided semantic drift is blocked by CI. Language dialect: [LUAX.md](LUAX.md) (LuaX) / each engine directory; LuaX engine internals: [ENGINE.md](ENGINE.md).

## 1. Platform layers

```
script (Lua / JS / future languages)
   │ drives
   ▼
UI tree contract: {type, props, children} JSON   ← language-neutral; the renderer only speaks this
   ▲
   │ run(src) / invoke(id, payload) / cancel / step-limit
host adapter (EngineAdapter @ Kotlin / facade C API)
   │
   ├── lx.h     LuaX engine (Lua)          t1–t28 tests
   └── qjs_x.h  QuickJS engine (JavaScript) j1–j6 tests
```

**Core principle: every language produces the same tree.** Language-adaptation differences may only exist in *how the driving script is written* (constructor syntax, closure syntax) — never in the tree structure, event semantics, or host behavior.

## 2. Host API surface (C contract)

Every engine facade must provide these functions (names may follow language conventions; semantics must match):

| Contract | lx.h (Lua) | qjs_x.h (JS) | mpy_x.h (Python, pilot) |
|---|---|---|---|
| create/destroy | `lx_new` / `lx_close` | `qjsx_new` / `qjsx_free` | `mpyx_new` / `mpyx_free` |
| run | `lx_run(S, src, err, errlen)` | `qjsx_run(x, src, err, errlen)` | `mpyx_run(x, src, err, errlen)` |
| event callback | `lx_invoke(S, id, arg, err, errlen)` | `qjsx_invoke(x, id, arg, err, errlen)` | `mpyx_invoke(x, id, arg, err, errlen)` |
| tree/output | `lx_last_json` / `lx_last_output` | `qjsx_last_json` / `qjsx_last_output` | `mpyx_last_json` / `mpyx_last_output` |
| cancel | `lx_cancel` / `lx_clear_cancel` | `qjsx_cancel` / `qjsx_clear_cancel` | `mpyx_cancel` / `mpyx_clear_cancel` (§10 note) |
| step limit | `lx_set_step_limit` | `qjsx_set_step_limit` | `mpyx_set_step_limit` (§10 note) |

The Kotlin side unifies everything as [`EngineAdapter`](../app/src/main/java/dev/luaxide/engine/EngineAdapter.kt) (`state / run / invoke / cancel / close`); Lua-only capabilities (REPL, blocking stdin, debugger, rootfs) are **outside the ABI** — they live in `EngineHost` and language-neutral callers must not touch them.

## 3. run semantics

1. `run(src)` executes synchronously on a dedicated engine thread; returns 0 on success, non-zero on failure with err filled.
2. **Per-engine guarantee**: `run` resets the output buffer and step counter at start, and clears any stale cancel flag (a new run is unaffected by an old cancel).
3. The script's return value decides the first frame:
   - returns a ui tree (Lua: table tagged `__ui`; JS: `{type: string, ...}` object) → serialized as tree JSON;
   - returns a **function** → remembered as the live view (`app_view` / `__lx_view` / `view()` convention), called immediately to produce the first frame; on later invokes, if the handler does not return a tree it is re-called — identical semantics on all three engines;
   - anything else / no return → tree is `null` (Lua) or keeps the previous tree (JS); the host returns to the idle state.
4. `print` (and equivalent output) is captured into the output buffer, pulled by the host via `last_output` — engines never write to the terminal directly.

## 4. invoke & re-render contract (the soul of the ABI)

`invoke(handlerId, payload)`:

1. Function props serialize in the tree JSON as `{"__handler": N}`; **ids are re-assigned on every tree rebuild** — the host re-reads them each round.
2. A non-null `payload` is passed as the handler's first (and only) argument: `input.onSubmit(text)` receives the field text; `switch.onToggle(v)` receives `"true"`/`"false"`; all other events take no argument.
3. **Re-render decision** (must be identical across the three engines):
   - handler returns a ui tree → the new tree replaces the current view (and any stored view function is dropped);
   - handler returns a function → it becomes the new live view and is called immediately to produce the new tree;
   - nil/undefined/non-tree return → **the old tree is retained** (the JS side must not clear the json early; on the Lua side `lx_build_tree` re-serialization guarantees it); if a view function is stored, it is re-called first and then serialized (LUAX.md §4.2 path A).
4. Handler-internal errors: return non-zero + error message (prefixed `line N:` when a line is known); the engine stays usable and **the current tree stays unchanged** — including the case where the handler succeeded but the view function threw during re-serialization (the json buffer and handler table are retained as a whole).

## 5. Runtime guards

- **Cancel**: `cancel()` sets a cooperative cancel flag (poll points are engine-specific: the STEP macro for Lua, an interrupt handler for JS); hitting it reports `"cancelled by user"`. `invoke` does **not** clear the cancel flag — an event handler fired after a mid-run cancel is cancelled too; `run` does clear it.
- **Step limit**: `set_step_limit` (App-side default 50,000,000); exceeding reports `"execution step limit exceeded (possible infinite loop)"`. The message is byte-identical across engines — the host does no re-translation.
- QuickJS extras: 64 MB memory limit, 4 MB stack limit (fixed in `qjsx_new`).

## 6. UI component parity

**The JS side must expose the same 16 constructors as Lua** (`app column row text button card input image spacer divider scrollview list listitem stack page switch`), producing identically-shaped tree nodes. Prop semantics (alias chains, defaults, event names) follow the prop table in LUAX.md §5 — that is the language-neutral renderer contract. Conformance tests assert per type (§7).

## 7. Conformance tests (the anti-drift mechanism)

The two suites assert **the same contract** and must evolve in lockstep:

| Assertion | Lua side | JS side |
|---|---|---|
| invoke tri-state (adopt/retain/error) | `t26_invoke_tree.c` | `j6_conformance.c` |
| payload reaches the handler | t26 | j6 |
| all 16 component types serialize | t15 (serialize coverage) | j6 |
| print capture / error line numbers | t22/t23 | j4/j6 |
| cancel / step-limit semantics | t19 (stdio/cancel) | j5_cancel.c |

**Adding a contract capability = extending both conformance suites in the same change** (AGENTS.md hard rule).

## 8. Packaging & standalone run (from phase three)

The packaging chain's multi-language support is closed-loop:

- The template runtime (`:runtime` release APK → `template.apk`) embeds **both engines** (libluax.so + libluaxjs.so × each ABI)
- `entryFile` in `luaxcfg.json` decides the language: ending in `.js` → RuntimeActivity picks `JsEngineHost`, otherwise `EngineHost` (see the isJs routing in RuntimeActivity)
- Pre-packaging smoke validation picks the engine by entry suffix the same way (BuildPipeline.validateEntry)
- Project sources ship whole under `assets/lua/` (historical directory name, language-neutral)

## 9. New-engine onboarding checklist

1. Pick a language engine (embed-friendly, cooperatively interruptible); write a facade implementing the §2 API surface + §3–§6 semantics;
2. Register in `Language.kt` (must pass 1–4 before supported=true);
3. Kotlin `EngineAdapter` implementation + JNI bridge;
4. **Write a conformance driver test** (copy j6's assertion structure); add to Makefile + `ci.yml` + branch protection;
5. Wire packaging: module CMake adds the .so → RuntimeActivity routing gains a branch → BuildPipeline.validateEntry gains a branch → syncRuntimeTemplate;
6. Update the §2/§7 tables in this file and cross-link LUAX.md/ENGINE.md.

## 10. Python engine (engine-py) status & limits

`engine-py/mpy_x.c` is built on the MicroPython v1.25.0 embed port (self-contained generated package) and is **wired into the App**: JNI bridge `mpy_jni.c` ×2, `PyEngineHost` (implements EngineAdapter; IDE and packaged-runtime dual variants), `Language.PYTHON.supported = true`, entry routing (`.py` → PyEngineHost) and pre-packaging validation are all live; emulator E2E: create a Python project → run renders → tap +1 re-renders (taps 0→1→2).

Implementation notes (parts matching the other engines): run → tree JSON + print capture; invoke(id, payload) → handler + re-render (view() first); `MICROPY_VM_HOOK_LOOP`-driven cooperative cancel and step limit (error messages byte-identical to Lua/JS, see p2); globals survive across invokes.

**Remaining limits (to be handled by follow-up Issues)**:
- no `import` module root path (multi-file projects unsupported; single-file projects fully work)
- one engine instance per process (facade uses a process-global `g_active`; the host uses it as a singleton)
- debugger/REPL/stdin do not apply to Python (allowed by contract: those are Lua-only extensions)
- the embed config is trimmed (compiler + GC + slice + str/float builtins); `re`/`json` etc. are missing — extend mpconfigport.h and regenerate micropython_embed/ when needed
