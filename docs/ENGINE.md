# LuaX Engine Internals (ENGINE)

[简体中文](ENGINE.zh-CN.md)

> For **engine maintainers**. The authoritative definition of language semantics (dialect, stdlib, UI DSL, runtime behavior) lives in [LUAX.md](LUAX.md) — this file does not repeat it; the cross-language host contract is [PLATFORM_ABI.md](PLATFORM_ABI.md). Changing `engine/lx.c` requires updating LUAX.md in the same change (AGENTS.md rule 8).

## 1. Architecture

- **One source, three build targets**: `engine/lx.c` (~2200 lines, single file) compiles into the desktop CLI (`make`) and into `libluax.so` for both `:app` and `:runtime`. Each Android module carries a byte-identical copy of `luax_jni.c` — JNI-layer changes must be synced to both (a zero diff is the norm).
- **Hybrid execution**: tree-walking interpreter + bytecode VM (function bodies compile on first call; the top-level chunk compiles on every run/dostring/repl/require; uncompilable constructs fall back transparently; the VM only accelerates, never changes semantics). VM design, compile coverage, and benchmarks: [BYTECODE_VM.md](BYTECODE_VM.md).
- **Memory**: arena allocation, no GC; `lx_close` frees everything. Script memory grows monotonically — don't write leak-shaped long-running scripts; big strings/buffers use malloc+free (`st_tconcat` is the pattern).
- **Debug protocol**: `lx_debug_*` (breakpoints / conditional breakpoints / logpoints / stepping / watches / eval) is wired only into `:app`'s EngineHost — `:runtime` does not carry it; never copy debug calls across modules.

## 2. Implementation notes (easy traps)

- **Serialization** (`jnode`): tree → `{type, props, children}` JSON; function props register into `S->handlers[id]`, and **ids are re-assigned on every tree rebuild** (the host re-reads each round); capped at 1024. Prop key order is hash-slot order — test assertions may only use substring matching.
- **invoke semantics**: `lx_invoke` judges whether the handler returned a new tree from `callValue`'s **direct return value** (not `S->retbuf`, which later calls would pollute); nil → re-serializes `app_view` (a view function is re-called). The language-level contract is LUAX.md §4.2.
- **Guards**: the `STEP` macro (cancel + steps) sits on statements/loops/every VM instruction; `lx_run` clears stale cancel flags, `lx_invoke` does not.
- **Array part**: `Table` stores integer keys `1..acap` in `arr[]` exclusively — `tget`/`tset` route by key, `agrow` doubles `acap` and migrates covered hash entries (non-nil writes within 2× of `acap` trigger growth; sparse far keys stay hash-side). `next` iterates arr first then hash; `ahi`/`tlen` unchanged. Sequence-heavy loops run ~5× faster (1M appends + 3M reads: 0.31s → 0.06s).
- **Length hint**: `Table.ahi` caches the verified non-nil integer prefix `1..ahi`; `tlen` resumes the scan at `ahi+1` (same first-nil border as a full scan — the hint never over-claims) and `tset` grows/shrinks it (`v` non-nil at `ahi+1` → `ahi++`; nil write to an integer key inside `[1,ahi]` → `ahi=k-1`). `tset` is the sole raw-write funnel (`__newindex` intercepts above it), so `t[#t+1]=v` append loops drop from O(n²) to O(n) — 500k appends >5min → ~0.6s.
- **Dynamic-scope guard**: at compile time the VM records names treated as globals into `Proto->gk`; before every call `bc_globals_still_global` re-verifies there is no shadowing — a hit falls back to tree-walking. Names found on the definition-site Env chain compile to `GETENV`/`SETENV` (capmode keeps locals env-resident for closure capture) — see BYTECODE_VM.md.
- **Array part**: integer keys `1..acap` live only in `arr[]`; `agrow` doubles `acap` and migrates covered hash entries; `next` iterates array part first. Dense-sequence loops ~5x.
- **Length prefix cache**: `Table.ahi` is the verified non-nil integer prefix; `tlen` resumes at `ahi+1` (identical result to a full scan — the hint never over-claims). `t[#t+1]=v` append loops: O(n^2) -> O(n).
- **Slot caches** (`Proto->ec`, per-pc): `GETENV`/`SETENV` cache `{cur-env, chain signature, table, slot}`; `GETGLOBAL`/`SETGLOBAL`/`GETFIELD`/`SETFIELD` cache `{table, gen, slot}`. **Invariant**: `Table.gen` must bump on every structural hash change — insert, `tresize`, `agrow`. In-place value writes don't bump (the cache re-reads `.v`). The env signature `sum(env->gen + env->vars->gen)` is exact for a fixed chain, not heuristic. Field hits only serve present non-nil entries — a nil-valued slot must still take `__index`/`__newindex`.
- **`S->twrites`** bumps inside `tset` (the single raw-write funnel); `lx_invoke` skips `lx_build_tree` when an event performs zero writes and `app_view` is the same static table — retained JSON + handler ids stay valid.
- **`internName` is pointer-keyed** (`char*` identity, not content): two identical names from different AST sites get different Strs — slot-cache `keyIs` therefore falls back to hash+memcmp after the pointer compare.
- **`newStrBuf`/`strSeal`**: builtins that generate bytes (`string.upper/rep/char/reverse`, concat fusion) write directly into the Str buffer then seal the hash — no temp-buffer double copy.
- **`toStrx` int fast path**: integral `|d|<1e14` emits digits directly (identical to `%.14g`); `-0.0` keeps its sign via `signbit`; larger/non-integral values stay on snprintf.
- **`S->no_bc`**: `LUAX_NO_BC` is read once at `lx_new` — it is process-level config, not per-call.

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
