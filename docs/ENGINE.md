# LuaX 引擎内幕(ENGINE)

> 面向**引擎维护者**。语言语义(方言、标准库、UI DSL、运行时行为)的权威定义在 [LUAX.md](LUAX.md) —— 本文不重复它;跨语言宿主契约见 [PLATFORM_ABI.md](PLATFORM_ABI.md)。改 `engine/lx.c` 必须同次更新 LUAX.md(AGENTS.md 规则 8)。

## 1. 架构

- **单一源码,三个构建目标**:`engine/lx.c`(~2200 行,单文件)编译为桌面 CLI(`make`)、`:app` 与 `:runtime` 的 `libluax.so`。两个 Android 模块各持一份字节级一致的 `luax_jni.c` —— 改 JNI 层必须两份同步(diff 为零是常态)。
- **混合执行**:树遍历解释器 + 字节码 VM(函数体首次调用时编译,不可编译的构造透明回退;VM 只加速不改语义)。VM 设计、编译范围与基准见 [BYTECODE_VM.md](BYTECODE_VM.md)。
- **内存**:arena 分配,无 GC,`lx_close` 整体释放。脚本内存单调增长 —— 不要写泄漏型长循环脚本;大字符串/缓冲用 malloc+free(`st_tconcat` 是范式)。
- **调试协议**:`lx_debug_*`(断点/条件断点/logpoint/单步/监视/求值),仅 `:app` 的 EngineHost 接入 —— `:runtime` 不带,不得跨模块复制调用。

## 2. 实现要点(易踩区)

- **序列化**(`jnode`):树 → `{type, props, children}` JSON;函数 prop 注册进 `S->handlers[id]`,**每次树重建 id 重新分配**(宿主每轮重读);上限 1024。props 键序为哈希槽序 —— 测试断言只能用子串匹配。
- **invoke 语义**:`lx_invoke` 用 `callValue` 的**直接返回值**(非 `S->retbuf`,避免被后续调用污染)判定 handler 是否返回新树;nil → 重序列化 `app_view`(view 函数会重调)。语言级契约见 LUAX.md §4.2。
- **防护**:`STEP` 宏(取消 + 步数)插在语句/循环/VM 每指令;`lx_run` 清残留取消标志,`lx_invoke` 不清。
- **动态作用域防护**:VM 编译期把"非局部名"记入 `Proto->gk`,每次调用前 `bc_globals_still_global` 复验无遮蔽,命中即回退树遍历(见 BYTECODE_VM.md)。

## 3. 测试工作流

```bash
make -C engine test          # 必须输出 ALL TESTS PASSED;含:
                             #   t1–t27 功能/契约测试、bc-diff 差分(11 脚本)、
                             #   doc-check(LUAX.md 代码块逐个实跑)、CLI 冒烟
./engine/lx --ui foo.lua      # 打印 ---OUTPUT--- / ---TREE---
./engine/lx --bc-dump f.lua   # 反汇编;LUAX_NO_BC=1 关 VM;LUAX_BC_STATS=1 看参与度
```

约定:

- **加引擎能力必须加 `t*` 测试**(AGENTS.md 硬规则);新标准库函数:正向断言进 t6/t14/t25,负例(bad argument)进 t14 的 `musterr` 列表
- 涉及 invoke/UI 契约的写 C driver(范式见 `t26_invoke_tree.c`:run → 找 handler id → invoke → 断言 JSON 变化)
- 纯库函数天然 VM 无关,差分免费;改执行语义(名字解析/调用约定)必须过差分
- **改 LUAX.md 的示例 = 改测试**:doc-check 会逐块实跑

## 4. 扩展 checklist

**加标准库函数**:`lx.c` 写 `static Value st_xxx`(类型守卫用 `argStr`/`argTab`/`num2int`,错误一律 `lx_rt_error`)→ `openLibs` 注册 → 测试(t6/t14/t25/t27)→ LUAX.md §3 加行(签名 + 一行语义 + 可运行示例)→ 可选 `LuaIntel.kt` 补全。

**加 UI 组件**(五处同步,缺一不可):

1. `engine/lx.c`:`UICTOR(名字)` 一行
2. `app/` 与 `runtime/` 两份 `ComponentRegistry.kt` 各加 Composable(改完 diff 必须零差异)
3. `ComponentCatalog.kt` 面板条目(`validateAgainstRegistry` 会抓漏)
4. `ApiDocs.kt` 文档条目
5. LUAX.md §5 表格加一行;若影响重渲染契约,同步 LUAX.md §4.2 与 PLATFORM_ABI.md

**改事件/重渲染契约**:改 `lx_invoke`/`lx_build_tree` → t26 扩用例 → `luax_jni.c` ×2、`EngineHost` ×2、JS/Python 引擎同步(PLATFORM_ABI conformance 全过)→ LUAX.md §4.2 重写。

## 5. 维护约定

- 改 `engine/lx.c`、两份 `luax_jni.c`、`ComponentRegistry.kt` 的组件行为、或事件契约 → **同一次改动更新 LUAX.md**(语言面)与本文(实现面,如涉及)。
- 示例(`ProjectRepository.kt` 种子)是契约的教学载体:改契约同步改示例,按需升 `.samples-vN` 强制旧设备重播种。
- `Motion.kt` app 侧多出的弹簧仅 IDE chrome 使用,允许与 runtime 差异;其余共享文件必须字节级一致。
