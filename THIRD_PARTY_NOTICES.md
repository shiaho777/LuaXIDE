# 第三方组件声明(Third-Party Notices)

LuaXIDE 的引擎(`engine/lx.c`)与全部 Kotlin/Java 源码为原创。除此之外,项目构建与分发链路包含以下第三方组件。

| 组件 | 版本 | 许可(以实际构建来源声明为准) | 来源 / 随仓库位置 |
|---|---|---|---|
| ARSCLib | 1.4.0 | Apache-2.0 | [reandroid/ARSCLib](https://github.com/reandroid/ARSCLib),打包于 `app/libs/ARSCLib-1.4.0.jar` |
| apksig | 随 AGP 8.7.3 | Apache-2.0 | [tools/apksig](https://android.googlesource.com/platform/tools/apksig/),Gradle 依赖 `com.android.tools.build:apksig` |
| proot(Termux 构建) | 5.1.107.84 | GPL-2.0(Termux 包元数据) | [termux-packages/packages/proot](https://github.com/termux/termux-packages/tree/master/packages/proot),随 APK 分发于 `app/src/main/assets/proot/` |
| libtalloc(Termux 构建) | 2.4.3 | GPL-3.0(Termux 包元数据) | [termux-packages/packages/libtalloc](https://github.com/termux/termux-packages/tree/master/packages/libtalloc),同上及 `app/src/main/jniLibs/` |
| libandroid-shmem(Termux 构建) | 0.7 | BSD-3-Clause | [termux/libandroid-shmem](https://github.com/termux/libandroid-shmem),随 APK 分发于 `assets/proot/` 与 `jniLibs/` |
| AndroidX / Jetpack Compose / Kotlin / AGP | 见 `gradle/libs.versions.toml` | Apache-2.0 | [developer.android.com](https://developer.android.com/jetpack) |

各组件的完整许可文本以上游仓库为准;上表"版本"为当前打包进仓库的构建版本,升级后请同步更新本文件。

## 分发义务说明

- `assets/proot/` 与 `jniLibs/` 中的二进制会随每个打包产出的 APK 分发。其中 GPL/LGPL 组件要求随分发提供许可文本与获取对应源码的途径——发布正式版(release 渠道)前,请在应用内"关于/开源许可"页面或发行说明中附上上表链接与许可文本。
- 本项目整体以 [Apache-2.0](LICENSE) 许可发布;外部再分发请一并保留本声明。
