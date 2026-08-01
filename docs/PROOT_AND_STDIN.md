# Proot userland + blocking stdin + cancel

## No-root policy
- Never uses `su`, Magisk, or device root.
- Proot is unprivileged userland only.
- Sandbox lives under app private `filesDir`.

## Bundled assets
APK embeds Termux-built proot for `arm64-v8a`, `armeabi-v7a`, `x86_64`:
- `app/src/main/assets/proot/<abi>/bin/proot`
- libs: `libtalloc.so*`, `libandroid-shmem.so`
- `libexec/proot/loader` (+ loader32 when present)
- `min-rootfs.zip` minimal guest layout

First open of a project installs into `filesDir/proot-rootfs/` automatically (marker `.bundle-v2`).

## Terminal
- Pure program projects (no UI tree) open `TerminalFace`.
- Commands: `.clear` `.help` `.run` `.stop` `.proot`
- `io.read()` / `input()` block on stdin queue.
- While waiting, Enter feeds the blocked reader.
- `.stop` / stop button: `lx_cancel` + proot `destroyForcibly`.

## Verify on device
1. Create a **Program** project.
2. Run:
   ```lua
   io.write("name: ")
   local n = io.read()
   print("hi", n)
   ```
3. Type a name when prompted; use stop to cancel mid-wait.
4. In terminal: `.proot` → expect `proot-selftest` / `rootfs-ok` / `[proot ok]`.
