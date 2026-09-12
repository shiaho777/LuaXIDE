# Program Mode → 控制台(Console)模型

[English](PROGRAM_MODE.md)

> 2026-09 更新:交互终端已并入控制台。本文档描述当前模型。

## Rule(唯一输出原则)

- 脚本**返回 ui 树** → 预览面板(Compose 渲染,Execution is truth)
- `print` 输出 / 运行错误 / 系统日志 → **底部控制台**(唯一输出口)
- REPL 求值 / `io.read()` 回复 → 控制台底部输入行

预览面板不再有 Terminal 态:`PreviewKind` 收敛为 `Ui / Error / Idle`,纯输出程序运行后预览回到空态,输出在控制台查看。

## 控制台(LogSheet / LogPanel)

- 时间戳流式列表;级别筛选(V/D/I/W/E)、正则过滤、暂停、清空、导出(.log/.json)
- 错误行可点击跳转源码行
- 底部常驻输入行:
  - 平时为 REPL:`print(1+2)`、`=1+2` 语法糖、跨行全局变量
  - 程序阻塞在 `io.read()` 时切换为回复态(tertiary 横幅提示)
- dot 命令:`.run` `.stop` `.clear` `.help`

## No-root policy(不变)

LuaXIDE never requires root / Magisk / su. Program runs go through `NoRootRuntime`:
- sandbox under app private storage: `filesDir/sandbox/<projectId>/`
- `HOME` / `TMPDIR` / `work` confined there, `LUAX_NO_ROOT=1`
- optional proot stays unprivileged userland rootfs — still no device root

## Blocking stdin + cancel + proot

See `docs/PROOT_AND_STDIN.md`. Semantics unchanged:
- `io.read()` blocks until the console input sends a line
- **stop** / `.stop` cancels cooperative execution (incl. blocked read)
- Proot userland rootfs under `filesDir/proot-rootfs` (optional binary in assets)

## Package

Packaged runtime renders the ui tree when the entry returns one; print output
goes to the packaged app's own console view.
