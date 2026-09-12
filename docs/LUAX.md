# LuaX Language Reference

[简体中文](LUAX.zh-CN.md)

> This document is the **single authoritative definition** of the LuaX language. The implementation is `engine/lx.c`; when doc and implementation disagree, the implementation wins and the doc gets fixed immediately (AGENTS.md rule 8). Every example in this file is executable — `make -C engine test` runs every code block; a stale example turns CI red.

## 1. What it is

LuaX is a **dialect subset** of Lua 5.1 semantics, built for writing and packaging small apps on Android:

- Single-file C engine (~2200 lines), tree-walking + bytecode VM hybrid, ~11× on numeric loops
- Declarative UI: the UI tree is an ordinary Lua table; event-driven re-render (§4)
- No root: sandboxed execution; one-tap packaging into a standalone signed APK
- The **reference language** of LuaXIDE's three-language platform (Lua / JavaScript / Python) — cross-language contract: [PLATFORM_ABI.md](PLATFORM_ABI.md)

```lua
-- 完整的交互 App:计数器
local ui = require("ui")
local count = 0

local function view()
  return ui.app {
    title = "Counter",
    ui.column {
      spacing = 12,
      ui.text { text = "taps: " .. count, size = 28 },
      ui.button { text = "+1", onClick = function() count = count + 1 end },
    },
  }
end

return view   -- 返回 view 函数 = 每次点击后自动重渲染(§4)
```

## 2. Differences from Lua 5.1

**Semantic differences** (behavior differs):

| Aspect | LuaX | Lua 5.1 |
|---|---|---|
| Numbers | double only; tostring uses `%.14g` | integer/float distinction exists |
| Table constructors | **every positional field expands multi-values**: `{f(), g()}` collects all returns | only the last field expands |
| String indexing | `("x").upper` exists (string library); unknown methods are `nil` | error (needs a metatable) |
| `<` `<=` comparisons | numeric coercion (strings compare via strtod) | numbers/strings compare separately |
| Scoping | lexical closures (standard); the bytecode VM re-verifies shadowing | lexical closures |

**Not supported** (writing it is an error):

- `goto` / labels
- `load` / `loadstring` / `dofile` (dynamic code)
- Metamethods limited to `__index` `__newindex` `__len` `__tostring` (no `__call`, `__eq`, arithmetic metamethods)
- Coroutines, the UTF-8 library, `string.dump`, `os.date`, module-system extensions beyond `require`

**Pattern matching**: byte-oriented (same as Lua 5.1), see §3.2.

## 3. Standard library

### 3.1 Base

| Function | Behavior |
|---|---|
| `print(...)` | tab-separated output, newline-terminated; captured by the host into the console |
| `type(v)` | one of `"nil" "boolean" "number" "string" "table" "function"` |
| `tostring(v)` | numbers via `%.14g`; tables/functions get a short tag; respects `__tostring` |
| `tonumber(v)` | number on success, `nil` otherwise (strings use strtod prefix) |
| `input([prompt])` | prints prompt, then **blocks** for one input line (same queue as `io.read`) |
| `assert(v [, msg])` | errors when `v` is falsy; msg must be a string |
| `error(msg)` | raises a runtime error prefixed with `line N:` |
| `pcall(f, ...)` | catches runtime errors, returns `ok, ...` |
| `select(n, ...)` | `n>0` returns from the n-th arg; `n=-1` returns the last; `"#"` returns the count; n beyond argc returns nothing, out-of-range negative reports `index out of range` |
| `pairs(t)` / `ipairs(t)` | iteration; `pairs` order undefined, `ipairs` stops at first nil |
| `next(t [, k])` | the iteration primitive behind `pairs` |
| `setmetatable(t, mt)` / `getmetatable(t)` | metatables; only the 4 metamethods work (§6) |
| `rawget(t, k)` / `rawset(t, k, v)` / `rawequal(a, b)` | primitives that bypass metamethods |
| `require(mod)` | `"ui"` is built in; `"a.b"` → `<modroot>/a/b.lua` or `a/b/init.lua`; cached in `package.loaded`; names containing `..` path segments are rejected (cannot escape the module root) |

```lua
print(type(1), type("x"), tostring(1.5), tonumber("12") + 1)  -- number string 1.5 13
print(select("#", 1, 2, 3), select(-1, "a", "b"))              -- 3 b
print(pcall(function() error("boom") end))                     -- false line 1: boom
```

### 3.2 string

| Function | Behavior |
|---|---|
| `len(s)` / `upper(s)` / `lower(s)` / `reverse(s)` | usual; `len` is byte count |
| `sub(s, i [, j])` | 1-based inclusive range; negatives count from the tail; `j` defaults to -1 |
| `rep(s, n)` | repeat n times |
| `format(fmt, ...)` | `%d %i %o %x %X %c %e %E %f %g %G %s %q %%` + flags (`- + # 0`) / width / precision |
| `byte(s [, i [, j]])` | byte values of `s[i..j]`; defaults `i=j=1` |
| `char(...)` | string from byte values; each must be 0–255 |
| `find(s, pat [, init [, plain]])` | returns `start, end[, captures...]` or `nil`; `plain=true` for literal search |
| `match(s, pat [, init])` | captures if present (possibly several), else the whole match; `nil` on no match |
| `gsub(s, pat, repl [, n])` | repl may be a string (`%0`–`%9` backrefs, `%%` literal) / table (lookup by key) / function (receives captures); returns `new string, count`; `nil/false` keeps the original text |
| `gmatch(s, pat)` | iterator: each step returns the next match's captures (whole match if none) |

**Patterns**: `.` any byte; `%a %c %d %g %l %p %s %u %w %x %z` (uppercase negates); `[set]` / `[^set]`; quantifiers `* + - ?`; anchors `^ $`; captures `(...)`, position captures `()`; `%bxy` balanced match; `%f[set]` frontier.

```lua
print(string.format("%d %s %5.2f %x", 42, "hi", 3.14159, 255))   -- 42 hi  3.14 ff
print(("key=value"):match("(%w+)=(%w+)"))                        -- key=value
print(("a1 b2"):gsub("%a(%d)", "[%1]"))                          -- [a1] [b2]  2
for w in ("one two three"):gmatch("%a+") do io.write(w, ".") end -- one.two.three.
print(("file.txt"):gsub("%.txt$", ".lua"))                       -- file.lua 1
print(("Hello"):lower(), ("abc"):rep(2), ("x"):len())            -- hello abcabc 1
```

> Method sugar: `s:upper()` equals `string.upper(s)` (`("x"):len() == 1`).

### 3.3 table

| Function | Behavior |
|---|---|
| `insert(t, v)` / `insert(t, pos, v)` | append / positional insert (pos is 1-based) |
| `remove(t [, pos])` | remove and return; default removes the tail; out-of-range returns `nil` |
| `concat(t [, sep [, i [, j]]])` | concatenate number/string elements |
| `unpack(t [, i [, j]])` | expand (capped at 64 results) |
| `sort(t [, comp])` | **stable** sort; default: number-vs-number numerically, string-vs-string bytewise, mixed types error |

```lua
local t = {5, 2, 8, 1}
table.sort(t)                    print(table.concat(t, ","))  -- 1,2,5,8
table.sort(t, function(a,b) return a > b end)
print(table.concat(t, ","), #t)                                -- 8,5,2,1 4
table.insert(t, 9)              print(t[#t])                  -- 9
```

### 3.4 math

| Function | Behavior |
|---|---|
| `floor(x)` / `ceil(x)` / `abs(x)` / `sqrt(x)` | usual; `sqrt` of a negative reports math domain error |
| `max(...)` / `min(...)` | variadic |
| `random()` | float in `[0, 1)` |
| `random(m)` / `random(m, n)` | integer in `1..m` / `m..n` (closed interval) |
| `randomseed(x)` | reseed; lazily seeded from time by default (per-State splitmix64) |

```lua
print(math.floor(3.7), math.ceil(3.2), math.abs(-5), math.sqrt(16))  -- 3 4 5 4
print(math.max(1, 9, 3), math.min(4, 2, 8))                            -- 9 2
math.randomseed(42)
print(math.random(1, 6) == math.random(1, 6) and "same" or "diff")    -- same(同种子确定)
```

### 3.5 io / os

| Function | Behavior |
|---|---|
| `io.read()` | **blocks** until the host delivers an input line (cancel interrupts it, reporting `cancelled by user`) |
| `io.write(...)` | output without a newline |
| `io.flush()` | flush |
| `os.getenv(name)` | environment variable or `nil` |
| `os.time()` | Unix seconds |
| `os.clock()` | CPU seconds |

```lua no-run
io.write("your name: ")
local name = io.read()          -- 在 IDE 控制台回复;桌面 CLI 未接 OS stdin(已知限制)
print("hi,", name)
```

## 4. Runtime semantics

### 4.1 Execution guards

- **Step limit**: the host defaults to 50,000,000 steps; exceeding it reports `execution step limit exceeded (possible infinite loop)`
- **Cooperative cancel**: the host may cancel a running script at any time; reports `cancelled by user`. A new `run` clears a stale cancel flag; `invoke` does not (§4.2)
- **Call-depth limit**: 160 nested calls; exceeding reports `stack overflow` (catchable by `pcall`; the engine stays usable)
- **Argument limit**: at most 64 actual arguments per call (expanded multi-values count too); exceeding reports `too many arguments`
- **UI-tree depth limit**: serialization nests at most 128 deep; a cyclic table reports `ui tree too deep (possible cycle)`
- **`__index`/`__newindex` chain limit**: 1024 hops; a self-loop reports `'__index'/'__newindex' chain too long`
- Errors uniformly read `line N: message`; `pcall` catches them

### 4.2 Events & re-render (the core contract)

A function prop serializes as `{"__handler": id}`; the host calls it via `invoke(id, payload)` where **payload is a single string** (`nil` for parameterless events). After every invoke, in order:

1. **handler returned a ui tree** → the new tree replaces the current view;
2. otherwise **`app_view` is re-serialized**; if the script returned a **view function**, it is re-called first.

Three correct ways to update the UI:

```lua
-- ✅ 路径 A(推荐):脚本返回 view 函数,每次事件后引擎重调,自动读到新状态
local count = 0
local function view()
  return ui.app { ui.text { text = "n=" .. count },
                  ui.button { text = "+1", onClick = function() count = count + 1 end } }
end
return view

-- ✅ 路径 B:handler 返回新树(适合整屏替换)
-- onClick = function() count = count + 1; return view() end

-- ✅ 路径 C:定时驱动 —— 树上挂 onTick(handler) + interval(毫秒),
--          宿主按 interval 调 onTick,每次走上面的判定(games/snake.lua 为范例)

-- ❌ 反例:返回静态表 + handler 里改局部变量 —— 树已序列化完毕,
--    改的变量无人再读,屏幕永远不变。
```

Event payload semantics: `input.onSubmit(text)` receives the field text; `switch.onToggle(v)` receives `"true"`/`"false"`; other events take no argument. `tonumber` it yourself when you need a number.

### 4.3 onTick / interval

Two props on a node — `onTick = function() ... end` and `interval = 260` (milliseconds) — form the timer contract: the host repeatedly `invoke`s onTick every interval. `interval` is re-read on every tree rebuild, so a handler may adjust the pace.

## 5. UI DSL

`ui.<type>{ props..., children... }` is literally **an ordinary Lua table tagged `__ui = "<type>"`**. String keys are props; the array part (1..#t) is children; function props register as handlers. The 16 constructors:

| Constructor | Props (type / default) | Notes |
|---|---|---|
| `app` | `title` (str, "") | root node; non-empty title renders a header |
| `column` / `row` | `spacing` (num, 8dp) | vertical / horizontal container (row children center vertically) |
| `text` | `text` (str, ""), `size` (num, 16sp), `color` (#hex), `font` (asset path), `animate` (bool, true) | `animate=false` takes the no-animation path (boards/clocks) |
| `button` | `text` (str), `onClick` (handler) | children are ignored |
| `card` | `spacing` (8dp), `radius` (16dp), `padding` (16dp) | card container |
| `input` | `value` (str), `label` (str), `onSubmit` (handler) | **onSubmit receives the field text** (keyboard confirm) |
| `image` | `src` (str), `size` (num, 96dp) | resolved via AssetResolver; failures show "missing" |
| `spacer` | `size` (num, 8dp) | height only |
| `divider` | — | horizontal rule |
| `scrollview` | `spacing` (8dp) | vertical scrolling |
| `list` / `listitem` | `spacing` / `title`, `subtitle`, `onClick` | listitem rows are clickable |
| `stack` / `page` | `selected` (str) / `key` | stack selects the page by key; page only valid as a stack child |
| `switch` | `label`, `checked` (bool), `onToggle` (handler) | **onToggle receives "true"/"false"** |

Common: `key`/`id` is the diff identity (drives animation). Serialization is `{type, props, children}` JSON; prop key order is hash-slot order (test assertions must use substring matching). The full render contract (the authoritative prop table shared by both modules) evolves with [PLATFORM_ABI.md](PLATFORM_ABI.md) §6.

## 6. Metatables

| Metamethod | Triggered by |
|---|---|
| `__index` | reading a missing field (table → prototype chain, function → call) |
| `__newindex` | writing a missing field (table → forward, function → call) |
| `__len` | `#t` |
| `__tostring` | `tostring(t)` / print |

```lua
local proto = { greet = function(self) return "hi " .. self.name end }
local obj = setmetatable({ name = "luax" }, { __index = proto })
print(obj:greet())                               -- hi luax
print(setmetatable({}, { __tostring = function() return "BOX" end }))
-- BOX
```

---
*Language evolution follows AGENTS.md: changing `engine/lx.c` requires updating this file in the same change, and every example here must stay executable.*
