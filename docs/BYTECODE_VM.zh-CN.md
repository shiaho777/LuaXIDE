# 字节码 VM(Phase 1b,v0 设计与现状)

[English](BYTECODE_VM.md)

`engine/lx.c` 原本是纯树遍历解释器(Phase 1a)。现在它带有一个保守混合的字节码 VM:函数体在首次调用时尝试编译为寄存器字节码,**顶层 chunk** 也在每次 `run`/`dostring`/`repl`/`require` 时以同样方式编译(包装成合成的 0 参 `Func`);编译成功则由栈式 VM 执行,否则透明回退到树遍历。**回退是语义安全网——VM 只加速,不改变任何可见行为。**

## 工作方式

```
callValue(T_FN)
  ├─ 调试关闭 && 未设 LUAX_NO_BC?
  │    ├─ 首次调用:bc_build() 尝试编译函数体
  │    │    ├─ 成功 → 每次调用先做全局名遮蔽校验 → vm_call() 执行字节码
  │    │    └─ 失败 → 标记 bc_tried,永久走树遍历
  │    └─ 已有 Proto → 校验 → vm_call()
  └─ 否则(调试会话/已禁用)→ 树遍历路径(原逻辑不变)

execChunk(chunk)                     # lx_run / lx_dostring / lx_repl / require
  ├─ 调试关闭 && 未设 LUAX_NO_BC?
  │    ├─ chunk 包装为合成 Func{env=新建的 chunk env} → bc_build()
  │    │    ├─ 成功 + 全局名校验 → vm_call();由 S->nret/retbuf 重建 Flow
  │    │    └─ 失败 → 落到下方树遍历循环
  └─ 否则(调试会话/已禁用)→ 树遍历路径
```

chunk 路径刻意复用函数体机制:`capmode` 检测让存在嵌套函数时顶层局部驻留 `Env` 作用域(闭包才能正确捕获);无嵌套函数的 chunk 则把局部留在寄存器。循环外 `break` 与函数体内一样软回退编译、改走树遍历——解析器本就把 `break` 视为块结束,存活语义不变。

### 编码与派发

- `BIns` 为 4 字节 `{op,a,b,c}`;立即数存于平行的 `Proto.imm[pc]` u16 数组(常量索引 ≤65535,或 i16 跳转增量——超界函数软回退树遍历)。
- 派发在 GNU/Clang 下用计算型 goto(每条指令一次间接跳转;各 label 处 `in.op` 为常量,内层 op-switch 被折叠),其他编译器回退 switch。
- 条件编译为融合测试跳转(`TEST`/`TESTN` 直接携带跳转增量,不再有独立 `JMP`);字面量算术/位运算/比较/拼接在编译期折叠为 `LOADK`。

## v0 编译范围(其余一律回退)

| 支持 | 回退 |
|---|---|
| 局部/赋值/多赋值、调用语句、**变长参数函数**(`...` 在调用入口打包为 env 绑定的 `"..."` 表;`BC_VARARG` 展开——嵌套函数经 env 链看到外层变长参数) | |
| if/elseif/else、while、repeat(until 可见体内局部)、数值 for、泛型 for | goto/label(解析器本就拒绝) |
| break、return(含多值展开) | |
| 算术/位运算/比较/拼接、and/or 短路(保值语义) | |
| 表构造(kv 字段;位置字段按引擎语义逐个展开多值) | |
| 方法调用 `obj:m(...)`、元表链(__index/__newindex/__len/__tostring) | |
| **嵌套函数与捕获变量**——闭包直接绑定运行时 Env 链(见下),涵盖参数、逐迭代循环变量、`do` 块局部 | |

### 捕获变量:Env 驻留 "capmode"

本引擎按运行时 `Env` 链解析名字(动态作用域),而 `Env` 对象本身就是共享可变绑定容器——闭包因此无需独立的 upvalue 格协议:

1. 函数体含嵌套函数定义时按 **capmode** 编译:其局部变量驻留 `Env` 作用域而非寄存器。VM 在树遍历器调用 `newEnv` 的相同点位发射 `ENVOPEN`/`ENVCLOSE`——每个 `if`/`elseif`/`else`/`do` 块,以及**逐迭代**的循环体(因此每轮闭包捕获当轮的循环变量,与逐迭代 `newEnv` 一致)。
2. capmode 代码内所有名字编译为 `GETENV`/`SETENV`——即 `envGetFn`/`envAssignFn` 本体——走与树遍历相同的链;`DECL` 对应 `envDeclareFn`。`repeat ... until` 仍在 `ENVCLOSE` 之前求值,可见体内局部;`break` 在跳出前先展开已打开的作用域 env。
3. `CLOSURE` 以 `env = cur`(当前活跃作用域 env)构造 `Func`——与 `K_FUNC` 求值一致。嵌套函数仍经 `bc_build` 惰性编译,多级嵌套递归成立。
4. 非 capmode 函数中,编译期命中定义处 Env 链的自由名(`bc_is_upvalue`)同样编译为 `GETENV`/`SETENV` 而非拒绝;只有链上查不到的名字走下方全局路径。
5. 被当作全局的名字仍记录在 `Proto->gk`,每次调用前重验"没有外层作用域在首次编译之后新声明同名局部"(`bc_globals_still_global`),发现遮蔽立即本次调用回退。

## 与树遍历的语义对齐点

- 数值 for 的 step<=0 走降序分支(step=0 死循环同样被步数上限截停);
- 比较走 `toNum`(无字符串排序);`and`/`or` 返回决定操作数的原值;
- 表构造的位置字段**每个都展开多值**(引擎既有语义,非标准 Lua);
- 运行错误保留 `line N:` 前缀(curLine 由指令行号表驱动);
- 取消检查与步数上限在每条指令上生效;
- pcall / 调试求值的 longjmp 路径同步恢复 VM 栈指针(`S->vtop`)。

## 观测与工具

```bash
make -C engine test        # 含 t25 用例、VM 参与度断言、bc-diff + bc-fuzz 差分验证
make -C engine bench-bc    # 双模式微基准
./engine/lx --bc-dump f.lua   # 反汇编源文件内全部可编译函数
LUAX_BC_STATS=1 ./engine/lx x.lua   # stderr 打印 "bc: N compiled calls, M fallbacks"
LUAX_NO_BC=1 ./engine/lx x.lua      # 整体禁用 VM(排障用)
```

`lx_bc_stats()` / `lx_bc_disassemble()` 已从 lx.h 导出,Android 侧后续可接入面板展示。

## 基准(engine/tests/bench_bc.lua,Apple Silicon macOS,-O2)

| 用例 | 树遍历 | 字节码 VM | 加速比 |
|---|---|---|---|
| fib(23) 递归 | ~0.025s | ~0.013s | **~1.9x** |
| 数值循环 300 万次 | ~0.42s | ~0.030s | **~14x** |
| **顶层数值循环 500 万次**(主 chunk) | ~1.17s | ~0.052s | **~22x** |
| 字符串拼接/长度 6 万次 | ~0.36s | ~0.31s | ~1.2x(C 字符串操作主导) |
| 建表 20 万行 + pairs 累加 | ~0.13s | ~0.051s | **~2.6x** |

## 后续路线(未做,按价值排序)

1. 断点下沉到字节码行号表(目前调试会话整体回退树遍历,功能无损但慢)

所有语句/表达式形态均可编译(含变长参数)。剩余回退全部是*运行时*门禁(调试会话、`LUAX_NO_BC`)与超限情形(u16 常量索引、i16 跳转增量、200 寄存器)。
