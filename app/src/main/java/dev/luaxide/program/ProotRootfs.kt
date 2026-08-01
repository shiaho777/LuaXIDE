package dev.luaxide.program

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ProotRootfs {
    private const val ASSET_ROOT = "proot"
    private const val ASSET_ROOTFS_ZIP = "proot/min-rootfs.zip"
    private const val MARKER = ".bundle-v4"
    private const val BUNDLE_ID = "proot-5.1.107.84-jni"

    data class Install(
        val root: File,
        val prootBin: File?,
        val libDir: File?,
        val loaderDir: File?,
        val mode: String,
        val ready: Boolean,
        val note: String,
        val abi: String,
        val nativeLibDir: File? = null,
    )

    fun installDir(context: Context): File =
        File(context.filesDir, "proot-rootfs").apply { mkdirs() }

    fun ensure(context: Context): Install {
        val root = installDir(context)
        val abi = abi()
        val marker = File(root, MARKER)
        val want = "$BUNDLE_ID|$abi"
        if (!marker.exists() || marker.readText().trim() != want) {
            runCatching { root.deleteRecursively() }
            root.mkdirs()
            installBundle(context, root, abi)
            marker.writeText(want)
        }
        ensureLayoutDirs(root)
        extractRootfsZipIfPresent(context, root)
        materializeLibs(root)
        syncNativeLibsIntoRoot(context, root)
        fixPermissions(root)

        val nativeDir = nativeLibDir(context)
        val prootNative = nativeDir?.let { File(it, "libluaxproot.so") }?.takeIf { it.isFile && it.length() > 1024L }
        val prootFiles = File(root, "bin/proot").takeIf { it.isFile && it.length() > 0 }
        val proot = prootNative ?: prootFiles
        val libDir = when {
            nativeDir != null && File(nativeDir, "libtalloc.so").isFile -> nativeDir
            File(root, "lib").isDirectory -> File(root, "lib")
            else -> null
        }
        val loaderNative = nativeDir?.let { File(it, "libluaxloader.so") }?.takeIf { it.isFile }
        val loaderDir = when {
            loaderNative != null -> nativeDir
            File(root, "libexec/proot").isDirectory -> File(root, "libexec/proot")
            else -> null
        }
        val mode = if (proot != null) "proot-userland" else "rootfs-layout"
        val note = when {
            prootNative != null -> "proot ready · jni · $abi"
            prootFiles != null -> "proot ready · files · $abi"
            else -> "rootfs layout only · proot missing for $abi"
        }
        return Install(
            root = root,
            prootBin = proot,
            libDir = libDir,
            loaderDir = loaderDir,
            mode = mode,
            ready = proot != null,
            note = note,
            abi = abi,
            nativeLibDir = nativeDir,
        )
    }

    fun forceReinstall(context: Context): Install {
        val root = installDir(context)
        runCatching { root.deleteRecursively() }
        root.mkdirs()
        listOf(".bundle-v2", ".bundle-v3", MARKER).forEach { File(root, it).delete() }
        return ensure(context)
    }

    fun nativeLibDir(context: Context): File? {
        val path = context.applicationInfo.nativeLibraryDir ?: return null
        val dir = File(path)
        return dir.takeIf { it.isDirectory }
    }

    fun resolveLoader(install: Install): File? {
        val native = install.nativeLibDir
        if (native != null) {
            val a = File(native, "libluaxloader.so")
            if (a.isFile) return a
        }
        val dir = install.loaderDir ?: return null
        val direct = File(dir, "loader")
        if (direct.isFile) return direct
        val jniName = File(dir, "libluaxloader.so")
        if (jniName.isFile) return jniName
        return null
    }

    fun resolveLoader32(install: Install): File? {
        val native = install.nativeLibDir
        if (native != null) {
            val a = File(native, "libluaxloader32.so")
            if (a.isFile) return a
        }
        val dir = install.loaderDir ?: return null
        val direct = File(dir, "loader32")
        if (direct.isFile) return direct
        val jniName = File(dir, "libluaxloader32.so")
        if (jniName.isFile) return jniName
        return null
    }

    fun abi(): String {
        val supported = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val preferred = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return preferred.firstOrNull { it in supported }
            ?: supported.firstOrNull()
            ?: Build.CPU_ABI
    }

    private fun syncNativeLibsIntoRoot(context: Context, root: File) {
        val native = nativeLibDir(context) ?: return
        val bin = File(root, "bin").apply { mkdirs() }
        val lib = File(root, "lib").apply { mkdirs() }
        val loaderDir = File(root, "libexec/proot").apply { mkdirs() }
        copyIfPresent(File(native, "libluaxproot.so"), File(bin, "proot"))
        copyIfPresent(File(native, "libluaxloader.so"), File(loaderDir, "loader"))
        copyIfPresent(File(native, "libluaxloader32.so"), File(loaderDir, "loader32"))
        copyIfPresent(File(native, "libtalloc.so"), File(lib, "libtalloc.so"))
        copyIfPresent(File(native, "libtalloc.so.2"), File(lib, "libtalloc.so.2"))
        copyIfPresent(File(native, "libtalloc.so"), File(lib, "libtalloc.so.2"))
        copyIfPresent(File(native, "libandroid-shmem.so"), File(lib, "libandroid-shmem.so"))
    }

    private fun copyIfPresent(from: File, to: File) {
        if (!from.isFile) return
        if (to.exists() && to.length() == from.length()) return
        runCatching {
            to.parentFile?.mkdirs()
            from.copyTo(to, overwrite = true)
        }
    }

    private fun ensureLayoutDirs(root: File) {
        listOf(
            "bin", "etc", "tmp", "home/luax", "usr/bin", "proc", "dev", "sys", "work", "lib", "libexec/proot",
        ).forEach { File(root, it).mkdirs() }
        File(root, "etc/passwd").takeIf { !it.exists() }?.writeText(
            "root:x:0:0:root:/root:/system/bin/sh\nluax:x:1000:1000:luax:/home/luax:/system/bin/sh\n",
        )
        File(root, "etc/group").takeIf { !it.exists() }?.writeText("root:x:0:\nluax:x:1000:\n")
        File(root, "etc/hosts").takeIf { !it.exists() }?.writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/os-release").takeIf { !it.exists() }?.writeText(
            "NAME=\"LuaX Userland\"\nID=luax\nVERSION_ID=1\nPRETTY_NAME=\"LuaX no-root rootfs\"\n",
        )
        File(root, "home/luax/.profile").takeIf { !it.exists() }?.writeText(
            "export HOME=/home/luax\nexport TMPDIR=/tmp\nexport PATH=/bin:/usr/bin:/system/bin:/system/xbin\nexport LUAX_NO_ROOT=1\n",
        )
        val sh = File(root, "bin/sh")
        if (!sh.exists() || sh.length() == 0L) {
            sh.writeText("#!/system/bin/sh\nexec /system/bin/sh \"\$@\"\n")
        }
        File(root, "README.txt").writeText(
            "LuaXIDE proot userland rootfs\npath=${root.absolutePath}\nno-root=1\n",
        )
    }

    private fun installBundle(context: Context, root: File, abi: String) {
        val am = context.assets
        val prefix = "$ASSET_ROOT/$abi"
        val names = runCatching { am.list(prefix)?.toList().orEmpty() }.getOrDefault(emptyList())
        if (names.isNotEmpty()) {
            copyAssetTree(context, prefix, root)
        }
        val flatCandidates = listOf("$ASSET_ROOT/proot-$abi", "$ASSET_ROOT/proot")
        for (n in flatCandidates) {
            runCatching {
                am.open(n).use { input ->
                    val out = File(root, "bin/proot")
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { input.copyTo(it) }
                }
            }.onSuccess { return }
        }
    }

    private fun copyAssetTree(context: Context, assetPath: String, destDir: File) {
        val am = context.assets
        val children = am.list(assetPath) ?: return
        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                FileOutputStream(destDir).use { input.copyTo(it) }
            }
            return
        }
        destDir.mkdirs()
        for (child in children) {
            copyAssetTree(context, "$assetPath/$child", File(destDir, child))
        }
    }

    private fun extractRootfsZipIfPresent(context: Context, root: File) {
        runCatching {
            context.assets.open(ASSET_ROOTFS_ZIP).use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.trimStart('/')
                        if (name.isNotEmpty() && !name.contains("..")) {
                            val out = File(root, name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else if (!out.exists() || out.length() == 0L) {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
    }

    private fun materializeLibs(root: File) {
        val lib = File(root, "lib")
        if (!lib.isDirectory) return
        val candidates = listOf(
            "libtalloc.so.2.4.3",
            "libtalloc.so.2",
            "libtalloc.so",
        ).map { File(lib, it) }
        val real = candidates.firstOrNull { it.isFile && it.length() > 1024 } ?: return
        for (name in listOf("libtalloc.so", "libtalloc.so.2", "libtalloc.so.2.4.3")) {
            val f = File(lib, name)
            if (f.absolutePath == real.absolutePath) continue
            if (!f.exists() || f.length() < 1024L) {
                runCatching {
                    if (f.exists()) f.delete()
                    real.copyTo(f, overwrite = true)
                }
            }
        }
    }

    private fun fixPermissions(root: File) {
        File(root, "bin").listFiles()?.forEach { f ->
            if (f.isFile) makeExecOnly(f)
        }
        File(root, "libexec/proot").listFiles()?.forEach { f ->
            if (f.isFile) makeExecOnly(f)
        }
        File(root, "lib").listFiles()?.forEach { f ->
            if (f.isFile) makeLibReadable(f)
        }
    }

    fun makeExecOnly(file: File) {
        if (!file.isFile) return
        runCatching {
            file.setReadable(true, false)
            file.setWritable(true, true)
            file.setExecutable(true, false)
        }
        runCatching { Os.chmod(file.absolutePath, 0b101_101_101) }
        runCatching {
            file.setWritable(false, false)
            file.setReadable(true, false)
            file.setExecutable(true, false)
        }
        runCatching { Os.chmod(file.absolutePath, 0b101_101_101) }
    }

    private fun makeLibReadable(file: File) {
        runCatching {
            file.setReadable(true, false)
            file.setWritable(false, false)
            file.setExecutable(true, false)
        }
        runCatching { Os.chmod(file.absolutePath, 0b101_101_101) }
    }
}
