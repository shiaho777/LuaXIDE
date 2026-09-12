# Proot 用户态 + 阻塞 stdin + 取消

[English](PROOT_AND_STDIN.md)

## 无 root 原则
- 永不使用 `su`、Magisk 或设备 root。
- Proot 仅为无特权用户态。
- 沙箱位于应用私有 `filesDir` 之下。

## 内置资产
APK 内嵌 Termux 构建的 proot,覆盖 `arm64-v8a`、`armeabi-v7a`、`x86_64`:
- `app/src/main/assets/proot/<abi>/bin/proot`
- 依赖库:`libtalloc.so*`、`libandroid-shmem.so`
- `libexec/proot/loader`(存在时含 loader32)
- `min-rootfs.zip` 最小客体重文件系统布局

首次打开项目时自动安装到 `filesDir/proot-rootfs/`(标记 `.bundle-v2`)。

## 终端
- 纯程序项目(无 UI 树)打开 `TerminalFace`。
- 命令:`.clear` `.help` `.run` `.stop` `.proot`
- `io.read()` / `input()` 阻塞在 stdin 队列上。
- 等待期间,回车键向被阻塞的读取者喂入一行。
- `.stop` / 停止按钮:`lx_cancel` + proot `destroyForcibly`。

## 真机验证
1. 创建一个 **Program** 项目。
2. 运行:
   ```lua
   io.write("name: ")
   local n = io.read()
   print("hi", n)
   ```
3. 提示出现时输入名字;可用停止键在等待中取消。
4. 终端中输入 `.proot` → 预期输出 `proot-selftest` / `rootfs-ok` / `[proot ok]`。
