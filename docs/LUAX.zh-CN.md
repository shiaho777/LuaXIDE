# LuaX 语言参考

[English](LUAX.md)

> 本文档是 LuaX 语言的**唯一权威定义**。实现见 `engine/lx.c`;文档与实现不一致时,以实现为准并立即修文档(AGENTS.md 规则 8)。文中全部示例可执行 —— `make -C engine test` 会逐块运行本文的代码块,失效即 CI 红。

## 1. 定位

LuaX 是 Lua 5.1 语义的**方言子集**,为「在 Android 上写并打包小 App」而生:

- 单文件 C 引擎(约 2200 行),树遍历 + 字节码 VM 混合执行(函数体**与**顶层 chunk 均编译),数值循环约 11–22× 加速
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

**性能说明**:`..` 内部是惰性的 —— `s = s .. x` 累积循环每步 O(1),不再是平方级;字节在首次真正读取(`#s`、打印、表键、`string.*`)时才物化。带分隔符拼接多段仍推荐 `table.concat`。

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

事件 payload 语义:`input.onChange(text)` 每次输入都收到字段文本字符串,`input.onSubmit(text)` 在键盘确认时收到字段文本;`slider.onChange(v)` 收到数值的字符串形式;`switch.onToggle(v)` 收到 `"true"`/`"false"`。点击与定时事件无参。slider 值需 `tonumber`,开关用 `v == "true"`。

### 4.3 onTick / interval

树上 `onTick = function() ... end` + `interval = 260`(毫秒)两个 prop 构成定时驱动契约:宿主按 interval 反复 `invoke(onTick)`。`interval` 每次重建时重读,可在 handler 里调速。

## 5. UI DSL

`ui.<type> { props..., children... }` 给普通 Lua 表打上 `__ui = "<type>"` 标签。字符串键是属性,数组项是子节点,函数属性注册为 handler。**只有 text 支持字符串构造糖**:`ui.text('hi')` 等同于 `ui.text { text = "hi" }`。`ui.column { "hi" }` 这样的字符串子项序列化为 text 节点,并不会变成按钮标签;其他构造器仍接收属性表。

### 组件(19 个)

以下为属性缺省值。尺寸单位为 dp,文本字号为 sp。

| 构造器 | 规范属性与默认值 | 行为 |
|---|---|---|
| `app` | `title = ""` | 根节点;标题非空时显示标题栏 |
| `column` / `row` | `spacing = 8` | 纵向 / 横向布局;row 子项默认垂直居中 |
| `box` | 下述通用样式 | 子项叠放,默认左上(start) |
| `text` | `text = ""`, `size = 16`, `color`, `font`, `bold = false`, `animate = true` | font 为资产字体路径;`animate = false` 关闭文本切换动画 |
| `button` | `text = "button"`, `onClick` | 点击无参;忽略子节点 |
| `card` | `spacing = 8`, `radius = 16`, `padding = 16` | 卡片内纵向排列 |
| `input` | `value = ""`, `label = "input"`, `onChange`, `onSubmit` | 单行输入;每次编辑 / 键盘确认均传字段文本字符串 |
| `image` | `src = ""`, `size = 96` | 资产图片,裁剪适配;路径解析失败显示 `missing` |
| `spacer` | `size = 8` | 默认高度 |
| `divider` | `color` | 水平分隔线 |
| `scrollview` | `spacing = 8` | 有限视口内纵向滚动 |
| `list` | `spacing = 8` | 普通 Column,非懒加载/回收列表,也不是独立滚动容器 |
| `listitem` | `title = ""`, `subtitle = ""`, `onClick` | 可选点击行及子项;点击无参 |
| `stack` / `page` | `selected = ""` / `key`, `spacing = 8` | **仅按 page.key** 选页,否则取首个 page;无 page 时显示全部子项 |
| `switch` | `label = "switch"`, `checked = false`, `onToggle` | 传 `"true"` / `"false"` 字符串,不是布尔值 |
| `slider` | `from = 0`, `to = 1`, `value = from`, `step = 0`, `onChange` | 传数值的**字符串**形式,用 `tonumber` 转换 |
| `progress` | `value`, `color` | 线性进度条:值截断到 0..1;**不传 value 为不定态** |

slider 的值和范围必须有限,`to > from`,step 为有限非负数(范围还必须能由宿主 float 滑杆表示)。`step = 0` 为连续滑动;正 step 是**从 from 起算的数值增量**,不是刻度数量。值会截断到范围并吸附至最近增量;即使范围不能整除 step,仍能到达 `to`。非法参数显示诊断信息。

### 通用样式与子项布局

- `width`、`height`、`padding`、`radius`:有限非负的 **dp 数值**,受父容器约束;非法值忽略。不支持 `"100%"` / `"fill"` 尺寸语法。radius 裁剪圆角;padding 默认 0,card 内边距默认 16dp(只应用一次)。
- `background`:`#RRGGBB` / `#AARRGGBB`;card/button/input/listitem/image 通过各自 surface 上色。text/divider/progress 的 `color` 格式相同。
- `weight`:**Row/Column 作用域**中子项的有限正权重,分配水平/垂直剩余空间。主轴必须有界;无界滚动内容中的 weight 被忽略。Box 不使用 weight。
- `align` 是**子项属性**,不是容器整体对齐方式。Row 作用域:`top`、`center`、`bottom`。Column 作用域:`start`(也接受 `left`)、`center`、`end`。Box 作用域:`topleft`(默认)、`top`、`topright`、`left`、`center`、`right`、`bottomleft`、`bottom`、`bottomright`。其他以 Column 渲染子项的容器沿用该作用域规则;不适用的值被忽略。
- 节点身份按 `key`、`id`、结构位置的优先级确定。同类型节点使用稳定 key 时,重排会保留状态;更换组件类型时重新创建状态。动态列表应显式提供稳定 key,不会根据文本或内容自动生成 key。同级重复的显式 key/id 会显示 `duplicate child key '<value>' at <parent path>`,而不渲染该冲突子列表;不同父节点下可重复使用 key。这**不代表**可以用 `page.id` 选页:`stack.selected` 必须对应 `page.key`。
- Row 子项的水平权重不影响宿主纵向滚动。未约束的垂直权重与 scrollview 需要有限宿主视口;显式容器高度会截断该需求向上传播。仅 stack 当前选中的页面参与判断。

渲染器回归测试:`./gradlew :app:testDebugUnitTest` 无需设备即可检查节点身份和视口规则;`./gradlew :app:connectedDebugAndroidTest` 在可用的专用设备上检查 Compose 布局、带 key 输入框和嵌套滚动。仅编译测试 APK 不等于验证布局。

嵌套 `scrollview` 必须有**有限高度**,来自自身或真正约束它的父容器。高度无界时会显示 `scrollview needs a bounded height. Set height on this nested scrollview or its parent.`,而不是滚动。不保证任意嵌套都可用。`list` 仍是普通 Column;需要独立滚动时请用有界 scrollview。

### 破坏性迁移:仅使用规范名

属性/事件别名回退链已移除,**没有兼容层**。请显式改写,不要依赖回退:

| 旧拼写 / 用法 | 规范替代 |
|---|---|
| text/button 用 `label` / `value` / `content` 作文字 | `text` |
| `fontSize`, `fontPath` | text 的 `size`, `font` |
| `onTap` / `onPress` | button/listitem 的 `onClick` |
| input 的 `text`, `placeholder`, `onEnter` | `value`, `label`, `onSubmit` |
| switch 的 `value`, `text`, `onChange` / `onCheckedChange` | `checked`, `label`, **`onToggle`** |
| image 的 `source` / `path`, 容器的 `gap`, card 的 `cornerRadius` / `pad` | `src`, `spacing`, `radius` / `padding` |
| listitem 的 `text` / `description` | `title` / `subtitle` |
| stack 的 `value` / `active`, 用于选页的 page `id` | `selected`, page **`key`** |
| slider 的 `min` / `max` / `steps` / `onValueChanged` | `from` / `to` / `step`(增量,不是数量) / `onChange` |

`input.onChange` 与 `slider.onChange` 是规范名称,`switch.onChange` 不是。其余名称及默认值以组件表为准。

### 状态更新示例

返回 view 函数,让每次事件根据更新后的状态重建树(§4.2):

```lua
local ui = require("ui")
local amount = 0.4
local name = ""
local enabled = false
local function view()
  return ui.app {
    title = "Controls",
    ui.column {
      spacing = 12,
      ui.text('hi'),
      "String children become text nodes",
      ui.row { width = 280,
        ui.text { text = "A", weight = 1 },
        ui.text { text = "B", weight = 2, align = "bottom" },
      },
      ui.box { width = 280, height = 64, background = "#202020", radius = 8,
        ui.text { text = "Overlay", bold = true, color = "#FFFFFF",
                  padding = 8, align = "bottomright" },
      },
      ui.input { label = "Name", value = name,
        onChange = function(text) name = text end,
        onSubmit = function(text) print(text) end,
      },
      ui.slider { value = amount, from = 0, to = 1, step = 0.1,
        onChange = function(value) amount = tonumber(value) end,
      },
      ui.progress { value = amount },
      ui.progress {}, -- absent value = indeterminate
      ui.switch { label = "Enabled", checked = enabled,
        onToggle = function(value) enabled = value == "true" end,
      },
      ui.text { text = name .. " / " .. amount },
    },
  }
end
return view -- return the function, not view(): events rebuild from state
```

序列化为 `{type, props, children}` JSON;属性顺序不保证,测试断言不可依赖键序。跨语言契约见 [PLATFORM_ABI.md](PLATFORM_ABI.zh-CN.md) §6。

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
