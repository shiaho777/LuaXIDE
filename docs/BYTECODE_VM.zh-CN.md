# 字节码 VM(Phase 1b,v0 设计与现状)

[English](BYTECODE_VM.md)

`engine/lx.c` 原本是纯树遍历解释器(Phase 1a)。现在它带有一个保守混合的字节码 VM:函数体在首次调用时尝试编译为寄存器字节码,编译成功则由栈式 VM 执行,否则透明回退到树遍历。**回退是语义安全网——VM 只加速,不改变任何可见行为。**

## 工作方式

```
callValue(T_FN)
  ├─ 调试关闭 && 未设 LUAX_NO_BC?
  │    ├─ 首次调用:bc_build() 尝试编译函数体
  │    │    ├─ 成功 → 每次调用先做全局名遮蔽校验 → vm_call() 执行字节码
  │    │    └─ 失败 → 标记 bc_tried,永久走树遍历
  │    └─ 已有 Proto → 校验 → vm_call()
  └─ 否则(调试会话/已禁用)→ 树遍历路径(原逻辑不变)
```

## v0 编译范围(其余一律回退)

| 支持 | 回退 |
|---|---|
| 局部/赋值/多赋值、调用语句 | 嵌套函数定义(闭包捕获 Env,需要 upvalue 机制) |
| if/elseif/else、while、repeat(until 可见体内局部)、数值 for、泛型 for | 变长参数函数(`...`) |
| break、return(含多值展开) | 自由变量(upvalue)——编译期检测 + 调用时复验 |
| 算术/位运算/比较/拼接、and/or 短路(保值语义) | goto/label(解析器本就拒绝) |
| 表构造(kv 字段;位置字段按引擎语义逐个展开多值) | |
| 方法调用 `obj:m(...)`、元表链(__index/__newindex/__len/__tostring) | |

### 动态作用域的兼容处理

本引擎按运行时 Env 链解析名字(动态作用域),不是词法作用域。因此:

1. 编译时:`K_NAME` 先查局部寄存器;再查定义处 Env 链(排除 globals)——查到即视为 upvalue,**拒绝编译**;
2. 运行时:被当作全局的名字记录在 `Proto->gk`,每次调用前重验"没有外层树遍历作用域在首次编译之后新声明同名局部"(见 `bc_globals_still_global`),发现遮蔽立即本次调用回退。

## 与树遍历的语义对齐点

- 数值 for 的 step<=0 走降序分支(step=0 死循环同样被步数上限截停);
- 比较走 `toNum`(无字符串排序);`and`/`or` 返回决定操作数的原值;
- 表构造的位置字段**每个都展开多值**(引擎既有语义,非标准 Lua);
- 运行错误保留 `line N:` 前缀(curLine 由指令行号表驱动);
- 取消检查与步数上限在每条指令上生效;
- pcall / 调试求值的 longjmp 路径同步恢复 VM 栈指针(`S->vtop`)。

## 观测与工具

```bash
make -C engine test        # 含 t25 用例、VM 参与度断言、bc-diff 差分验证
make -C engine bench-bc    # 双模式微基准
./engine/lx --bc-dump f.lua   # 反汇编源文件内全部可编译函数
LUAX_BC_STATS=1 ./engine/lx x.lua   # stderr 打印 "bc: N compiled calls, M fallbacks"
LUAX_NO_BC=1 ./engine/lx x.lua      # 整体禁用 VM(排障用)
```

`lx_bc_stats()` / `lx_bc_disassemble()` 已从 lx.h 导出,Android 侧后续可接入面板展示。

## 基准(engine/tests/bench_bc.lua,Apple Silicon macOS,-O2)

| 用例 | 树遍历 | 字节码 VM | 加速比 |
|---|---|---|---|
| fib(23) 递归 | ~0.023s | ~0.013s | **1.8x** |
| 数值循环 300 万次 | ~0.31s | ~0.029s | **~11x** |
| 字符串拼接/长度 6 万次 | ~0.27s | ~0.22s | ~1.25x(C 字符串操作主导) |
| 建表 20 万行 + pairs 累加 | ~0.105s | ~0.041s | **~2.6x** |

## 后续路线(未做,按价值排序)

1. **upvalue**:Lua 式 open/upvalue 协议,解锁"函数内嵌套函数"——覆盖面最大的一块
2. 主 chunk 编译(需配合 upvalue,顶层局部可被闭包捕获)
3. 常量折叠 / 跳转穿透等简单窥孔优化
4. 指令编码压缩(当前 BIns 8 字节,可打包至 4)+ 计算型 goto
5. 断点下沉到字节码行号表(目前调试会话整体回退树遍历,功能无损但慢)
