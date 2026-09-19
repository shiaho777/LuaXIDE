package dev.luaxide.build

import android.content.Context
import dev.luaxide.engine.EngineHost
import dev.luaxide.engine.JsEngineHost
import dev.luaxide.engine.PyEngineHost
import dev.luaxide.log.LogLevel
import dev.luaxide.log.LogSink
import dev.luaxide.log.LogSource
import dev.luaxide.project.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuildPipeline(
    private val context: Context,
    private val repo: ProjectRepository,
    private val logSink: LogSink,
) {
    private val stageNames = listOf(
        "validate",
        "bundle lua",
        "package apk",
        "sign",
        "finalize",
    )

    private val _progress = MutableStateFlow(BuildProgress())
    val progress: StateFlow<BuildProgress> = _progress.asStateFlow()

    private fun initStages() {
        _progress.value = BuildProgress(
            stages = stageNames.map { BuildStage(it) },
            running = true,
        )
    }

    private fun setStage(index: Int, status: StageStatus, detail: String = "") {
        val stages = _progress.value.stages.toMutableList()
        stages[index] = stages[index].copy(status = status, detail = detail)
        _progress.value = _progress.value.copy(stages = stages)
    }

    suspend fun build(projectId: String, config: BuildConfig): BuildArtifact =
        withContext(Dispatchers.IO) {
            initStages()
            log(LogLevel.INFO, "build started: ${config.appName} ${config.versionName} (${config.versionCode})")

            try {
                setStage(0, StageStatus.RUNNING)
                val src = repo.readFile(projectId, config.entryFile)
                if (src.isBlank()) fail(0, "entry file '${config.entryFile}' is empty or missing")
                validatePackageName(config.packageName)
                if (config.versionName.isBlank()) fail(0, "version name is empty")
                if (config.versionCode < 1) fail(0, "version code must be >= 1")
                if (config.appName.isBlank()) fail(0, "app name is empty")
                if (config.abis.isEmpty()) fail(0, "select at least one ABI")
                validateEntry(src, repo.srcDirOf(projectId), config.entryFile)
                heartbeat()
                setStage(0, StageStatus.DONE, "ok")

                setStage(1, StageStatus.RUNNING)
                val srcDir = repo.srcDirOf(projectId)
                if (!srcDir.isDirectory) fail(1, "src/ missing")
                val files = srcDir.walkTopDown().filter { it.isFile }.toList()
                if (files.isEmpty()) fail(1, "no source files under src/")
                log(LogLevel.INFO, "bundling ${files.size} file(s)")
                heartbeat()
                setStage(1, StageStatus.DONE, "${files.size} files")

                setStage(2, StageStatus.RUNNING)
                currentCoroutineContext().ensureActive()
                val buildId = UUID.randomUUID().toString().take(8)
                val buildsDir = File(repo.projectDirOf(projectId), "builds/$buildId").apply { mkdirs() }
                val baseName = "${sanitize(config.appName)}-${config.versionName}"
                val pkgTarget = File(buildsDir, "$baseName.luaxpkg")
                writeBundle(pkgTarget, srcDir, files, config)
                log(LogLevel.INFO, "bundle ${pkgTarget.name} (${humanSize(pkgTarget.length())})")

                val apkTarget = File(buildsDir, "$baseName.apk")
                val ksMgr = KeystoreManager(context)
                val identity = resolveIdentity(ksMgr, config)
                val packager = ApkPackager(context)

                var kind: ArtifactKind = ArtifactKind.APK_DEBUG
                try {
                    kind = packager.packageApk(
                        config = config,
                        srcDir = srcDir,
                        files = files,
                        outFile = apkTarget,
                        identity = identity,
                        projectDir = repo.projectDirOf(projectId),
                        onLog = { log(LogLevel.INFO, it) },
                        onPhase = { phase, detail ->
                            when (phase) {
                                "package" -> {
                                    if (detail == "done") {
                                        setStage(2, StageStatus.DONE, humanSize(pkgTarget.length()))
                                    } else {
                                        setStage(2, StageStatus.RUNNING, detail)
                                    }
                                }
                                "sign" -> {
                                    if (_progress.value.stages.getOrNull(2)?.status != StageStatus.DONE) {
                                        setStage(2, StageStatus.DONE, humanSize(pkgTarget.length()))
                                    }
                                    if (detail == "done") {
                                        setStage(3, StageStatus.DONE, humanSize(apkTarget.length()))
                                    } else {
                                        setStage(3, StageStatus.RUNNING, detail)
                                    }
                                }
                            }
                        },
                    )
                } catch (e: Exception) {
                    val running = _progress.value.stages.indexOfFirst { it.status == StageStatus.RUNNING }
                    val stage = if (running >= 0) running else 2
                    val msg = e.message ?: "package failed"
                    val friendly = when {
                        msg.contains("Wrong version of key store", ignoreCase = true) ->
                            "signing keystore incompatible · rebuilt debug key may be required"
                        msg.contains("template", ignoreCase = true) ->
                            "runtime template missing · rebuild app"
                        msg.contains("No space", ignoreCase = true) ->
                            "not enough storage to package apk"
                        else -> msg
                    }
                    fail(stage, friendly)
                }
                if (_progress.value.stages.getOrNull(2)?.status != StageStatus.DONE) {
                    setStage(2, StageStatus.DONE, humanSize(pkgTarget.length()))
                }
                if (_progress.value.stages.getOrNull(3)?.status != StageStatus.DONE) {
                    setStage(3, StageStatus.DONE, humanSize(apkTarget.length()))
                }

                setStage(4, StageStatus.RUNNING)
                if (!apkTarget.isFile || apkTarget.length() < 1024L) {
                    fail(4, "apk artifact missing")
                }
                val artifact = BuildArtifact(
                    id = buildId,
                    appName = config.appName,
                    versionName = config.versionName,
                    versionCode = config.versionCode,
                    kind = kind,
                    sizeBytes = apkTarget.length(),
                    createdAt = System.currentTimeMillis(),
                    file = apkTarget,
                )
                writeMeta(buildsDir, artifact)
                setStage(4, StageStatus.DONE, humanSize(apkTarget.length()))

                log(LogLevel.INFO, "build finished: ${apkTarget.name} (${humanSize(apkTarget.length())})")
                _progress.value = _progress.value.copy(running = false, artifact = artifact)
                artifact
            } catch (ce: CancellationException) {
                markRunningFailed("cancelled")
                log(LogLevel.WARNING, "build cancelled")
                throw ce
            } catch (e: BuildException) {
                markRunningFailed(e.message ?: "failed")
                log(LogLevel.ERROR, "build failed: ${e.message}")
                _progress.value = _progress.value.copy(running = false, error = e.message)
                throw e
            } catch (e: Exception) {
                markRunningFailed(e.message ?: "error")
                log(LogLevel.ERROR, "build error: ${e.message}")
                _progress.value = _progress.value.copy(running = false, error = e.message)
                throw e
            }
        }

    private fun resolveIdentity(ksMgr: KeystoreManager, config: BuildConfig): SigningIdentity {
        return if (config.signMode == SignMode.RELEASE) {
            val id = config.releaseKeystoreId
                ?: fail(3, "release signing selected but no keystore chosen")
            runCatching { ksMgr.getIdentity(id) }
                .getOrElse { fail(3, "release keystore: ${it.message}") }
        } else {
            runCatching { ksMgr.debugIdentity() }
                .getOrElse {
                    fail(
                        3,
                        "debug signing key unavailable: ${it.message}. " +
                            "Clear app data or reinstall, then rebuild.",
                    )
                }
        }
    }

    private fun validatePackageName(pkg: String) {
        val ok = pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
        if (!ok) fail(0, "invalid package name: $pkg")
    }

    /** Pre-package smoke run: the entry's language picks the engine (PLATFORM_ABI). */
    private suspend fun validateEntry(src: String, srcDir: File, entryFile: String) {
        if (entryFile.endsWith(".py")) {
            val engine = PyEngineHost()
            try {
                if (srcDir.isDirectory) engine.setModuleRoot(srcDir.absolutePath)
                val result = engine.run(src)
                if (!result.ok) {
                    val msg = result.error ?: "validation failed"
                    fail(0, "py validate failed: $msg")
                }
            } catch (e: BuildException) {
                throw e
            } catch (e: Exception) {
                fail(0, "py validate error: ${e.message}")
            } finally {
                engine.close()
            }
            return
        }
        if (entryFile.endsWith(".js")) {
            val engine = JsEngineHost()
            try {
                val result = engine.run(src)
                if (!result.ok) {
                    val msg = result.error ?: "validation failed"
                    fail(0, "js validate failed: $msg")
                }
            } catch (e: BuildException) {
                throw e
            } catch (e: Exception) {
                fail(0, "js validate error: ${e.message}")
            } finally {
                engine.close()
            }
            return
        }
        val engine = EngineHost()
        try {
            if (srcDir.isDirectory) engine.setModuleRoot(srcDir.absolutePath)
            val result = engine.run(src)
            if (!result.ok) {
                val msg = result.error ?: "validation failed"
                fail(0, "lua validate failed: $msg")
            }
        } catch (e: BuildException) {
            throw e
        } catch (e: Exception) {
            fail(0, "lua validate error: ${e.message}")
        } finally {
            engine.close()
        }
    }

    private fun writeBundle(target: File, srcDir: File, files: List<File>, config: BuildConfig) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        ZipOutputStream(FileOutputStream(tmp)).use { zip ->
            zip.putNextEntry(ZipEntry("luaxpkg.json"))
            zip.write(configJson(config).toByteArray())
            zip.closeEntry()
            files.forEach { f ->
                val rel = "src/" + f.toRelativeString(srcDir).replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(rel))
                zip.write(f.readBytes())
                zip.closeEntry()
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun configJson(c: BuildConfig): String = org.json.JSONObject()
        .put("appName", c.appName)
        .put("packageName", c.packageName)
        .put("versionName", c.versionName)
        .put("versionCode", c.versionCode)
        .put("entryFile", c.entryFile)
        .put("orientation", c.orientation.name)
        .put("abis", org.json.JSONArray(c.abis.toList()))
        .put("aot", c.aot)
        .toString(2)

    private fun writeMeta(dir: File, a: BuildArtifact) {
        val o = org.json.JSONObject()
            .put("id", a.id)
            .put("appName", a.appName)
            .put("versionName", a.versionName)
            .put("versionCode", a.versionCode)
            .put("kind", a.kind.name)
            .put("sizeBytes", a.sizeBytes)
            .put("createdAt", a.createdAt)
            .put("fileName", a.file.name)
        val meta = File(dir, "artifact.json")
        val tmp = File(dir, ".artifact.json.tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(meta)) {
            meta.writeText(o.toString(2))
            tmp.delete()
        }
    }

    private suspend fun heartbeat() {
        currentCoroutineContext().ensureActive()
        delay(80)
    }

    private fun markRunningFailed(detail: String) {
        val stages = _progress.value.stages.toMutableList()
        val idx = stages.indexOfFirst { it.status == StageStatus.RUNNING }
        if (idx >= 0) stages[idx] = stages[idx].copy(status = StageStatus.FAILED, detail = detail)
        _progress.value = _progress.value.copy(stages = stages, running = false)
    }

    private fun log(level: LogLevel, message: String) =
        logSink.log(level, LogSource.SYSTEM, message, tag = "build")

    private fun fail(stage: Int, message: String): Nothing {
        setStage(stage, StageStatus.FAILED, message)
        throw BuildException(message)
    }

    private class BuildException(message: String) : Exception(message)

    private fun sanitize(s: String) = s.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
