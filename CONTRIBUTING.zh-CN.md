# 贡献指南

[English](CONTRIBUTING.md)

感谢关注 LuaXIDE —— 一个纯 Android 端、无需 root 的 Lua IDE。开工前建议先读 [README](README.zh-CN.md) 了解架构。

## 快速上手

```bash
# 引擎测试套件(需要 clang)
make -C engine test

# Android 构建(需要 JDK 17 + Android SDK)
./gradlew :app:assembleDebug :runtime:assembleDebug

# 修改 runtime 后同步模板 APK
./gradlew :runtime:syncRuntimeTemplate
```

真机自检:安装 debug 包后,在应用内打开"自检"面板(沙箱 / proot / stdin / 取消 / 模板 / 安装 六项),或用 `scripts/device-checklist.sh` 配合 adb 查看日志。

## 交付流程(Issue → PR → CI → merge)

1. **先开 Issue**(或认领现有 Issue):写清问题 / 目标 / 验收标准。
2. 从最新 `main` 切分支,命名如 `codex/主题` 或 `feat/主题`。
3. 提交 PR 到 `main`,正文使用 PR 模板,**必须包含 `Fixes #N`**。
4. 等待 CI 四个必需检查变绿:`engine-tests`、`engine-js-tests`、`engine-py-tests`、`android-build`(见 `.github/workflows/ci.yml`)。
5. CI 绿后由维护者合并;合并后 Issue 自动关闭。CI 红时不合并、不提前关 Issue。

## 约定

- 提交信息说清"为什么",而不只是"改了什么"。
- 不提交机器本地文件与密钥:`local.properties`、任何 `*.jks` / `*.p12` 密钥库、IDE 缓存、构建产物。
- 引擎(`engine/lx.c`)改动必须保证 `make -C engine test` 全绿;新增能力请顺手补一个 t 系列测试。
- `:app` 与 `:runtime` 各有一份 `EngineHost`:app 版带调试 API,runtime 版没有——不要把调试调用跨模块拷贝。
- **无 root 政策不可违反**:不引入 su / Magisk / 设备 root 依赖;proot 仅限非特权用户态。

## 报告 Bug

开 Issue 时请附:设备型号与 Android 版本、复现用的 Lua 脚本、终端或日志面板的完整输出、期望行为与实际行为。
