# Third-Party Notices

[简体中文](THIRD_PARTY_NOTICES.zh-CN.md)

LuaXIDE's engine (`engine/lx.c`) and all Kotlin/Java sources are original work. Beyond that, the project's build and distribution chain contains the following third-party components.

| Component | Version | License (per actual build source) | Origin / in-repo location |
|---|---|---|---|
| quickjs-ng | 0.10.1 | MIT | [quickjs-ng/quickjs](https://github.com/quickjs-ng/quickjs), vendored at `engine-js/quickjs/`, compiled into `libluaxjs.so` |
| ARSCLib | 1.4.0 | Apache-2.0 | [reandroid/ARSCLib](https://github.com/reandroid/ARSCLib), packaged as `app/libs/ARSCLib-1.4.0.jar` |
| apksig | ships with AGP 8.7.3 | Apache-2.0 | [tools/apksig](https://android.googlesource.com/platform/tools/apksig/), Gradle dependency `com.android.tools.build:apksig` |
| proot (Termux build) | 5.1.107.84 | GPL-2.0 (Termux package metadata) | [termux-packages/packages/proot](https://github.com/termux/termux-packages/tree/master/packages/proot), distributed with the APK at `app/src/main/assets/proot/` |
| libtalloc (Termux build) | 2.4.3 | GPL-3.0 (Termux package metadata) | [termux-packages/packages/libtalloc](https://github.com/termux/termux-packages/tree/master/packages/libtalloc), same as above plus `app/src/main/jniLibs/` |
| libandroid-shmem (Termux build) | 0.7 | BSD-3-Clause | [termux/libandroid-shmem](https://github.com/termux/libandroid-shmem), distributed with the APK at `assets/proot/` and `jniLibs/` |
| AndroidX / Jetpack Compose / Kotlin / AGP | see `gradle/libs.versions.toml` | Apache-2.0 | [developer.android.com](https://developer.android.com/jetpack) |

Full license texts are authoritative in each upstream repository; the "Version" column is the build currently packaged into this repo — update this file when upgrading.

## Distribution obligations

- Binaries under `assets/proot/` and `jniLibs/` ship inside every packaged APK. GPL/LGPL components require shipping license texts and a way to obtain the corresponding source — before a formal release (release channel), include the links and license texts from the table above on the in-app "About / Open-source licenses" page or in the release notes.
- The project as a whole is published under [Apache-2.0](LICENSE); external redistribution must retain this notice.
