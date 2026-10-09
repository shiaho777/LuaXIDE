# LuaX

A Lua dialect built for **writing apps on your phone** — HTML and CSS for the page, Lua for the behavior, a single-file C engine — plus an Android IDE (LuaXIDE) that packages it into a standalone APK.

[简体中文](README.zh-CN.md)

<p align="center">
  <img src="docs/screenshot.png" width="340" alt="LuaXIDE: code editor on the left, live preview on the right">
</p>

```lua
-- This is LuaX. The page is index.html; this file is the logic.
local html = require("html")
local count = 0

html.on("plus", "click", function()
  count = count + 1
  html.setText("count", "taps: " .. count)
end)

html.setText("count", "taps: 0")
```

- **HTML page**: real HTML and CSS in a WebView. Lua registers events and applies DOM operations. JavaScript and Python projects still use the JSON UI tree.
- **Single-file engine**: `engine/lx.c` is ~2200 lines of C, tree-walking + bytecode VM hybrid (numeric loops ~11×)
- **Modern stdlib**: `string.format` / pattern matching (`find gsub match gmatch`) / `math.*` / `table.sort`
- **No root**: sandboxed execution; the IDE packages projects into standalone signed APKs
- **Three languages**: LuaX pages are HTML. JavaScript (QuickJS) and Python (MicroPython) share the JSON UI tree.

## Thirty seconds to running

```bash
make -C engine lx          # build the desktop CLI (needs clang)
./engine/lx your.lua       # run
./engine/lx --ui your.lua  # print the DOM-op JSON (desktop check; it does not draw a page)
```

Or install LuaXIDE (Android) — a new project seeds the counter above; debugging (breakpoints / stepping / watches) and the console (REPL, `io.read` replies) live inside the IDE.

## Language at a glance

```lua
-- a corner of the standard library
print(string.format("%d %s %5.2f", 42, "hi", 3.14159))             -- 42 hi  3.14
print(("2026-09-09"):match("(%d+)-(%d+)-(%d+)"))                    -- 2026 09 09
for w in ("one two three"):gmatch("%a+") do io.write(w, ".") end   -- one.two.three.

local t = {5, 2, 8, 1}
table.sort(t)                                                       -- {1,2,5,8}
print(math.floor(3.7), math.random(1, 6), #t)

-- lexical closures + metatables
local proto = { greet = function(self) return "hi " .. self.name end }
local obj = setmetatable({ name = "luax" }, { __index = proto })
print(obj:greet())                                                  -- hi luax
```

LuaX is a dialect subset of Lua 5.1: closures and metatables are in; numbers are doubles only; table constructors expand multi-values in every positional field; `goto`/`load` are out. Full differences and the per-function stdlib reference live in **[docs/LUAX.md](docs/LUAX.md)**.

## Documentation

| You want to… | Read |
|---|---|
| **Write LuaX programs** | [docs/LUAX.md](docs/LUAX.md) — language reference: dialect differences, per-function stdlib, the HTML host, metatables |
| Port / align another language engine | [docs/PLATFORM_ABI.md](docs/PLATFORM_ABI.md) — JSON UI tree for JavaScript and Python; LuaX HTML is specified in LUAX.md |
| Maintain the LuaX engine itself | [docs/ENGINE.md](docs/ENGINE.md) + [docs/BYTECODE_VM.md](docs/BYTECODE_VM.md) — architecture, test workflow, extension checklists, VM design |
| Understand IDE behavior | [docs/PROGRAM_MODE.md](docs/PROGRAM_MODE.md), [docs/PROOT_AND_STDIN.md](docs/PROOT_AND_STDIN.md) |

Every doc ships in English (default) and Chinese (`*.zh-CN.md` sibling). LUAX.md's code blocks are actually executed by CI — the documentation is the test, so it can't silently drift.

## Repository layout

```
engine/       LuaX engine (lx.c) — one source for the desktop CLI and both Android modules
engine-js/    JavaScript engine (QuickJS facade), JSON UI tree
engine-py/    Python engine (MicroPython facade), JSON UI tree
app/          LuaXIDE itself (Kotlin + Compose): edit / debug / console / packaging
runtime/      packaging template: Lua pages in a WebView; JS and Python still render UI trees
docs/         the documents listed above
```

## Build & test

```bash
make -C engine test      # Lua engine: t1–t28 + bc-diff differential + doc-example gate → ALL TESTS PASSED
make -C engine-js test   # JS engine: j1–j6 conformance
make -C engine-py test   # Python engine: p1–p2
./gradlew :app:assembleDebug :runtime:assembleDebug   # Android (needs SDK / JDK 17)
```

CI runs all of the above on every PR — `engine-tests` / `engine-js-tests` / `engine-py-tests` / `android-build` are the four required merge-gate checks.

## Contributing

Issues and PRs welcome. Process and conventions: [CONTRIBUTING.md](CONTRIBUTING.md); coding agents read [AGENTS.md](AGENTS.md) first.

Delivery loop: **Issue → PR (base `main`, body contains `Fixes #N`) → CI gates → merge → the Issue auto-closes**.

## License

Released under [Apache-2.0](LICENSE). Third-party components shipped with the repo (QuickJS, ARSCLib, apksig, Termux proot, …) are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
