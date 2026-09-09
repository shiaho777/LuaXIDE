# LuaX 引擎规范(ENGINE.md)

> 这是自研引擎的**唯一权威规范**:语言方言、标准库、UI DSL、事件与重渲染契约、测试与扩展流程。本文面向"用 Lua 写 LuaXIDE 程序的人"(人类或 Agent)。**改 `engine/lx.c`、`luax_jni.c`、组件渲染契约或标准库时,必须在同一次改动里更新本文件**(AGENTS.md 硬规则)。发现文档与代码不一致时,以代码为准并立刻修文档。

## 1. 总览

- **单一源码,三个构建目标**:`engine/lx.c`(单文件,约 两千余行)编译为桌面 CLI(`make`)、`:app` 的 `libluax.so`、`:runtime` 的 `libluax.so`。两个 Android 模块各持有一份字节级一致的 `luax_jni.c`。
- **混合执行**:树遍历解释器 + 字节码 VM(函数体首次调用时尝试编译,编译不了透明回退,VM 只加速不改语义,详见 [BYTECODE_VM.md](BYTECODE_VM.md))。
- **内存**:arena 分配,无 GC,`lx_close` 时整体释放。脚本内存单调增长 —— 别写泄漏型长循环脚本。
- **数值**:只有 double,无整数类型;数字转字符串用 `%.14g`。
- **作用域**:词法闭包(函数捕获定义处 Env),行为对齐标准 Lua。字节码 VM 对"编译后被外层新声明同名局部遮蔽的名字"有每次调用前的复验,发现遮蔽即回退树遍历(`bc_globals_still_global`)。

## 2. 与标准 Lua 的差异清单

写脚本前先读这个,能省掉所有"为什么跑不起来":

**没有的**(写了直接报错或解析失败):
- `goto`/`label`(解析器拒绝)
- `load`/`loadstring`/`dofile`/`require` 之外的动态代码
- 元方法只有 `__index / __newindex / __len / __tostring`(没有 `__call`、`__eq`、算术元方法等)
- 协程、`__gc`、UTF-8 库、`string.dump`、`os.date` 等不存在

**非标准语义**(行为与标准 Lua 不同):
- **表构造器的位置字段逐个展开多值**:`{f(), g()}` 会把 `f()` 和 `g()` 的**全部**返回值都收进来(标准 Lua 只展开最后一个)。
- **字符串索引查 string 库**:`("x").upper` 存在(返回库函数),未知方法为 `nil` 而非报错 —— 等价于给字符串挂了 `__index = string`。
- 比较运算 `< <= > >=` 走数值转换(和标准 Lua 对字符串的处理不同);`table.sort` 的**默认**比较器独立实现:两数字比数值、两字符串比字节序、混型报错。
- 数字只有 double;`1 // 2`、位运算均经由 double 转换(存在但精度按 int64 截断)。

**模式匹配**:字节语义(和 Lua 5.1 一致),`.`/字符类匹配单字节;模式串不能含内嵌 NUL;subject 可以含 NUL。支持 `%a %c %d %g %l %p %s %u %w %x %z`(大写取反)、`[集合]` 与 `[^集合]`、`* + - ?`、`^ $` 锚、`(...)` 与 `()` 捕获(最多 32 个)、`%bxy` 平衡匹配、`%f[set]` 前界。

## 3. 标准库清单(全部)

全局:`print` `type` `tostring` `tonumber` `input`(带提示的阻塞读行) `pairs` `next` `ipairs` `setmetatable` `getmetatable` `rawget` `rawset` `rawequal` `assert` `error` `pcall` `select` `require`

- **string**:`len` `upper` `lower` `sub` `rep` `format` `byte` `char` `reverse` `find` `match` `gsub` `gmatch`
  - `format` 支持 `%d %i %o %x %X %c %e %E %f %g %G %s %q %%` + 宽度/精度/flags;`%s` 手工实现(NUL 字节安全)。
  - `find(s, pat, init, plain)` 返回 `start, end[, 捕获...]`;`gsub` 的 repl 支持 字符串(`%0`–`%9`)/表/函数,返回 `新串, 次数`。
- **table**:`insert` `remove` `concat` `unpack` `sort`(稳定归并)
- **math**:`floor` `ceil` `abs` `sqrt` `max` `min` `random` `randomseed`(每 State 独立 splitmix64,懒播种)
- **io**:`read`(阻塞,吃事件队列,见 [PROOT_AND_STDIN.md](PROOT_AND_STDIN.md)) `write` `flush`
- **os**:`getenv` `time` `clock`
- **package**:`package.loaded`(require 缓存);`require("ui")` 内置,`require("a.b")` → `<modroot>/a/b.lua` 或 `a/b/init.lua`

## 4. UI DSL

`ui.<type>{ props..., children... }` 的本质:**一个普通 Lua 表,打上 `__ui = "<type>"` 标签**。字符串键是 props,数组部分(1..#t)是 children;嵌套的 ui 节点作为 prop 值会序列化为子树。

### 4.1 组件与属性(渲染器权威定义,两模块一致)

| 组件 | 属性(类型/默认) | 说明 |
|---|---|---|
| `app` | `title`(str, "") | 非空时渲染标题头;children 纵向排列 |
| `column` | `spacing`(num, 8dp) | 纵向容器 |
| `row` | `spacing`(num, 8dp) | 横向容器,子项垂直居中 |
| `text` | `text`/别名`value`(str, "")、`size`(num, 16sp)、`font`/别名`typeface`(str, 资产路径)、`color`(str, 主题色)、`animate`(bool, true) | `color` 接受 `#RRGGBB`/`#AARRGGBB`;`animate=false` 走无动画廉价路径(棋盘/时钟用) |
| `button` | `text`(str, "button")、`onClick`(handler) | 子节点被忽略 |
| `card` | `spacing`(8dp)、`radius`(16dp)、`padding`(16dp) | |
| `input` | `value`(str, "")、`label`(str, "input")、`onSubmit`(handler) | **onSubmit 收到输入框当前文本(事件 payload)**;键盘 Done 触发 |
| `image` | `src`/别名`path`/`file`(str, "")、`size`(num, 96dp) | 经 AssetResolver 解码;失败显示 "missing" |
| `spacer` | `size`(num, 8dp) | 仅高度 |
| `divider` | — | 水平分隔线 |
| `scrollview` | `spacing`(8dp) | 纵向滚动容器 |
| `list` | `spacing`(8dp) | 语义即 column(非懒加载) |
| `listitem` | `title`/别名`text`、`subtitle`、`onClick`(handler) | children 渲染在标题下 |
| `stack` | `selected`/别名`page`(str, 首页) | 按 `key`/`name`/`id` 匹配子 `page`;只渲染选中页 |
| `page` | `spacing`(8dp);`key`/`name`/`id` 供 stack 选择 | 包裹层本身不渲染 |
| `switch` | `label`/别名`text`、`checked`/别名`value`(bool, false)、`onToggle`/`onChange`(handler) | **onToggle 收到 "true"/"false" 字符串**;onToggle 优先于 onChange |

所有组件:`key`/`id` 作为 diff 身份(影响动画);数值/尺寸带动画。未知组件类型渲染为错误色 chip 而非崩溃。

### 4.2 序列化契约(Execution is truth)

引擎把返回的树序列化为 JSON:`{"type":..., "props":{...}, "children":[...]}`。函数属性序列化为 `{"__handler": N}`,函数本体存进引擎侧 handler 表(**每次重建树 id 重新分配**)。props 键序为哈希表槽序(不排序、非插入序),所以**测试断言只能用子串匹配**。handler 上限 1024 个。

## 5. 事件与重渲染契约(最重要的章节)

宿主调用 `lx_invoke(handlerId, payload)` 之后发生什么:

1. **handler 以 `payload` 为第一个参数被调用**(无 payload 则无参)。
2. 若 handler **返回一个 ui 树**(带 `__ui` 标签的表)→ 它成为新的 `app_view`,序列化后即新屏幕。
3. 否则重新序列化现有 `app_view`;**若 `app_view` 是函数则先调用它**再序列化返回值。

由此得出**三条让界面更新的正确路径**,以及最重要的反例:

```lua
local ui = require("ui")
local count = 0

-- ✅ 路径 A(推荐,官方示例全部用这个):脚本返回 view 函数。
--    每次事件后引擎重新调用它,读取最新状态。
local function view()
  return ui.app {
    ui.text { text = "taps: " .. count },
    ui.button { text = "+1", onClick = function() count = count + 1 end },
  }
end
return view

-- ✅ 路径 B:handler 返回新树(适合局部替换整个屏幕)。
--    onClick = function() count = count + 1; return view() end

-- ✅ 路径 C:定时驱动 —— 树上挂 onTick(handler)+ interval(毫秒),
--    App 侧按 interval 调 onTick,每次 invoke 走上述 1–3(games/snake.lua 是范例)。

-- ❌ 反例(2026-09 修复的教训):返回静态表 + 在 handler 里改局部变量。
--    树已经序列化完毕,改的变量没人再读,屏幕永远不变。
--    return ui.app{ ui.text{ text = tostring(count) }, ... }  ← 千万别这样写
```

事件 payload 语义:`input.onSubmit(text)` 收到输入框文本;`switch.onToggle(v)` 收到 `"true"`/`"false"` 字符串;其余事件无参。payload 只有一个字符串参数 —— 数字自己 `tonumber`。

## 6. 测试工作流

```bash
make -C engine test          # 必须输出 ALL TESTS PASSED(引擎一切改动的门禁)
./engine/lx --ui foo.lua      # 打印 ---OUTPUT--- / ---TREE---,UI 契约可直接在桌面验证
./engine/lx --bc-dump f.lua   # 反汇编;LUAX_NO_BC=1 关 VM;LUAX_BC_STATS=1 看 VM 参与度
```

- **加引擎能力必须加 `t*` 测试**(AGENTS.md 硬规则):Lua 脚本测试注册进 Makefile `test:` recipe,并加入 `BC_DIFF_SCRIPTS`(树遍历/VM 差分);涉及 invoke/UI 契约的写 C driver(范式见 `tests/t26_invoke_tree.c`:run → 找 handler id → invoke → 断言 JSON 变化)。
- 新标准库函数:正向断言加进 `t6/t14/t25/t27`,负例(bad argument)加进 `t14` 的 `musterr` 列表。
- 纯库函数天然 VM 无关,差分免费;改执行语义(名字解析/调用约定)时必须跑差分。

## 7. 扩展 checklist

**加一个 UI 组件**(五处同步,缺一不可):
1. `engine/lx.c`:`UICTOR(名字)` 一行(得到 `ui.名字` 构造器)
2. `app/` 与 `runtime/` 两份 `ComponentRegistry.kt` 各加一个 Composable + 注册(**两模块代码手工复制,改完 diff 必须零差异**)
3. `ComponentCatalog.kt` 加面板条目(`validateAgainstRegistry` 会抓漏)
4. `ApiDocs.kt` 补文档条目
5. 本文件 4.1 表格加一行;若影响重渲染契约,更新第 5 节

**加一个标准库函数**:`lx.c` 写 `static Value st_xxx`(类型守卫用 `argStr`/`argTab`/`num2int`,错误一律 `lx_rt_error`)→ `openLibs` 注册 → 测试(t6/t14/t25/t27)→ 本文件第 3 节加行 → (可选)`LuaIntel.kt` 加补全。

**改事件/重渲染契约**:改 `lx_invoke`/`lx_build_tree` → t26 扩用例 → `luax_jni.c` ×2 与 `EngineHost` ×2、JS 引擎(`qjs_x.c` 的 `__lx_invoke` 前奏)同步 → 本文件第 5 节重写。

## 8. 维护约定

- 改 `engine/lx.c`、两份 `luax_jni.c`、`ComponentRegistry.kt` 的组件行为、或第 5 节的契约 → **同一次改动更新本文件**。
- 两份 `luax_jni.c` 与两份 `ComponentRegistry.kt`/`UiTreeRenderer.kt` 必须保持同步(字节级 diff 为零是常态;`Motion.kt` 的 app 侧多出的弹簧仅 app chrome 使用,允许差异)。
- 示例(`ProjectRepository.kt` 中的种子)是契约的教学载体:改契约时同步改示例,并按需升 `.samples-vN` 标记强制旧设备重播种(用 `overwriteSeedFiles` 只覆盖目标文件)。
