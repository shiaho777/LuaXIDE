package dev.luaxide.ui.build

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.luaxide.build.ApkInstaller
import dev.luaxide.build.ArtifactKind
import dev.luaxide.build.BuildArtifact
import dev.luaxide.build.BuildConfig
import dev.luaxide.build.BuildConfigStore
import dev.luaxide.build.BuildPipeline
import dev.luaxide.build.BuildProgress
import dev.luaxide.build.BuildRepository
import dev.luaxide.build.IconGenerator
import dev.luaxide.build.KeystoreInfo
import dev.luaxide.build.KeystoreManager
import dev.luaxide.build.SignMode
import dev.luaxide.log.LogSink
import dev.luaxide.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BuildViewModel(app: Application) : AndroidViewModel(app) {

    private val projectRepo = ProjectRepository(app)
    private val configStore = BuildConfigStore(projectRepo)
    private val buildRepo = BuildRepository(projectRepo)
    private val keystoreManager = KeystoreManager(app)

    private var projectId: String? = null
    private var pipeline: BuildPipeline? = null
    private var buildJob: Job? = null
    private var saveJob: Job? = null
    private var previewJob: Job? = null

    private val _config = MutableStateFlow(BuildConfig())
    val config: StateFlow<BuildConfig> = _config.asStateFlow()

    private val _artifacts = MutableStateFlow<List<BuildArtifact>>(emptyList())
    val artifacts: StateFlow<List<BuildArtifact>> = _artifacts.asStateFlow()

    private val _progress = MutableStateFlow(BuildProgress())
    val progress: StateFlow<BuildProgress> = _progress.asStateFlow()

    private val _keystores = MutableStateFlow<List<KeystoreInfo>>(emptyList())
    val keystores: StateFlow<List<KeystoreInfo>> = _keystores.asStateFlow()

    private val _iconPreview = MutableStateFlow<ByteArray?>(null)
    val iconPreview: StateFlow<ByteArray?> = _iconPreview.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() {
        _toast.value = null
    }

    fun bind(projectId: String, projectName: String, entryFile: String, logSink: LogSink) {
        if (this.projectId == projectId && pipeline != null) return
        this.projectId = projectId
        pipeline = BuildPipeline(getApplication(), projectRepo, logSink)
        viewModelScope.launch {
            _config.value = configStore.load(projectId, BuildConfig.forProject(projectName, entryFile))
            _artifacts.value = buildRepo.list(projectId)
            refreshKeystores()
            refreshIconPreview()
        }
        viewModelScope.launch {
            pipeline!!.progress.collect { _progress.value = it }
        }
    }

    fun updateConfig(config: BuildConfig) {
        val prev = _config.value
        _config.value = config
        val id = projectId ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(280)
            configStore.save(id, _config.value)
        }
        val iconChanged = prev.iconPath != config.iconPath ||
            prev.iconBgColor != config.iconBgColor ||
            prev.iconScale != config.iconScale ||
            prev.iconOffsetX != config.iconOffsetX ||
            prev.iconOffsetY != config.iconOffsetY ||
            prev.iconSafeZone != config.iconSafeZone ||
            prev.iconCorner != config.iconCorner ||
            prev.appName != config.appName
        if (iconChanged) {
            previewJob?.cancel()
            previewJob = viewModelScope.launch {
                delay(60)
                refreshIconPreview()
            }
        }
    }

    fun startBuild() {
        val id = projectId ?: return
        val pl = pipeline ?: return
        if (buildJob?.isActive == true) return
        val cfg = _config.value
        if (cfg.signMode == SignMode.RELEASE && cfg.releaseKeystoreId.isNullOrBlank()) {
            _toast.value = "choose a release keystore first"
            return
        }
        buildJob = viewModelScope.launch {
            saveJob?.join()
            runCatching { pl.build(id, _config.value) }
                .onSuccess { art ->
                    _toast.value = "apk ready · ${art.file.name}"
                }
                .onFailure { _toast.value = it.message ?: "build failed" }
            _artifacts.value = buildRepo.list(id)
        }
    }

    fun cancelBuild() {
        buildJob?.cancel()
    }

    fun deleteArtifact(artifact: BuildArtifact) {
        val id = projectId ?: return
        viewModelScope.launch {
            buildRepo.delete(id, artifact.id)
            _artifacts.value = buildRepo.list(id)
        }
    }

    fun shareArtifact(artifact: BuildArtifact) {
        val ctx = getApplication<Application>()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", artifact.file)
        val mime = when (artifact.kind) {
            ArtifactKind.LUAXPKG -> "application/zip"
            else -> "application/vnd.android.package-archive"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "share ${artifact.file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }

    fun installArtifact(artifact: BuildArtifact) {
        val ctx = getApplication<Application>()
        if (!artifact.file.isFile) {
            _toast.value = "apk file missing"
            return
        }
        if (artifact.kind == ArtifactKind.LUAXPKG) {
            _toast.value = "luaxpkg is not installable"
            return
        }
        if (!ApkInstaller.canRequestInstall(ctx)) {
            ApkInstaller.openInstallPermissionSettings(ctx)
            _toast.value = "enable Install unknown apps for LuaXIDE, then tap Install again"
            return
        }
        runCatching {
            ApkInstaller.install(ctx, artifact.file)
            _toast.value = "opening system installer · ${artifact.file.name}"
        }.onFailure {
            _toast.value = it.message ?: "install failed"
        }
    }

    fun setSignMode(mode: SignMode) {
        updateConfig(_config.value.copy(signMode = mode))
    }

    fun selectKeystore(id: String?) {
        updateConfig(_config.value.copy(releaseKeystoreId = id))
    }

    fun setIconBgColor(color: Long) {
        updateConfig(_config.value.copy(iconBgColor = color))
    }

    fun setIconScale(v: Float) {
        updateConfig(_config.value.copy(iconScale = v.coerceIn(0.4f, 1.8f)))
    }

    fun setIconOffsetX(v: Float) {
        updateConfig(_config.value.copy(iconOffsetX = v.coerceIn(-0.3f, 0.3f)))
    }

    fun setIconOffsetY(v: Float) {
        updateConfig(_config.value.copy(iconOffsetY = v.coerceIn(-0.3f, 0.3f)))
    }

    fun setIconSafeZone(v: Float) {
        updateConfig(_config.value.copy(iconSafeZone = v.coerceIn(0.4f, 0.9f)))
    }

    fun setIconCorner(v: Float) {
        updateConfig(_config.value.copy(iconCorner = v.coerceIn(0.05f, 0.5f)))
    }

    fun resetIconTransform() {
        updateConfig(
            _config.value.copy(
                iconScale = 1.0f,
                iconOffsetX = 0f,
                iconOffsetY = 0f,
                iconSafeZone = 0.66f,
                iconCorner = 0.22f,
            ),
        )
    }

    fun clearIcon() {
        val id = projectId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(projectRepo.projectDirOf(id), "icon.png").delete()
            }
            updateConfig(_config.value.copy(iconPath = null))
        }
    }

    fun importIconFromUri(uri: Uri) {
        val id = projectId ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val dest = File(projectRepo.projectDirOf(id), "icon.png")
                    val tmp = File(dest.parentFile, ".icon.png.tmp")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmp).use { out -> input.copyTo(out) }
                    } ?: error("cannot open image")
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    if (BitmapFactory.decodeFile(dest.absolutePath) == null) {
                        dest.delete()
                        error("not a valid image")
                    }
                    dest
                }
                updateConfig(_config.value.copy(iconPath = "icon.png"))
                _toast.value = "icon updated"
            }.onFailure {
                _toast.value = it.message ?: "icon import failed"
            }
        }
    }

    fun createReleaseKeystore(
        alias: String,
        storePassword: String,
        keyPassword: String,
        displayName: String,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    keystoreManager.createKeystore(
                        alias = alias.ifBlank { "release" },
                        storePassword = storePassword,
                        keyPassword = keyPassword.ifBlank { storePassword },
                        displayName = displayName.ifBlank { alias.ifBlank { "release" } },
                    )
                }
            }.onSuccess { info ->
                refreshKeystores()
                updateConfig(
                    _config.value.copy(
                        signMode = SignMode.RELEASE,
                        releaseKeystoreId = info.id,
                    ),
                )
                _toast.value = "keystore created · ${info.sha256.take(17)}…"
            }.onFailure {
                _toast.value = it.message ?: "create keystore failed"
            }
        }
    }

    fun importReleaseKeystore(
        uri: Uri,
        alias: String,
        storePassword: String,
        keyPassword: String,
        displayName: String,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val tmp = File(ctx.cacheDir, "import-${System.currentTimeMillis()}.ks")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmp).use { out -> input.copyTo(out) }
                    } ?: error("cannot open keystore file")
                    try {
                        keystoreManager.importKeystore(
                            source = tmp,
                            alias = alias,
                            storePassword = storePassword,
                            keyPassword = keyPassword.ifBlank { storePassword },
                            displayName = displayName.ifBlank { alias },
                        )
                    } finally {
                        tmp.delete()
                    }
                }
            }.onSuccess { info ->
                refreshKeystores()
                updateConfig(
                    _config.value.copy(
                        signMode = SignMode.RELEASE,
                        releaseKeystoreId = info.id,
                    ),
                )
                _toast.value = "keystore imported · ${info.sha256.take(17)}…"
            }.onFailure {
                _toast.value = it.message ?: "import keystore failed"
            }
        }
    }

    fun deleteKeystore(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { keystoreManager.delete(id) }
            if (_config.value.releaseKeystoreId == id) {
                updateConfig(_config.value.copy(releaseKeystoreId = null))
            }
            refreshKeystores()
        }
    }

    fun exportKeystore(id: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val exportDir = File(ctx.cacheDir, "keystore-export").apply { mkdirs() }
                    val info = keystoreManager.list().firstOrNull { it.id == id }
                        ?: error("keystore not found")
                    val safe = info.displayName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
                        .ifEmpty { "release" }
                    val ext = File(info.fileName).extension.ifBlank { "p12" }
                    val dest = File(exportDir, "${safe}-${info.alias}.$ext")
                    keystoreManager.exportBackup(id, dest)
                    dest to info
                }
            }.onSuccess { (file, info) ->
                val ctx = getApplication<Application>()
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "LuaXIDE keystore backup\nalias=${info.alias}\nsha256=${info.sha256}\n(passwords not included)",
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "export keystore").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(chooser)
                _toast.value = "exported · passwords not included"
            }.onFailure {
                _toast.value = it.message ?: "export failed"
            }
        }
    }

    private fun refreshKeystores() {
        _keystores.value = keystoreManager.list()
    }

    private fun refreshIconPreview() {
        val cfg = _config.value
        val id = projectId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val iconFile = cfg.iconPath?.let { File(projectRepo.projectDirOf(id), it) }
                ?.takeIf { it.isFile }
            val layers = IconGenerator.generate(
                sourceImage = iconFile,
                bgColor = cfg.iconBgColor,
                appName = cfg.appName,
                transform = cfg.iconTransform(),
            )
            _iconPreview.value = layers.previewPng
        }
    }
}
