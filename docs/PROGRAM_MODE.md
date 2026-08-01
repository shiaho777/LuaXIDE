# Program Mode (pure code / terminal)

## Rule
- If a script **returns a UI tree** → visual preview
- If a script **returns no UI** (print-only / compute) → **terminal preview**

## No-root policy
LuaXIDE never requires root / Magisk / su.

Program runs go through `NoRootRuntime`:
- sandbox under app private storage: `filesDir/sandbox/<projectId>/`
- `HOME` / `TMPDIR` / `work` confined there
- `LUAX_NO_ROOT=1`
- future **proot** (optional) stays unprivileged userland rootfs — still no device root

## Create
New project → **Program** seeds a pure `print` script.

## Package
Packaged runtime also shows terminal output when the entry returns no UI.

## Interactive terminal
After a pure program run (or in terminal mode), the bottom input accepts:

- `print(1+2)` normal lua statements
- `=1+2` sugar for `print(1+2)`
- assignments keep globals across lines (`x=1` then `=x`)
- `.clear` clear scrollback
- `.help` command list
- `.run` re-run the open file

Policy remains **no-root** (`NoRootRuntime` sandbox under app private storage).

## Blocking stdin + cancel + proot
See `docs/PROOT_AND_STDIN.md`.

- `io.read()` / `input()` block until terminal sends a line
- **stop** / `.stop` cancels cooperative execution (incl. blocked read)
- Proot userland rootfs under `filesDir/proot-rootfs` (optional binary in assets)
