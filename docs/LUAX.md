# LuaX Language Reference

[简体中文](LUAX.zh-CN.md)

> This document is the **single authoritative definition** of the LuaX language. The implementation is `engine/lx.c`; when doc and implementation disagree, the implementation wins and the doc gets fixed immediately (AGENTS.md rule 8). Every example in this file is executable — `make -C engine test` runs every code block; a stale example turns CI red.

## 1. What it is

LuaX is a **dialect subset** of Lua 5.1 semantics, built for writing and packaging small apps on Android:

- Single-file C engine (~2200 lines), tree-walking + bytecode VM hybrid (function bodies **and** the top-level chunk), ~11–22× on numeric loops
- HTML UI: the page is real HTML and CSS, drawn by the host WebView; Lua registers events and queues DOM updates (§4, §5)
- No root: sandboxed execution; one-tap packaging into a standalone signed APK
- The **reference language** of LuaXIDE's three-language platform (Lua / JavaScript / Python) — cross-language contract: [PLATFORM_ABI.md](PLATFORM_ABI.md)

```lua
-- A complete interactive app. The page is index.html; this file is the logic.
local html = require("html")
local count = 0

html.on("plus", "click", function()
  count = count + 1
  html.setText("count", "taps: " .. count)
end)

html.setText("count", "taps: 0")
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

**Performance note**: `..` is internally lazy — `s = s .. x` accumulation loops are O(1) per step, not quadratic; the bytes materialize on the first real read (`#s`, printing, table keys, `string.*`). `table.concat` remains the idiomatic choice for joining many parts with a separator.

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
- **Cooperative cancel**: the host may cancel a running script at any time; reports `cancelled by user`. A new `run` clears a stale cancel flag; an HTML event does not (§4.2)
- **Call-depth limit**: 160 nested calls; exceeding reports `stack overflow` (catchable by `pcall`; the engine stays usable)
- **Argument limit**: at most 64 actual arguments per call (expanded multi-values count too); exceeding reports `too many arguments`
- **`__index`/`__newindex` chain limit**: 1024 hops; a self-loop reports `'__index'/'__newindex' chain too long`
- Errors uniformly read `line N: message`; `pcall` catches them

### 4.2 Events (the core contract)

The page is an HTML document. Lua does not return a view. `html.on(id, event, fn)` registers a handler for an element id; registering the same pair again replaces the previous function. The host delivers events with a single string payload:

| Event | When | Payload |
|---|---|---|
| `click` | a click on an element with an `id` (the nearest ancestor id wins) | `""` |
| `input` | the control's value changes while typing | the current value |
| `change` | the control commits a value; a checkbox sends `"true"` or `"false"` | the current value |
| `submit` | a form is submitted; the host cancels the navigation | `""` |

An id with no handler is ignored. If the handler errors, DOM operations queued during that call are discarded and the page stays as it was. `run` clears a stale cancel flag; an event dispatch does not.

```lua
local html = require("html")
local count = 0
local name = ""

html.on("plus", "click", function()
  count = count + 1
  html.setText("count", "taps: " .. count)
end)

html.on("name", "input", function(text)
  name = text
  html.setText("echo", name)
end)
```

There is no engine timer. A host that wants animation calls the same event dispatch on its own clock.

### 4.3 Where the page lives

The host loads `index.html` from the project, or `<name>.html` beside the script when that file exists (`ui/counter.lua` looks for `ui/counter.html` first). Relative CSS and image URLs resolve against the project directory. `<script>` elements in the page are removed; behavior stays in Lua. The page cannot navigate off the project or load the network.

`require("ui")` is an error: `module 'ui' has been removed; write index.html and use require('html')`.

## 5. HTML host

`local html = require("html")` (or the global `html`) is the whole UI API. Each call appends one DOM operation. The host applies the batch after the script or the event handler returns. A missing element is skipped by the page.

| Function | Operation |
|---|---|
| `html.on(id, event, fn)` | register `fn(payload)`; payload is always a string |
| `html.setText(id, text)` | set `textContent` |
| `html.setHtml(id, html)` | set `innerHTML` |
| `html.setAttr(id, name, value)` | `setAttribute` |
| `html.setValue(id, value)` | set an input's `value` |
| `html.addClass(id, class)` | `classList.add` |
| `html.removeClass(id, class)` | `classList.remove` |

A wrong argument type or a missing argument reports `bad argument #N to 'html.…'`.

```html
<label>Name <input id="name"></label>
<input id="amount" type="range" min="0" max="1" step="0.1" value="0.4">
<label><input id="enabled" type="checkbox"> Enabled</label>
<p id="summary"></p>
```

```lua
local html = require("html")
local amount = 0.4
local name = ""
local enabled = false

local function summary()
  html.setText("summary", name .. " / " .. amount .. " / " .. tostring(enabled))
end

html.on("name", "input", function(text)
  name = text
  summary()
end)
html.on("amount", "change", function(value)
  amount = tonumber(value) or 0
  summary()
end)
html.on("enabled", "change", function(value)
  enabled = value == "true"
  summary()
end)
summary()
```

CSS is ordinary CSS (`<link>`, `<style>`, or a `style` attribute). Layout, color, and fonts are the WebView's. JavaScript and Python projects still render the JSON UI tree; this section is LuaX only. See [PLATFORM_ABI.md](PLATFORM_ABI.md).


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
