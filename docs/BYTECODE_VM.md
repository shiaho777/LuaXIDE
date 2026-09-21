# Bytecode VM (Phase 1b, v0 design & status)

[简体中文](BYTECODE_VM.zh-CN.md)

`engine/lx.c` was originally a pure tree-walking interpreter (Phase 1a). It now carries a conservatively hybrid bytecode VM: function bodies attempt compilation to register bytecode on first call, and the **top-level chunk** is compiled the same way on every `run`/`dostring`/`repl`/`require` (wrapped in a synthetic 0-param `Func`); on success a stack-based VM executes them, otherwise they fall back transparently to tree-walking. **Fallback is the semantic safety net — the VM only accelerates; it never changes observable behavior.**

## How it works

```
callValue(T_FN)
  ├─ debugger off && LUAX_NO_BC unset?
  │    ├─ first call: bc_build() tries to compile the body
  │    │    ├─ success → per-call global-name shadowing check → vm_call() runs bytecode
  │    │    └─ failure → mark bc_tried, tree-walk forever
  │    └─ Proto exists → check → vm_call()
  └─ otherwise (debug session / disabled) → tree-walking path (original logic unchanged)

execChunk(chunk)                       # lx_run / lx_dostring / lx_repl / require
  ├─ debugger off && LUAX_NO_BC unset?
  │    ├─ wrap chunk in synthetic Func{env=fresh chunk env} → bc_build()
  │    │    ├─ success + globals check → vm_call(); S->nret/retbuf rebuild the Flow
  │    │    └─ failure → fall through to the tree-walk loop
  └─ otherwise (debug session / disabled) → tree-walking path
```

The chunk path deliberately shares the function-body machinery: `capmode` detection puts top-level locals into `Env` scopes whenever any nested function exists (so closures capture them correctly); a chunk without nested functions keeps locals in registers. A `break` outside a loop soft-fails the compile exactly like inside function bodies and runs tree-walked — the parser still treats `break` as a block terminator, so surviving semantics are unchanged.

### Encoding & dispatch

- `BIns` is 4 bytes `{op,a,b,c}`; immediates live in a parallel `Proto.imm[pc]` u16 array (constant index ≤65535, or i16 jump delta — oversize functions soft-fail to tree-walk).
- Dispatch uses computed goto on GNU/Clang (one indirect branch per instruction; inner op-switches fold away since `in.op` is constant per label); other compilers keep the switch fallback.
- Conditions compile to a fused test-and-branch (`TEST`/`TESTN` carry the jump delta directly — no separate `JMP`); literal arithmetic/bitwise/comparison/concat folds to `LOADK` at compile time.

## v0 compile coverage (everything else falls back)

| Supported | Falls back |
|---|---|
| locals / assignment / multi-assign, call statements | vararg functions (`...`) |
| if/elseif/else, while, repeat (until sees in-body locals), numeric for, generic for | goto/label (the parser rejects these anyway) |
| break, return (incl. multi-value expansion) | |
| arithmetic/bitwise/comparison/concat, and/or short-circuit (value-preserving) | |
| table constructors (kv fields; positional fields expand multi-values per engine semantics) | |
| method calls `obj:m(...)`, metatable chains (__index/__newindex/__len/__tostring) | |
| **nested functions & captured variables** — closures bind the runtime Env chain (see below), including params, per-iteration loop vars, and `do`-block locals | |

### Captured variables: Env-resident "capmode"

This engine resolves names through the runtime `Env` chain (dynamic scope), and `Env` objects are already shared mutable binding containers — so closures need no separate upvalue-cell protocol:

1. A function whose body contains nested function definitions compiles in **capmode**: its locals are stored in `Env` scopes instead of registers. The VM emits `ENVOPEN`/`ENVCLOSE` at exactly the points the tree-walker calls `newEnv` — every `if`/`elseif`/`else`/`do` block, and **per iteration** for loop bodies (so each loop round's closures capture that round's loop var, matching `newEnv` per iteration).
2. Inside capmode code, every name compiles to `GETENV`/`SETENV` — literally `envGetFn`/`envAssignFn` — walking the same chain the tree-walker would; `DECL` mirrors `envDeclareFn`. `repeat ... until` still evaluates its condition before `ENVCLOSE`, so it sees body locals; `break` unwinds any open scope envs before leaving the loop.
3. `CLOSURE` builds a `Func` with `env = cur` (the live scope env) — identical to `K_FUNC` evaluation. The nested function compiles lazily through the usual `bc_build` path, so multi-level nesting works recursively.
4. In non-capmode functions a free name that hits the definition-site Env chain at compile time (`bc_is_upvalue`) also compiles to `GETENV`/`SETENV` instead of refusing; only names missing from the chain take the global path below.
5. Names treated as globals are still recorded in `Proto->gk`; before every call we re-verify "no outer scope declared a same-named local after first compilation" (`bc_globals_still_global`); detected shadowing falls back for that call.

## Semantic alignment with tree-walking

- Numeric for with step<=0 takes the descending branch (step=0 infinite loops are stopped by the step limit the same way);
- comparisons go through `toNum` (no string ordering); `and`/`or` return the deciding operand's original value;
- table-constructor positional fields **each expand multi-values** (existing engine semantics, non-standard Lua);
- runtime errors keep the `line N:` prefix (curLine driven by the instruction line table);
- cancel checks and the step limit apply on every instruction;
- pcall / debug-eval longjmp paths restore the VM stack pointer (`S->vtop`) in sync.

## Observability & tools

```bash
make -C engine test        # includes t25 cases, VM-participation assertions, bc-diff + bc-fuzz differential
make -C engine bench-bc    # dual-mode microbenchmark
./engine/lx --bc-dump f.lua   # disassemble every compilable function in a source file
LUAX_BC_STATS=1 ./engine/lx x.lua   # stderr prints "bc: N compiled calls, M fallbacks"
LUAX_NO_BC=1 ./engine/lx x.lua      # disable the VM entirely (troubleshooting)
```

`lx_bc_stats()` / `lx_bc_disassemble()` are exported from lx.h; the Android side can later wire them into a panel.

## Benchmarks (engine/tests/bench_bc.lua, Apple Silicon macOS, -O2)

| Case | Tree-walking | Bytecode VM | Speedup |
|---|---|---|---|
| fib(23) recursive | ~0.025s | ~0.013s | **~1.9x** |
| numeric loop ×3M | ~0.42s | ~0.030s | **~14x** |
| **top-level numeric loop ×5M** (main chunk) | ~1.17s | ~0.052s | **~22x** |
| string concat/len ×60k | ~0.36s | ~0.31s | ~1.2x (C string ops dominate) |
| build 200k-row table + pairs sum | ~0.13s | ~0.051s | **~2.6x** |

## Roadmap (not done, ordered by value)

1. vararg functions (`...`) — the only remaining hard fallback
2. breakpoints lowered onto the bytecode line table (today a debug session falls back to tree-walking wholesale — functional but slow)
