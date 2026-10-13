# Program Mode → Console model

[简体中文](PROGRAM_MODE.zh-CN.md)

> 2026-09 update: the interactive terminal merged into the console. This document describes the current model.

## Rule (single-output principle)

- Script **returns a ui tree** → the preview panel (Compose render, Execution is truth)
- `print` output / runtime errors / system logs → the **bottom console** (the only output surface)
- REPL eval / `io.read()` replies → the input line at the console's bottom

The preview panel no longer has a Terminal state: `PreviewKind` collapses to `Ui / Error / Idle`; after a pure-output program runs, the preview returns to idle and output lives in the console.

## Console (LogSheet / LogPanel)

- Timestamped streaming list; level filter (V/D/I/W/E), regex filter, pause, clear, export (.log/.json)
- Error lines are clickable → jump to the source line
- Persistent input line at the bottom:
  - normally a REPL: `print(1+2)`, `=1+2` sugar, globals persist across lines
  - when the program blocks in `io.read()` it switches to reply mode (tertiary banner hint)
- dot commands: `.run` `.stop` `.clear` `.help`

## No-root policy (unchanged)

LuaXIDE never requires root / Magisk / su. Program runs go through `NoRootRuntime`:
- sandbox under app private storage: `filesDir/sandbox/<projectId>/`
- `HOME` / `TMPDIR` / `work` confined there, `LUAX_NO_ROOT=1`
- optional proot stays unprivileged userland rootfs — still no device root

## Blocking stdin + cancel + proot

See `docs/PROOT_AND_STDIN.md`. Semantics unchanged:
- `io.read()` blocks until the console input sends a line
- **stop** / `.stop` cancels cooperative execution (incl. blocked read)
- Proot userland rootfs under `filesDir/proot-rootfs` (optional binary in assets)

## Package

The packaged runtime renders the ui tree when the entry returns one; print output
goes to the packaged app's own console view.
