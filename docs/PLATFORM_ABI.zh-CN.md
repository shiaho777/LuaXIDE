# LuaX Platform ABI(跨语言契约权威规范)

[English](PLATFORM_ABI.md)

> 本文件定义宿主契约。当前三个参考实现:`engine/lx.c`(LuaX,Lua)、`engine-js/qjs_x.c`(QuickJS,JavaScript)、`engine-py/mpy_x.c`(MicroPython,Python —— 试点,限制见 §10)。**JavaScript 与 Python 共用 JSON UI 树。** LuaX 不再产出这棵树:页面是 HTML 与 CSS,规范在 [LUAX.md](LUAX.zh-CN.md) §4–§5,由 `engine/tests/t26_invoke_tree.c` 断言。改树契约必须同次更新本文件和 `j6`。改 Lua HTML 宿主必须同次更新 LUAX.md 和 `t26`。`t26` 与 `j6` 不再是同一套测试。LuaX 引擎内部细节见 [ENGINE.md](ENGINE.zh-CN.md)。

## 1. 平台分层

```
脚本
   │
   ├── LuaX (lx.h)     HTML 操作 JSON  →  WebView     t26
   └── JS / Python     UI 树 JSON      →  Compose     j6(JS);Python 试点
         {type, props, children}
```

**核心原则:JavaScript 与 Python 产出同一棵树。** 这两种语言的差异只允许出现在脚本怎么写。LuaX 渲染工程里的 HTML 与 CSS。LuaX 上的 `lx_invoke` 一律失败;DOM 事件走 `lx_html_event`。

## 2. 宿主 API 面(C 契约)

每个引擎 facade 必须提供以下函数(签名可按语言习惯命名,语义必须一致):

| 契约 | lx.h(Lua) | qjs_x.h(JS) | mpy_x.h(Python,试点) |
|---|---|---|---|
| 创建/销毁 | `lx_new` / `lx_close` | `qjsx_new` / `qjsx_free` | `mpyx_new` / `mpyx_free` |
| 运行 | `lx_run(S, src, err, errlen)` | `qjsx_run(x, src, err, errlen)` | `mpyx_run(x, src, err, errlen)` |
| 事件回调 | `lx_html_event`(DOM)。`lx_invoke` 一律报错 | `qjsx_invoke(x, id, arg, err, errlen)` | `mpyx_invoke(x, id, arg, err, errlen)` |
| 树或操作 / 输出 | `lx_last_json` 是 DOM 操作的 JSON 数组;`lx_html_clear_ops` 丢掉它。`lx_last_output` | `qjsx_last_json` 是树 / `qjsx_last_output` | `mpyx_last_json` 是树 / `mpyx_last_output` |
| 取消 | `lx_cancel` / `lx_clear_cancel` | `qjsx_cancel` / `qjsx_clear_cancel` | `mpyx_cancel` / `mpyx_clear_cancel`(见 §10 注) |
| 步数上限 | `lx_set_step_limit` | `qjsx_set_step_limit` | `mpyx_set_step_limit`(见 §10 注) |

Kotlin 侧统一为 [`EngineAdapter`](../app/src/main/java/dev/luaxide/engine/EngineAdapter.kt)(`state / run / invoke / cancel / close`);Lua 专属能力(REPL、阻塞 stdin、调试器、rootfs)**不在 ABI 内**,留在 `EngineHost`,语言无关调用方不得触碰。

## 3. run 语义

1. `run(src)` 在专用引擎线程同步执行;返回 0 成功,非 0 失败并填充 err。
2. **每引擎保证**:`run` 开始时重置输出缓冲与步数计数,并清除残留取消标志(新 run 不受旧取消影响)。
3. **JavaScript 与 Python** 的脚本返回值决定首屏:
   - 返回 ui 树(`{type: string, ...}`)→ 序列化为树 JSON;
   - 返回**函数** → 记忆为活视图(`__lx_view` / `view()` 约定),立即调用产生首屏;后续 invoke 若 handler 未返回树则重调它。Python 改找全局 `view()` 或 `_lx_tree`(§10);
   - 其他/无返回 → 树为 `null`(JS 也可能保留上一棵树,以引擎为准),宿主回到空态。
   - **LuaX** 不建树。`run` 先清空操作缓冲,随后 `html.*` 追加 DOM 操作。`lx_last_json` 是这个数组(空时为 `[]`)。失败的 `run` 丢掉缓冲。
4. `print`(及等价输出)被捕获进输出缓冲,由宿主经 `last_output` 拉取 —— 引擎不得直接写终端。

## 4. invoke 与重渲染契约(JSON 树:JavaScript 与 Python)

LuaX 的事件见 [LUAX.md](LUAX.zh-CN.md) §4.2:`html.on(id, event, fn)`,载荷是一个字符串,handler 出错会回滚这一次调用里的操作批次。本节只描述树契约。

`invoke(handlerId, payload)`:

1. 树 JSON 中函数属性序列化为 `{"__handler": N}`;**每次树重建 id 重新分配**,宿主每轮重新读取。
2. `payload` 非空时作为 handler 的第一个(且唯一的)参数传入:`input.onChange(text)` / `input.onSubmit(text)` 收到输入框文本字符串;`slider.onChange(v)` 收到数值的字符串形式;`switch.onToggle(v)` 收到 `"true"`/`"false"` 字符串;其余事件无参。
3. **重渲染判定**(JavaScript 一致性目标;Python 限制见 §10):
   - handler 返回 ui 树 → 新树替换当前视图(同时丢弃已存的视图函数);
   - handler 返回函数 → 成为新的活视图,立即调用产生新树;
   - 返回 nil/undefined/非树 → **保留旧树**(JS 侧不得提前清空 json);若存有视图函数则先重调它再序列化。
4. handler 内部错误:返回非 0 + 错误信息(含行号时以 `line N:` 前缀),引擎保持可用,**当前树保持不变** —— 包括 handler 成功但视图函数在重序列化期间抛错的情形(json 缓冲与 handler 表整体保留)。

## 5. 运行时防护

- **取消**:`cancel()` 置协作式取消标志(轮询点由引擎自定:Lua 为 STEP 宏,JS 为 interrupt handler);命中报 `"cancelled by user"`。`invoke` **不**清取消标志 —— 中途取消后触发的事件 handler 同样被取消;`run` 会清。
- **步数上限**:`set_step_limit`(App 侧默认 50,000,000);超限报 `"execution step limit exceeded (possible infinite loop)"`。文案在实现引擎间逐字一致,宿主不做二次翻译。
- QuickJS 附加:内存上限 64MB、栈上限 4MB(`qjsx_new` 固定)。

## 6. UI 组件一致性(JavaScript 与 Python)

**JavaScript 暴露 19 个构造器**(`app column row text button card input image spacer divider scrollview list listitem stack page switch box slider progress`)。Python 对齐同样的节点形状。属性名、默认值、事件名、`text` 的字符串构造糖、字符串子项包装,以两份 `ComponentRegistry.kt` 为准(必须字节级一致),由 `j6` 断言。LuaX 没有这些构造器。LUAX.md §5 是 HTML 宿主,不是这张表。

## 7. Conformance 测试(防漂移机制)

`t26` 断言 Lua HTML 宿主。`j6` 断言 JavaScript 的树。两者不是镜像。

| 断言 | Lua HTML(未注明时为 `t26`) | JS 树(未注明时为 `j6`) |
|---|---|---|
| DOM 事件载荷、未知 id、出错回滚、重新登记 | `t26_invoke_tree.c` | — |
| `lx_invoke` 已退役 | t26 | — |
| invoke 三态(树采纳/保留/错误) | — | `j6_conformance.c` |
| payload 到达 handler | t26(字符串载荷) | j6 |
| 19 个组件类型序列化 | —(`require("ui")` 报错;t15 / t28) | j6 |
| print 捕获 / 错误行号 | t22/t23 | j4/j6 |
| 取消 / 步数语义 | t19(stdio/cancel) | j5_cancel.c |

**树契约的新能力同次扩展 `j6`。Lua HTML 的新能力同次扩展 `t26` 和 LUAX.md。**

## 8. 打包与独立运行(阶段三起)

打包链路对多语言的支撑已闭环:

- 模板 runtime(`:runtime` release APK → `template.apk`)内置**多引擎**(libluax.so + libluaxjs.so × 各 ABI)
- `luaxcfg.json` 的 `entryFile` 决定语言:`.js` → `JsEngineHost`,`.py` → `PyEngineHost`,其余 → `EngineHost`。Lua 入口若工程里有页面(`index.html`,或入口脚本旁边的同名 `.html`)就用 WebView 渲染。没有页面的 Lua 入口仍走 print 终端。JS 与 Python 仍渲染树。
- 打包前的冒烟校验同样按入口后缀选引擎(BuildPipeline.validateEntry)
- 工程源码整体进 `assets/lua/`(目录名历史沿用,与语言无关)

## 9. 接入新引擎 checklist

1. 选定语言引擎(嵌入友好、可协作中断),写 facade 实现 §2 API 面。走树的语言实现 §3–§6。改渲染 HTML 的语言遵循 LUAX.md §4–§5,不产出树;
2. `Language.kt` 注册(supported=true 前必须过 1–4);
3. Kotlin `EngineAdapter` 实现 + JNI 桥;
4. **写一份 conformance 驱动测试**(照抄 j6 的断言结构),进 Makefile + `ci.yml` + 分支保护;
5. 打包接入:模块 CMake 加 .so → RuntimeActivity 路由加分支 → BuildPipeline.validateEntry 加分支 → syncRuntimeTemplate;
6. 更新本文件 §2/§7 的表格,并与 LUAX.md/ENGINE.md 互链。

## 10. Python 引擎(engine-py)的现状与限制

`engine-py/mpy_x.c` 基于 MicroPython v1.25.0 embed port(自包含生成包),已**接入 App**:JNI 桥 `mpy_jni.c` ×2、`PyEngineHost`(实现 EngineAdapter,IDE 与打包 runtime 双变体)、`Language.PYTHON.supported = true`、入口路由(`.py` → PyEngineHost)与打包前校验均已打通;模拟器 E2E:创建 Python 项目 → 运行渲染 → 点击 +1 重渲染(taps 0→1→2)。

实现要点(与 JavaScript 树引擎对齐的部分):run → 树 JSON + print 捕获、invoke(id, payload) → handler + 重渲染(view() 优先)、`MICROPY_VM_HOOK_LOOP` 驱动的协作式取消与步数上限(错误文案与 Lua/JS 逐字一致,见 p2)、globals 跨 invoke 存活。Python 不走 Lua HTML 宿主。

Python 不在 j6 覆盖范围内:p1_smoke.c 和 p2_cancel.c 仅覆盖各自断言的子集,不证明组件/事件完全一致。其全局 `view()` 优先于 handler 返回树,handler 返回函数也不等同于 JS 的活视图替换。计数器冒烟测试不代表完整 ABI 一致性。`t26` 不适用于 Python。

**仍属限制(接入后续 Issue 处理)**:
- 无 `import` 模块根路径(多文件工程未支持;单文件项目完整可用)
- 单进程单引擎实例(facade 用进程级 `g_active`;宿主以单例方式使用)
- 调试器/REPL/stdin 不适用于 Python(契约允许:这些本就是 Lua 专属扩展)
- embed 配置为裁剪版(compiler + GC + slice + str/float builtins);缺 `re`/`json` 等标准库,需要时按 mpconfigport.h 增项并重新生成 micropython_embed/
