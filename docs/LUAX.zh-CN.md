# LuaX 语言参考

[English](LUAX.md)

> 本文档是 LuaX 语言的**唯一权威定义**。实现见 `engine/lx.c`;文档与实现不一致时,以实现为准并立即修文档(AGENTS.md 规则 8)。文中全部示例可执行 —— `make -C engine test` 会逐块运行本文的代码块,失效即 CI 红。

## 1. 定位

LuaX 是 Lua 5.1 语义的**方言子集**,为「在 Android 上写并打包小 App」而生:

- 单文件 C 引擎(约 2200 行),树遍历 + 字节码 VM 混合执行,数值循环约 11× 加速
- 声明式 UI:UI 树就是普通 Lua 表;事件驱动重渲染(§4)
- 无 root:沙箱执行;一键打包为独立签名 APK
- 是 LuaXIDE 三语言平台(Lua / JavaScript / Python)的**参考语言** —— 跨语言契约见 [PLATFORM_ABI.md](PLATFORM_ABI.zh-CN.md)

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

## 2. 与 Lua 5.1 的差异

**语义差异**(行为不同):

| 方面 | LuaX | Lua 5.1 |
|---|---|---|
| 数字 | 仅 double;转字符串 `%.14g` | 有整数语义区分 |
| 表构造器 | **每个位置字段都展开多值**:`{f(), g()}` 收进全部返回值 | 仅最后字段展开 |
| 字符串索引 | `("x").upper` 存在 = string 库;未知方法为 `nil` | 报错(需 metatable) |
| `<` `<=` 等比较 | 走数值转换(字符串参与时按 strtod) | 数字/字符串各自比较 |
| 作用域 | 词法闭包(标准);字节码 VM 对遮蔽有复验 | 词法闭包 |

**不支持**(写了即错):

- `goto` / label
- `load` / `loadstring` / `dofile`(动态代码)
- 元方法仅 `__index` `__newindex` `__len` `__tostring`(无 `__call`、`__eq`、算术元方法)
- 协程、UTF-8 库、`string.dump`、`os.date`、`require` 之外的模块系统扩展

**模式匹配**:字节语义(与 Lua 5.1 一致),详见 §3.2。

## 3. 标准库参考

### 3.1 基础

| 函数 | 行为 |
|---|---|
| `print(...)` | 制表符分隔输出,换行结尾;输出被宿主捕获进控制台 |
| `type(v)` | 返回 `"nil" "boolean" "number" "string" "table" "function"` 之一 |
| `tostring(v)` | 数字按 `%.14g`;表/函数给短标签;尊重 `__tostring` |
| `tonumber(v)` | 成功返回数字,否则 `nil`(字符串走 strtod 前缀) |
| `input([prompt])` | 打印 prompt 后**阻塞**等一行输入(与 `io.read` 同队列) |
| `assert(v [, msg])` | `v` 为假则报错;msg 必须是字符串 |
| `error(msg)` | 抛出运行时错误,信息带 `line N:` 前缀 |
| `pcall(f, ...)` | 捕获运行时错误,返回 `ok, ...` |
| `select(n, ...)` | `n>0` 返回第 n 个起;`n=-1` 返回最后一个;`"#"` 返回个数;n 超过参数个数返回空,负数越界报 `index out of range` |
| `pairs(t)` / `ipairs(t)` | 遍历;`pairs` 顺序未定义,`ipairs` 止于首个 nil |
| `next(t [, k])` | `pairs` 的迭代原语 |
| `setmetatable(t, mt)` / `getmetatable(t)` | 元表;仅 4 个元方法有效(§6) |
| `rawget(t, k)` / `rawset(t, k, v)` / `rawequal(a, b)` | 绕过元方法的原语 |
| `require(mod)` | `"ui"` 内置;`"a.b"` → `<modroot>/a/b.lua` 或 `a/b/init.lua`;缓存于 `package.loaded`;模块名含 `..` 路径段一律拒绝(不允许逃出模块根) |

```lua
print(type(1), type("x"), tostring(1.5), tonumber("12") + 1)  -- number string 1.5 13
print(select("#", 1, 2, 3), select(-1, "a", "b"))              -- 3 b
print(pcall(function() error("boom") end))                     -- false line 1: boom
```

### 3.2 string

| 函数 | 行为 |
|---|---|
| `len(s)` / `upper(s)` / `lower(s)` / `reverse(s)` | 常规;`len` 为字节数 |
| `sub(s, i [, j])` | 1-based 起止;负数从尾部数;`j` 缺省 -1 |
| `rep(s, n)` | 重复 n 次 |
| `format(fmt, ...)` | `%d %i %o %x %X %c %e %E %f %g %G %s %q %%` + flags(`- + # 0`)/宽度/精度 |
| `byte(s [, i [, j]])` | 返回 `s[i..j]` 的字节值;缺省 `i=j=1` |
| `char(...)` | 字节值序列拼字符串;每个须 0–255 |
| `find(s, pat [, init [, plain]])` | 返回 `start, end[, 捕获...]`,未中返回 `nil`;`plain=true` 字面查找 |
| `match(s, pat [, init])` | 有捕获返回捕获(多个),否则返回整个匹配;未中返回 `nil` |
| `gsub(s, pat, repl [, n])` | repl 为字符串(`%0`–`%9` 回引,`%%` 字面)/表(按键查)/函数(收捕获);返回 `新串, 次数`;`nil/false` 保留原文 |
| `gmatch(s, pat)` | 迭代器:每次返回下一个匹配的捕获(无捕获时为整个匹配) |

**模式**:`.` 任意字节;`%a %c %d %g %l %p %s %u %w %x %z`(大写取反);`[集合]` / `[^集合]`;量词 `* + - ?`;锚 `^ $`;捕获 `(...)`,位置捕获 `()`;`%bxy` 平衡匹配;`%f[set]` 前界。

```lua
print(string.format("%d %s %5.2f %x", 42, "hi", 3.14159, 255))   -- 42 hi  3.14 ff
print(("key=value"):match("(%w+)=(%w+)"))                        -- key=value
print(("a1 b2"):gsub("%a(%d)", "[%1]"))                          -- [a1] [b2]  2
for w in ("one two three"):gmatch("%a+") do io.write(w, ".") end -- one.two.three.
print(("file.txt"):gsub("%.txt$", ".lua"))                       -- file.lua 1
print(("Hello"):lower(), ("abc"):rep(2), ("x"):len())            -- hello abcabc 1
```

> 方法糖:`s:upper()` 等价 `string.upper(s)`(`("x"):len() == 1`)。

### 3.3 table

| 函数 | 行为 |
|---|---|
| `insert(t, v)` / `insert(t, pos, v)` | 尾插 / 定位插(pos 起 1-based) |
| `remove(t [, pos])` | 删并返回;缺省删尾;越界返回 `nil` |
| `concat(t [, sep [, i [, j]]])` | 拼接数字/字符串元素 |
| `unpack(t [, i [, j]])` | 展开(上限 64 个) |
| `sort(t [, comp])` | **稳定**排序;缺省:两数比数值、两串比字节序、混型报错 |

```lua
local t = {5, 2, 8, 1}
table.sort(t)                    print(table.concat(t, ","))  -- 1,2,5,8
table.sort(t, function(a,b) return a > b end)
print(table.concat(t, ","), #t)                                -- 8,5,2,1 4
table.insert(t, 9)              print(t[#t])                  -- 9
```

### 3.4 math

| 函数 | 行为 |
|---|---|
| `floor(x)` / `ceil(x)` / `abs(x)` / `sqrt(x)` | 常规;`sqrt` 负数报 math domain error |
| `max(...)` / `min(...)` | 变参 |
| `random()` | 返回 `[0, 1)` 浮点 |
| `random(m)` / `random(m, n)` | 整数 `1..m` / `m..n`(闭区间) |
| `randomseed(x)` | 重设种子;默认按时间懒初始化(per-State splitmix64) |

```lua
print(math.floor(3.7), math.ceil(3.2), math.abs(-5), math.sqrt(16))  -- 3 4 5 4
print(math.max(1, 9, 3), math.min(4, 2, 8))                            -- 9 2
math.randomseed(42)
print(math.random(1, 6) == math.random(1, 6) and "same" or "diff")    -- same(同种子确定)
```

### 3.5 io / os

| 函数 | 行为 |
|---|---|
| `io.read()` | **阻塞**直到宿主输入行送入(取消可中断,报 `cancelled by user`) |
| `io.write(...)` | 无换行输出 |
| `io.flush()` | 刷新 |
| `os.getenv(name)` | 环境变量或 `nil` |
| `os.time()` | Unix 秒 |
| `os.clock()` | CPU 秒 |

```lua no-run
io.write("your name: ")
local name = io.read()          -- 在 IDE 控制台回复;桌面 CLI 未接 OS stdin(已知限制)
print("hi,", name)
```

## 4. 运行时语义

### 4.1 执行防护

- **步数上限**:宿主默认 50,000,000 步;超限报 `execution step limit exceeded (possible infinite loop)`
- **协作式取消**:宿主可随时取消正在运行的脚本;报 `cancelled by user`。新一次 `run` 清除残留取消标志;`invoke` 不清除(§4.2)
- **调用深度上限**:160 层嵌套调用;超限报 `stack overflow`(`pcall` 可捕获,引擎保持可用)
- **参数上限**:单次调用至多 64 个实参(展开多值时同样计);超限报 `too many arguments`
- **UI 树深度上限**:序列化至多 128 层嵌套;环形表报 `ui tree too deep (possible cycle)`
- **`__index`/`__newindex` 链上限**:1024 跳;自环报 `'__index'/'__newindex' chain too long`
- 错误格式统一为 `line N: message`;`pcall` 可捕获

### 4.2 事件与重渲染(核心契约)

函数 prop 序列化为 `{"__handler": id}`;宿主以 `invoke(id, payload)` 调用,**payload 为单个字符串**(无参事件为 `nil`)。每次调用后按序判定:

1. **handler 返回 ui 树** → 新树替换当前视图;
2. 否则**重新序列化 `app_view`**;若脚本返回的是 **view 函数**则先重新调用它。

由此三条让界面更新的正确路径:

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

事件 payload 语义:`input.onSubmit(text)` 收到输入框文本;`switch.onToggle(v)` 收到 `"true"`/`"false"`;其余事件无参。需要数字自行 `tonumber`。

### 4.3 onTick / interval

树上 `onTick = function() ... end` + `interval = 260`(毫秒)两个 prop 构成定时驱动契约:宿主按 interval 反复 `invoke(onTick)`。`interval` 每次重建时重读,可在 handler 里调速。

## 5. UI DSL

`ui.<type>{ props..., children... }` 的本质:**一个普通 Lua 表,打上 `__ui = "<type>"` 标签**。字符串键是 props;数组部分(1..#t)是 children;函数 prop 注册为 handler。16 个构造器:

| 构造器 | 属性(类型 / 默认) | 说明 |
|---|---|---|
| `app` | `title`(str, "") | 根节点;非空时渲染标题头 |
| `column` / `row` | `spacing`(num, 8dp) | 纵向 / 横向容器(row 子项垂直居中) |
| `text` | `text`(str, "")、`size`(num, 16sp)、`color`(#hex)、`font`(资产路径)、`animate`(bool, true) | `animate=false` 走无动画路径(棋盘/时钟) |
| `button` | `text`(str)、`onClick`(handler) | 子节点被忽略 |
| `card` | `spacing`(8dp)、`radius`(16dp)、`padding`(16dp) | 卡片容器 |
| `input` | `value`(str)、`label`(str)、`onSubmit`(handler) | **onSubmit 收到输入框文本**(键盘确认触发) |
| `image` | `src`(str)、`size`(num, 96dp) | 经 AssetResolver;失败显示 missing |
| `spacer` | `size`(num, 8dp) | 仅高度 |
| `divider` | — | 水平分隔线 |
| `scrollview` | `spacing`(8dp) | 纵向滚动 |
| `list` / `listitem` | `spacing` / `title`、`subtitle`、`onClick` | listitem 整行可点 |
| `stack` / `page` | `selected`(str)/ `key` | 按 key 选页;page 仅作 stack 子节点 |
| `switch` | `label`、`checked`(bool)、`onToggle`(handler) | **onToggle 收到 "true"/"false"** |

通用:`key`/`id` 作为 diff 身份(影响动画)。序列化为 `{type, props, children}` JSON;props 键序为哈希表槽序(测试断言须用子串匹配)。完整渲染契约(两模块一致的权威属性表)随 [PLATFORM_ABI.md](PLATFORM_ABI.zh-CN.md) §6 演进。

## 6. 元表

| 元方法 | 触发 |
|---|---|
| `__index` | 表字段缺省时(表→原型链,函数→调用) |
| `__newindex` | 写入缺省字段时(表→转发,函数→调用) |
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
*语言演进遵循 AGENTS.md:改 `engine/lx.c` 必须同次更新本文,且本文全部示例必须保持可执行。*
