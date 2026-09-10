# LuaX Platform ABI(跨语言契约权威规范)

> 本文件定义**语言无关的宿主契约**:任何脚本引擎接入 LuaXIDE 平台,必须实现本文的 API 面与语义。当前两个参考实现:`engine/lx.c`(LuaX,Lua)与 `engine-js/qjs_x.c`(QuickJS,JavaScript)。**改任一引擎的契约行为,必须同次更新本文件,并保证两个实现的 conformance 测试(t26 / j6)同步通过** —— 两套测试断言的是同一份契约,任何一侧的语义偏差都会被 CI 拦截。语言方言与各自引擎内部细节见 [ENGINE.md](ENGINE.md)。

## 1. 平台分层

```
脚本(Lua / JS / 未来的语言)
   │ 驱动
   ▼
UI 树契约:{type, props, children} JSON   ← 语言无关,渲染器只认这个
   ▲
   │ run(src) / invoke(id, payload) / cancel / step-limit
宿主适配层(EngineAdapter @ Kotlin / facade C API)
   │
   ├── lx.h     LuaX 引擎(Lua)          t1–t27 测试
   └── qjs_x.h  QuickJS 引擎(JavaScript) j1–j6 测试
```

**核心原则:所有语言跑出同一棵树。** 语言适配的差异只允许存在于"驱动脚本怎么写"(构造器语法、闭包语法),不允许存在于树的结构、事件语义或宿主行为。

## 2. 宿主 API 面(C 契约)

每个引擎 facade 必须提供以下函数(签名可按语言习惯命名,语义必须一致):

| 契约 | lx.h | qjs_x.h |
|---|---|---|
| 创建/销毁 | `lx_new` / `lx_close` | `qjsx_new` / `qjsx_free` |
| 运行 | `lx_run(S, src, err, errlen)` | `qjsx_run(x, src, err, errlen)` |
| 事件回调 | `lx_invoke(S, id, arg, err, errlen)` | `qjsx_invoke(x, id, arg, err, errlen)` |
| 取树/输出 | `lx_last_json` / `lx_last_output` | `qjsx_last_json` / `qjsx_last_output` |
| 取消 | `lx_cancel` / `lx_clear_cancel` | `qjsx_cancel` / `qjsx_clear_cancel` |
| 步数上限 | `lx_set_step_limit` | `qjsx_set_step_limit` |

Kotlin 侧统一为 [`EngineAdapter`](../app/src/main/java/dev/luaxide/engine/EngineAdapter.kt)(`state / run / invoke / cancel / close`);Lua 专属能力(REPL、阻塞 stdin、调试器、rootfs)**不在 ABI 内**,留在 `EngineHost`,语言无关调用方不得触碰。

## 3. run 语义

1. `run(src)` 在专用引擎线程同步执行;返回 0 成功,非 0 失败并填充 err。
2. **每引擎保证**:`run` 开始时重置输出缓冲与步数计数,并清除残留取消标志(新 run 不受旧取消影响)。
3. 脚本返回值决定首屏:
   - 返回 ui 树(Lua:带 `__ui` 标签的表;JS:`{type: string, ...}` 对象)→ 序列化为树 JSON;
   - 返回**函数**(Lua 特有)→ 每次 invoke 前先调用它,返回值再走上面的判定;
   - 其他/无返回 → 树为 `null`(Lua)或保持上次的树(JS),宿主据此回到空态。
4. `print`(及等价输出)被捕获进输出缓冲,由宿主经 `last_output` 拉取 —— 引擎不得直接写终端。

## 4. invoke 与重渲染契约(ABI 的灵魂)

`invoke(handlerId, payload)`:

1. 树 JSON 中函数属性序列化为 `{"__handler": N}`;**每次树重建 id 重新分配**,宿主每轮重新读取。
2. `payload` 非空时作为 handler 的第一个(且唯一的)参数传入:`input.onSubmit(text)` 收到输入框文本;`switch.onToggle(v)` 收到 `"true"`/`"false"` 字符串;其余事件无参。
3. **重渲染判定**(两引擎必须一致):
   - handler 返回 ui 树 → 新树替换当前视图;
   - 返回 nil/undefined/非树 → **保留旧树**(JS 侧不得提前清空 json;Lua 侧由 `lx_build_tree` 重序列化保证);
   - Lua 特有:app_view 为函数时先重新调用(见 ENGINE.md §5 路径 A)。
4. handler 内部错误:返回非 0 + 错误信息(含行号时以 `line N:` 前缀),引擎保持可用。

## 5. 运行时防护

- **取消**:`cancel()` 置协作式取消标志(轮询点由引擎自定:Lua 为 STEP 宏,JS 为 interrupt handler);命中报 `"cancelled by user"`。`invoke` **不**清取消标志 —— 中途取消后触发的事件 handler 同样被取消;`run` 会清。
- **步数上限**:`set_step_limit`(App 侧默认 50,000,000);超限报 `"execution step limit exceeded (possible infinite loop)"`。两引擎文案逐字一致,宿主不做二次翻译。
- QuickJS 附加:内存上限 64MB、栈上限 4MB(`qjsx_new` 固定)。

## 6. UI 组件奇偶性

**JS 侧必须暴露与 Lua 完全相同的 16 个构造器**(`app column row text button card input image spacer divider scrollview list listitem stack page switch`),生成的树节点结构一致。属性语义(别名链、默认值、事件名)以 ENGINE.md §4.1 的属性表为准 —— 那是语言无关的渲染器契约。conformance 测试逐类型断言(见 §7)。

## 7. Conformance 测试(防漂移机制)

两套测试断言**同一契约**,必须同步演进:

| 断言 | Lua 侧 | JS 侧 |
|---|---|---|
| invoke 三态(树采纳/保留/错误) | `t26_invoke_tree.c` | `j6_conformance.c` |
| payload 到达 handler | t26 | j6 |
| 16 组件类型全序列化 | t15(serialize 覆盖) | j6 |
| print 捕获/错误行号 | t22/t23 | j4/j6 |
| 取消/步数语义 | t19(stdio/cancel) | j5_cancel.c |

**加契约能力 = 两套 conformance 同步加断言**(AGENTS.md 硬规则)。

## 8. 接入新引擎 checklist

1. 选定语言引擎(嵌入友好、可协作中断),写 facade 实现 §2 API 面 + §3–§6 语义;
2. `Language.kt` 注册(supported=true 前必须过 1–4);
3. Kotlin `EngineAdapter` 实现 + JNI 桥;
4. **写一份 conformance 驱动测试**(照抄 j6 的断言结构),进 Makefile + `ci.yml` + 分支保护;
5. 若涉及打包:阶段三的入口路由 + 模板 runtime 带上对应 .so;
6. 更新本文件 §2/§7 的表格与 ENGINE.md 互链。
