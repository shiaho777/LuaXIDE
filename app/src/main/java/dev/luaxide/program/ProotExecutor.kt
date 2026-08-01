package dev.luaxide.program

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProotExecutor(
    private val install: ProotRootfs.Install,
    private val sandbox: NoRootRuntime.Sandbox,
) {
    data class Result(
        val ok: Boolean,
        val output: String,
        val exitCode: Int,
        val mode: String,
        val cancelled: Boolean = false,
    )

    private val processRef = AtomicReference<Process?>(null)

    fun cancel() {
        processRef.getAndSet(null)?.destroyForcibly()
    }

    fun runShell(script: String, timeoutMs: Long = 60_000L): Result {
        val work = sandbox.work.apply { mkdirs() }
        val tmp = sandbox.tmp.apply { mkdirs() }
        sandbox.home.mkdirs()
        val scriptFile = File(work, ".luax-cmd.sh")
        scriptFile.writeText("#!/system/bin/sh\nset +e\n$script\n")
        runCatching {
            scriptFile.setReadable(true, false)
            scriptFile.setExecutable(true, false)
        }

        val proot = install.prootBin
        val env = LinkedHashMap<String, String>()
        System.getenv()?.let { env.putAll(it) }
        env.putAll(NoRootRuntime.env(sandbox))
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        env["HOME"] = sandbox.home.absolutePath
        env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/bin:/usr/bin"
        env["LUAX_NO_ROOT"] = "1"
        env["PROOT_NO_SECCOMP"] = "1"

        if (proot == null || !proot.isFile) {
            val cmd = listOf("/system/bin/sh", scriptFile.absolutePath)
            return execSafe(cmd, work, env, timeoutMs, "app-sandbox")
        }

        val mode = "proot-userland"
        val libPaths = linkedSetOf<String>()
        install.nativeLibDir?.absolutePath?.let { libPaths += it }
        install.libDir?.absolutePath?.let { libPaths += it }
        File(install.root, "lib").takeIf { it.isDirectory }?.absolutePath?.let { libPaths += it }
        if (libPaths.isNotEmpty()) {
            val prev = env["LD_LIBRARY_PATH"]
            val joined = libPaths.joinToString(":")
            env["LD_LIBRARY_PATH"] = if (prev.isNullOrBlank()) joined else "$joined:$prev"
        }

        val loader = ProotRootfs.resolveLoader(install)
        if (loader != null) {
            if (!loader.absolutePath.contains("/lib/")) {
                ProotRootfs.makeExecOnly(loader)
            }
            env["PROOT_LOADER"] = loader.absolutePath
        }
        val loader32 = ProotRootfs.resolveLoader32(install)
        if (loader32 != null) {
            if (!loader32.absolutePath.contains("/lib/")) {
                ProotRootfs.makeExecOnly(loader32)
            }
            env["PROOT_LOADER32"] = loader32.absolutePath
        }

        if (!proot.absolutePath.contains(File.separator + "lib" + File.separator) &&
            !proot.name.endsWith(".so")
        ) {
            ProotRootfs.makeExecOnly(proot)
        }

        val prootArgs = mutableListOf(
            "--link2symlink",
            "--kill-on-exit",
            "-0",
            "-r", install.root.absolutePath,
        )
        for (hostPath in listOf("/system", "/apex", "/vendor", "/dev", "/proc", "/sys")) {
            if (File(hostPath).exists()) {
                prootArgs += listOf("-b", hostPath)
            }
        }
        prootArgs += listOf(
            "-b", "${work.absolutePath}:/work",
            "-b", "${sandbox.home.absolutePath}:/home/luax",
            "-b", "${tmp.absolutePath}:/tmp",
            "-w", "/work",
            "/system/bin/sh",
            "-c",
            "cd /work 2>/dev/null || true; . /work/.luax-cmd.sh",
        )

        val attempts = buildLaunchAttempts(proot, prootArgs, env)
        var last: Result? = null
        for (cmd in attempts) {
            val result = execSafe(cmd, work, env, timeoutMs, mode)
            last = result
            if (result.ok) return result
            val out = result.output
            val retriable = out.contains("Permission denied", true) ||
                out.contains("error=13") ||
                out.contains("not found", true) ||
                result.exitCode == -1
            if (!retriable) return result
        }
        return last ?: Result(false, "proot launch failed", -1, mode)
    }

    private fun buildLaunchAttempts(
        proot: File,
        prootArgs: List<String>,
        env: Map<String, String>,
    ): List<List<String>> {
        val out = ArrayList<List<String>>(4)
        val linker = systemLinker()
        if (linker != null) {
            out += listOf(linker, proot.absolutePath) + prootArgs
            out += wrapWithEnv(env, listOf(linker, proot.absolutePath) + prootArgs)
        }
        out += listOf(proot.absolutePath) + prootArgs
        out += wrapWithEnv(env, listOf(proot.absolutePath) + prootArgs)
        return out
    }

    private fun systemLinker(): String? {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val prefer64 = abis.any { it.contains("64") }
        val candidates = if (prefer64) {
            listOf("/system/bin/linker64", "/system/bin/linker")
        } else {
            listOf("/system/bin/linker", "/system/bin/linker64")
        }
        return candidates.firstOrNull { File(it).exists() }
    }

    private fun wrapWithEnv(env: Map<String, String>, args: List<String>): List<String> {
        val export = buildString {
            append("export PROOT_TMP_DIR='${esc(env["PROOT_TMP_DIR"].orEmpty())}'; ")
            append("export TMPDIR='${esc(env["TMPDIR"].orEmpty())}'; ")
            append("export HOME='${esc(env["HOME"].orEmpty())}'; ")
            append("export PATH='${esc(env["PATH"].orEmpty())}'; ")
            append("export LUAX_NO_ROOT=1; ")
            append("export PROOT_NO_SECCOMP=1; ")
            val ld = env["LD_LIBRARY_PATH"]
            if (!ld.isNullOrBlank()) append("export LD_LIBRARY_PATH='${esc(ld)}'; ")
            val loader = env["PROOT_LOADER"]
            if (!loader.isNullOrBlank()) append("export PROOT_LOADER='${esc(loader)}'; ")
            val loader32 = env["PROOT_LOADER32"]
            if (!loader32.isNullOrBlank()) append("export PROOT_LOADER32='${esc(loader32)}'; ")
            append("exec \"\$@\"")
        }
        return buildList {
            add("/system/bin/sh")
            add("-c")
            add(export)
            add("luax-proot")
            addAll(args)
        }
    }

    private fun esc(s: String): String = s.replace("'", "'\\''")

    private fun execSafe(
        cmd: List<String>,
        work: File,
        env: Map<String, String>,
        timeoutMs: Long,
        mode: String,
    ): Result {
        return try {
            exec(cmd, work, env, timeoutMs, mode)
        } catch (t: Throwable) {
            Result(
                false,
                (t.message ?: "proot exec failed") + "\ncmd=${cmd.joinToString(" ")}",
                -1,
                mode,
            )
        }
    }

    private fun exec(
        cmd: List<String>,
        work: File,
        env: Map<String, String>,
        timeoutMs: Long,
        mode: String,
    ): Result {
        val pb = ProcessBuilder(cmd)
            .directory(work)
            .redirectErrorStream(true)
        val pe = pb.environment()
        pe.clear()
        pe.putAll(env)
        val proc = pb.start()
        processRef.set(proc)
        val out = StringBuilder()
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    out.append(line).append('\n')
                    if (processRef.get() == null) break
                }
            }
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                processRef.set(null)
                return Result(false, out.toString() + "\n[timeout]", 124, mode, cancelled = true)
            }
            val code = proc.exitValue()
            processRef.set(null)
            return Result(code == 0, out.toString(), code, mode, cancelled = code == 143 || code == 137)
        } catch (t: Throwable) {
            processRef.set(null)
            proc.destroyForcibly()
            throw t
        }
    }

    fun selfTest(): Result {
        return runShell(
            """
            echo "proot-selftest"
            echo "uid=$(id -u 2>/dev/null || echo n/a)"
            echo "pwd=$(pwd)"
            echo "uname=$(uname -a 2>/dev/null || echo n/a)"
            echo "rootfs-ok"
            """.trimIndent(),
            timeoutMs = 20_000L,
        )
    }
}
