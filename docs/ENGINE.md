# LuaX Engine Internals (ENGINE)

[简体中文](ENGINE.zh-CN.md)

> For **engine maintainers**. The authoritative definition of language semantics (dialect, stdlib, UI DSL, runtime behavior) lives in [LUAX.md](LUAX.md) — this file does not repeat it; the cross-language host contract is [PLATFORM_ABI.md](PLATFORM_ABI.md). Changing `engine/lx.c` requires updating LUAX.md in the same change (AGENTS.md rule 8).

## 1. Architecture

- **One source, three build targets**: `engine/lx.c` (~2200 lines, single file) compiles into the desktop CLI (`make`) and into `libluax.so` for both `:app` and `:runtime`. Each Android module carries a byte-identical copy of `luax_jni.c` — JNI-layer changes must be synced to both (a zero diff is the norm).
- **Hybrid execution**: tree-walking interpreter + bytecode VM (function bodies compile on first call; uncompilable constructs fall back transparently; the VM only accelerates, never changes semantics). VM design, compile coverage, and benchmarks: [BYTECODE_VM.md](BYTECODE_VM.md).
- **Memory**: arena allocation, no GC; `lx_close` frees everything. Script memory grows monotonically — don't write leak-shaped long-running scripts; big strings/buffers use malloc+free (`st_tconcat` is the pattern).
- **Debug protocol**: `lx_debug_*` (breakpoints / conditional breakpoints / logpoints / stepping / watches / eval) is wired only into `:app`'s EngineHost — `:runtime` does not carry it; never copy debug calls across modules.

## 2. Implementation notes (easy traps)

- **Serialization** (`jnode`): tree → `{type, props, children}` JSON; function props register into `S->handlers[id]`, and **ids are re-assigned on every tree rebuild** (the host re-reads each round); capped at 1024. Prop key order is hash-slot order — test assertions may only use substring matching.
- **invoke semantics**: `lx_invoke` judges whether the handler returned a new tree from `callValue`'s **direct return value** (not `S->retbuf`, which later calls would pollute); nil → re-serializes `app_view` (a view function is re-called). The language-level contract is LUAX.md §4.2.
- **Guards**: the `STEP` macro (cancel + steps) sits on statements/loops/every VM instruction; `lx_run` clears stale cancel flags, `lx_invoke` does not.
- **Dynamic-scope guard**: at compile time the VM records names treated as globals into `Proto->gk`; before every call `bc_globals_still_global` re-verifies there is no shadowing — a hit falls back to tree-walking. Names found on the definition-site Env chain compile to `GETENV`/`SETENV` (capmode keeps locals env-resident for closure capture) — see BYTECODE_VM.md.

## 3. Test workflow

```bash
make -C engine test          # must print ALL TESTS PASSED; includes:
                             #   t1–t28 functional/contract tests, bc-diff differential (11 scripts),
                             #   bc-fuzz (seeded generated programs, VM on/off output diff),
                             #   doc-check (every LUAX.md code block actually run), CLI smoke
./engine/lx --ui foo.lua      # prints ---OUTPUT--- / ---TREE---
./engine/lx --bc-dump f.lua   # disassembly; LUAX_NO_BC=1 disables the VM; LUAX_BC_STATS=1 shows participation
make -C engine bc-fuzz FUZZ_N=200 FUZZ_SEED=7   # deeper divergence sweeps; a failing program is kept in FUZZ_DIR
```

Conventions:

- **New engine capability requires a new `t*` test** (AGENTS.md hard rule); new stdlib functions: positive assertions go in t6/t14/t25, negative cases (bad argument) go in t14's `musterr` list
- VM/tree-walk semantics fixes get regression asserts in t25 (multi-value expansion, loop control flow); bc-fuzz sweeps broader expression space — when it catches a divergence, the kept program plus seed is the repro
- invoke/UI contract work needs a C driver (see `t26_invoke_tree.c`: run → find handler id → invoke → assert JSON changes)
- Pure library functions are VM-agnostic — differential coverage is free; changes to execution semantics (name resolution / call conventions) must pass the differential
- **Editing a LUAX.md example = editing a test**: doc-check runs every block

## 4. Extension checklists

**Adding a stdlib function**: write `static Value st_xxx` in `lx.c` (type guards via `argStr`/`argTab`/`num2int`; errors always via `lx_rt_error`) → register in `openLibs` → tests (t6/t14/t25/t27) → add a row to LUAX.md §3 (signature + one-line semantics + runnable example) → optionally extend `LuaIntel.kt` completion.

**Adding a UI component** (five sync points, all required):

1. `engine/lx.c`: one `UICTOR(name)` line
2. Both `ComponentRegistry.kt` copies in `app/` and `runtime/` each gain a Composable (the diff must be zero afterwards)
3. `ComponentCatalog.kt` panel entry (`validateAgainstRegistry` catches misses)
4. `ApiDocs.kt` doc entry
5. Add a row to the LUAX.md §5 table; if the re-render contract is affected, sync LUAX.md §4.2 and PLATFORM_ABI.md

**Changing the event/re-render contract**: change `lx_invoke`/`lx_build_tree` → extend t26 cases → sync `luax_jni.c` ×2, `EngineHost` ×2, and the JS/Python engines (all PLATFORM_ABI conformance passing) → rewrite LUAX.md §4.2.

## 5. Maintenance conventions

- Changing `engine/lx.c`, either `luax_jni.c`, component behavior in `ComponentRegistry.kt`, or the event contract → **update LUAX.md (language surface) and this file (implementation surface, when relevant) in the same change**.
- The samples (`ProjectRepository.kt` seeds) are the contract's teaching vehicle: contract changes sync the samples, and bump `.samples-vN` as needed to force re-seeding on old devices.
- The extra springs in `Motion.kt` on the app side serve IDE chrome only and may differ from runtime; all other shared files must be byte-identical.
