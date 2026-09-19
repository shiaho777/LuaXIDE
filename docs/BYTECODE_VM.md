# Bytecode VM (Phase 1b, v0 design & status)

[简体中文](BYTECODE_VM.zh-CN.md)

`engine/lx.c` was originally a pure tree-walking interpreter (Phase 1a). It now carries a conservatively hybrid bytecode VM: function bodies attempt compilation to register bytecode on first call; on success a stack-based VM executes them, otherwise they fall back transparently to tree-walking. **Fallback is the semantic safety net — the VM only accelerates; it never changes observable behavior.**

## How it works

```
callValue(T_FN)
  ├─ debugger off && LUAX_NO_BC unset?
  │    ├─ first call: bc_build() tries to compile the body
  │    │    ├─ success → per-call global-name shadowing check → vm_call() runs bytecode
  │    │    └─ failure → mark bc_tried, tree-walk forever
  │    └─ Proto exists → check → vm_call()
  └─ otherwise (debug session / disabled) → tree-walking path (original logic unchanged)
```

### Encoding & dispatch

- `BIns` is 4 bytes `{op,a,b,c}`; immediates live in a parallel `Proto.imm[pc]` u16 array (constant index ≤65535, or i16 jump delta — oversize functions soft-fail to tree-walk).
- Dispatch uses computed goto on GNU/Clang (one indirect branch per instruction; inner op-switches fold away since `in.op` is constant per label); other compilers keep the switch fallback.
- Conditions compile to a fused test-and-branch (`TEST`/`TESTN` carry the jump delta directly — no separate `JMP`); literal arithmetic/bitwise/comparison/concat folds to `LOADK` at compile time.

## v0 compile coverage (everything else falls back)

| Supported | Falls back |
|---|---|
| locals / assignment / multi-assign, call statements | nested function definitions (closures capture Env — needs an upvalue mechanism) |
| if/elseif/else, while, repeat (until sees in-body locals), numeric for, generic for | vararg functions (`...`) |
| break, return (incl. multi-value expansion) | free variables (upvalues) — compile-time detection + call-time re-verification |
| arithmetic/bitwise/comparison/concat, and/or short-circuit (value-preserving) | goto/label (the parser rejects these anyway) |
| table constructors (kv fields; positional fields expand multi-values per engine semantics) | |
| method calls `obj:m(...)`, metatable chains (__index/__newindex/__len/__tostring) | |

### Dynamic-scope compatibility

This engine resolves names through the runtime Env chain (dynamic scope), not lexical scope. Therefore:

1. At compile time: `K_NAME` first checks local registers, then the definition-site Env chain (excluding globals) — a hit counts as an upvalue and **refuses compilation**;
2. At runtime: names treated as globals are recorded in `Proto->gk`; before every call we re-verify "no outer tree-walking scope declared a same-named local after first compilation" (see `bc_globals_still_global`); detected shadowing falls back for that call.

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
| string concat/len ×60k | ~0.36s | ~0.31s | ~1.2x (C string ops dominate) |
| build 200k-row table + pairs sum | ~0.13s | ~0.051s | **~2.6x** |

## Roadmap (not done, ordered by value)

1. **upvalues**: a Lua-style open/upvalue protocol, unlocking "nested functions inside functions" — the largest coverage win
2. main-chunk compilation (needs upvalues; top-level locals are capturable by closures)
3. breakpoints lowered onto the bytecode line table (today a debug session falls back to tree-walking wholesale — functional but slow)
