# LuaX Platform ABI（引擎 ↔ 宿主）

[English](PLATFORM_ABI.md)

> 本文件是 LuaX 引擎（`engine/lx.c`、`lx.h`）与 Android 宿主（`EngineAdapter` / `EngineHost`）之间的**宿主契约**。改 run / invoke / cancel / 树 JSON 行为，必须同次更新本文件，并保证 `engine/tests/t26_invoke_tree.c` 通过。语言方言见 [LUAX.md](LUAX.zh-CN.md)。引擎内部见 [ENGINE.md](ENGINE.zh-CN.md)。

## 1. 平台分层

```
LuaX 脚本
   │ 驱动
   ▼
UI 树:{type, props, children} JSON   ← 渲染器只认这个
   ▲
   │ run(src) / invoke(id, payload) / cancel / step-limit
宿主适配层(EngineAdapter @ Kotlin / lx.h)
   │
   └── lx.h     LuaX 引擎     t1–t28 测试
```

**核心原则:渲染器跟随引擎的 JSON 树。** 脚本语法在 LUAX.md；树结构、事件语义、宿主行为在本文。

## 2. 宿主 API 面（C 契约）

| 契约 | lx.h |
|---|---|
| 创建/销毁 | `lx_new` / `lx_close` |
| run | `lx_run(S, src, err, errlen)` |
| 事件回调 | `lx_invoke(S, id, arg, err, errlen)` |
| 树/输出 | `lx_last_json` / `lx_last_output` |
| 取消 | `lx_cancel` / `lx_clear_cancel` |
| 步数上限 | `lx_set_step_limit` |
| 模块根 | `lx_set_modroot` / `lx_modroot` |

Kotlin 侧是 [`EngineAdapter`](../app/src/main/java/dev/luaxide/engine/EngineAdapter.kt)（`state / run / invoke / cancel / close`）。REPL、阻塞 stdin、调试器、rootfs **不在本 ABI 内** —— 它们在 `EngineHost` 上，通用调用方不得触碰。

## 3. run 语义

1. `run(src)` 在专用引擎线程同步执行；成功返回 0，失败非 0 并填 err。
2. `run` 开始时重置输出缓冲与步数计数，并清掉陈旧 cancel 标志（新 run 不受旧 cancel 影响）。
3. 脚本返回值决定第一帧：
   - 返回 ui 树（带 `__ui` 标记的 Lua 表）→ 序列化为树 JSON；
   - 返回**函数** → 记为活视图，立即调用得到第一帧；之后 invoke 若 handler 不返回树，再调这个函数；
   - 其他 / 无返回 → 树为 `null`；宿主回到空闲。
4. `print` 被捕获进输出缓冲，宿主通过 `last_output` 拉取 —— 引擎不直接写终端。

## 4. invoke 与重渲染契约

`invoke(handlerId, payload)`：

1. 函数属性在树 JSON 里序列化为 `{"__handler": N}`；**每次重建树都会重新编号** —— 宿主每轮重读。
2. 非空 `payload` 作为 handler 的唯一参数：`input.onChange(text)` / `input.onSubmit(text)` 收到字段文本；`slider.onChange(v)` 收到数值字符串；`switch.onToggle(v)` 收到 `"true"`/`"false"`；其余事件无参数。
3. **重渲染判定**：
   - handler 返回 ui 树 → 新树替换当前视图（并丢掉已存的视图函数）；
   - handler 返回函数 → 它成为新活视图并立即调用得到新树；
   - nil / 非树返回 → **保留旧树**；若存有视图函数，先再调用再序列化（LUAX.md §4.2 路径 A）。
4. handler 内部错误：返回非 0 + 错误信息（已知行号时前缀 `line N:`）；引擎仍可用，**当前树不变** —— 包括 handler 成功但视图函数在再序列化时抛错（json 缓冲与 handler 表整体保留）。

## 5. 运行时护栏

- **取消**：`cancel()` 设协作式取消标志（Lua 经 STEP 宏轮询）；命中报告 `"cancelled by user"`。`invoke` **不清** cancel 标志 —— run 中途取消后触发的事件 handler 也会被取消；`run` 会清。
- **步数上限**：`set_step_limit`（App 侧默认 50,000,000）；超出报告 `"execution step limit exceeded (possible infinite loop)"`。宿主不做转译。

## 6. UI 树

引擎暴露 24 个构造器（`app column row text button card input image icon spacer divider scrollview list listitem stack page switch checkbox box slider progress dialog tabs tab`），产出 `{type, props, children}` 节点。属性语义（仅规范名 —— 已删除的别名链是文档化的破坏性迁移，见 LUAX.md §5；默认值、事件名、`text` 的字符串构造糖、字符串子项包装）遵循 LUAX.md §5 属性表。Conformance 按类型断言（§7）。

## 7. Conformance 测试

| 断言 | 测试 |
|---|---|
| invoke 三态（采纳/保留/错误） | `t26_invoke_tree.c` |
| payload 到达 handler | t26 |
| 24 组件类型全序列化 | t15（构造器）；t22（新类型 / JSON） |
| text 专属字符串构造糖 / 字符串子项 | t15 / t22 |
| print 捕获 / 错误行号 | t22 / t23 |
| 取消 / 步数上限语义 | t19 |

新增契约能力须在同一次改动里扩展这些测试（AGENTS.md）。

## 8. 打包与独立运行

- 模板 runtime（`:runtime` release APK → `template.apk`）按 ABI 内嵌 `libluax.so`。
- `luaxcfg.json` 的 `entryFile` 是 Lua 路径；`RuntimeActivity` 始终使用 `EngineHost`。
- 打包前冒烟校验经 `EngineHost` 跑入口（`BuildPipeline.validateEntry`）。
- 项目源码整包放在 `assets/lua/`。
