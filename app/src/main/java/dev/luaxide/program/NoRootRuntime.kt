package dev.luaxide.program

import android.content.Context
import java.io.File

object NoRootRuntime {
    const val POLICY = "no-root"

    data class Sandbox(
        val root: File,
        val home: File,
        val tmp: File,
        val work: File,
        val mode: String = "app-sandbox",
        val rootfs: File? = null,
        val proot: File? = null,
        val note: String = "",
    )

    fun sandbox(context: Context, projectId: String): Sandbox {
        val root = File(context.filesDir, "sandbox/$projectId").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val tmp = File(root, "tmp").apply { mkdirs() }
        val work = File(root, "work").apply { mkdirs() }
        val prootInstall = runCatching { ProotRootfs.ensure(context) }.getOrNull()
        val mode = when {
            prootInstall?.prootBin != null -> "proot-userland"
            prootInstall != null -> "rootfs-layout"
            else -> "app-sandbox"
        }
        return Sandbox(
            root = root,
            home = home,
            tmp = tmp,
            work = work,
            mode = mode,
            rootfs = prootInstall?.root,
            proot = prootInstall?.prootBin,
            note = prootInstall?.note.orEmpty(),
        )
    }

    fun ensureLayout(sandbox: Sandbox) {
        sandbox.home.mkdirs()
        sandbox.tmp.mkdirs()
        sandbox.work.mkdirs()
        File(sandbox.root, "README.txt").writeText(
            """
            LuaXIDE no-root sandbox
            -----------------------
            mode: ${sandbox.mode}
            policy: $POLICY
            home: ${sandbox.home.absolutePath}
            tmp:  ${sandbox.tmp.absolutePath}
            work: ${sandbox.work.absolutePath}
            rootfs: ${sandbox.rootfs?.absolutePath ?: "(none)"}
            proot: ${sandbox.proot?.absolutePath ?: "(not bundled)"}
            ${sandbox.note}

            No su / magisk / device root required.
            Proot (when present) is unprivileged userland only.
            """.trimIndent() + "\n",
        )
    }

    fun env(sandbox: Sandbox): Map<String, String> = buildMap {
        put("HOME", sandbox.home.absolutePath)
        put("TMPDIR", sandbox.tmp.absolutePath)
        put("PWD", sandbox.work.absolutePath)
        put("LUAX_SANDBOX", sandbox.root.absolutePath)
        put("LUAX_NO_ROOT", "1")
        put("LUAX_PROOT", if (sandbox.proot != null) "1" else "0")
        sandbox.rootfs?.let { put("LUAX_ROOTFS", it.absolutePath) }
    }

    fun describe(sandbox: Sandbox): String {
        val proot = if (sandbox.proot != null) "proot=on" else "proot=off"
        return "sandbox=${sandbox.mode} root=${sandbox.root.name} no-root=1 $proot"
    }
}
