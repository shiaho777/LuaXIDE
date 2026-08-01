package dev.luaxide.ui.shell

import android.app.Application
import dev.luaxide.project.FileKind
import dev.luaxide.build.ApkInstaller
import dev.luaxide.checklist.DeviceChecklistCatalog
import dev.luaxide.checklist.ChecklistReport
import dev.luaxide.checklist.CheckStatus
import android.net.Uri
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.luaxide.engine.BreakpointSpec
import dev.luaxide.engine.DebugState
import dev.luaxide.engine.EngineHost
import dev.luaxide.engine.RunResult
import dev.luaxide.log.LogLevel
import dev.luaxide.log.LogSource
import dev.luaxide.log.LogStore
import dev.luaxide.log.NativeLogBridge
import dev.luaxide.project.FileNode
import dev.luaxide.project.Project
import dev.luaxide.program.NoRootRuntime
import dev.luaxide.program.ProotRootfs
import dev.luaxide.program.ProotExecutor
import dev.luaxide.program.ProgramSession
import dev.luaxide.program.TermLine
import dev.luaxide.program.resolvePreviewKind
import dev.luaxide.program.PreviewKind
import dev.luaxide.project.ProjectRepository
import dev.luaxide.ui.editor.BreakpointRemap
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Session state for the editor: the open project, its file tree, the currently
 * open file and its buffer, plus the engine and logs.
 *
 * Persistence: edits are debounced (250ms) and written atomically to disk, so
 * work survives restart. The same debounced signal also drives hot-reload
 * execution, so the preview tracks the file without an explicit run.
 */
@OptIn(FlowPreview::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProjectRepository(app)
    private var sandbox = NoRootRuntime.sandbox(app, "_boot")
    private var prootInstall: ProotRootfs.Install? = null
    private var prootExecutor: ProotExecutor? = null
    private val _programSession = MutableStateFlow(ProgramSession.boot(NoRootRuntime.describe(sandbox)))
    val programSession = _programSession.asStateFlow()
    private val _waitingStdin = MutableStateFlow(false)
    val waitingStdin = _waitingStdin.asStateFlow()

    val logs = LogStore(viewModelScope)
    private val engine = EngineHost(logSink = logs)
    private val nativeLogs = NativeLogBridge(logs)

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _project = MutableStateFlow<Project?>(null)
    val project = _project.asStateFlow()

    private val _tree = MutableStateFlow<FileNode?>(null)
    val tree = _tree.asStateFlow()

    private val _openPath = MutableStateFlow<String?>(null)
    val openPath = _openPath.asStateFlow()

    private val _code = MutableStateFlow("")
    val code = _code.asStateFlow()

    private val _result = MutableStateFlow<RunResult?>(null)
    val result = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val jumpSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val _jumpRequest = MutableStateFlow<dev.luaxide.ui.editor.JumpRequest?>(null)
    val jumpRequest = _jumpRequest.asStateFlow()

    private val _debugEnabled = MutableStateFlow(false)
    val debugEnabled = _debugEnabled.asStateFlow()

    private val _breakOnError = MutableStateFlow(true)
    val breakOnError = _breakOnError.asStateFlow()

    private val _instantEval = MutableStateFlow<dev.luaxide.engine.DebugWatch?>(null)
    val instantEval = _instantEval.asStateFlow()

    private val breakpointsByPath = mutableMapOf<String, MutableMap<Int, BreakpointSpec>>()
    private val _breakpoints = MutableStateFlow<Map<Int, BreakpointSpec>>(emptyMap())
    val breakpoints = _breakpoints.asStateFlow()

    private val _watches = MutableStateFlow<List<String>>(emptyList())
    val watches = _watches.asStateFlow()

    private val _showDebugPanel = MutableStateFlow(false)
    val showDebugPanel = _showDebugPanel.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    private val _checklist = MutableStateFlow(ChecklistReport())
    val checklist = _checklist.asStateFlow()

    private val _assetPreviewPath = MutableStateFlow<String?>(null)
    val assetPreviewPath = _assetPreviewPath.asStateFlow()

    fun consumeToast() {
        _toast.value = null
    }

    private fun toast(msg: String) {
        _toast.value = msg
    }

    val debugState = engine.debugState

    fun jumpToLine(line: Int) {
        _jumpRequest.value = dev.luaxide.ui.editor.JumpRequest(line, jumpSeq.incrementAndGet())
    }

    fun setDebugEnabled(enabled: Boolean) {
        _debugEnabled.value = enabled
        engine.setDebugEnabled(enabled)
        engine.setBreakOnError(_breakOnError.value)
        if (!enabled) {
            engine.debugStop()
            _instantEval.value = null
        }
    }

    fun setBreakOnError(enabled: Boolean) {
        _breakOnError.value = enabled
        engine.setBreakOnError(enabled)
        persistDebugSettings()
    }

    fun evalNow(expr: String) {
        val e = expr.trim()
        if (e.isEmpty()) return
        _instantEval.value = engine.evalExpression(e)
    }

    fun clearInstantEval() {
        _instantEval.value = null
    }

    fun toggleBreakpoint(line: Int) {
        if (line <= 0) return
        val path = _openPath.value ?: return
        val current = breakpointsByPath.getOrPut(path) { linkedMapOf() }
        if (line in current) current.remove(line)
        else current[line] = BreakpointSpec(line = line)
        if (current.isEmpty()) breakpointsByPath.remove(path)
        publishBreakpoints(path)
        persistBreakpoints()
    }

    fun setBreakpointCondition(line: Int, cond: String) {
        updateBreakpoint(line) { it.copy(condition = cond.trim()) }
    }

    fun setBreakpointLog(line: Int, logMessage: String, logOnly: Boolean) {
        updateBreakpoint(line) {
            it.copy(
                logMessage = logMessage.trim(),
                logOnly = logOnly && logMessage.isNotBlank(),
            )
        }
    }

    private fun updateBreakpoint(line: Int, transform: (BreakpointSpec) -> BreakpointSpec) {
        if (line <= 0) return
        val path = _openPath.value ?: return
        val current = breakpointsByPath.getOrPut(path) { linkedMapOf() }
        val base = current[line] ?: BreakpointSpec(line = line)
        current[line] = transform(base).copy(line = line)
        publishBreakpoints(path)
        persistBreakpoints()
    }

    fun clearBreakpoints() {
        val path = _openPath.value
        if (path != null) breakpointsByPath.remove(path)
        _breakpoints.value = emptyMap()
        engine.setBreakpoints(emptyMap<Int, BreakpointSpec>())
        persistBreakpoints()
    }

    private fun publishBreakpoints(path: String?) {
        val map = if (path != null) breakpointsByPath[path].orEmpty().toMap() else emptyMap()
        _breakpoints.value = map
        engine.setBreakpoints(map)
    }

    private fun persistBreakpoints() {
        val proj = _project.value ?: return
        val snapshot = breakpointsByPath.mapValues { (_, bps) ->
            bps.mapValues { (_, bp) ->
                ProjectRepository.StoredBreakpoint(
                    condition = bp.condition,
                    logMessage = bp.logMessage,
                    logOnly = bp.logOnly,
                )
            }
        }
        viewModelScope.launch {
            repo.saveBreakpoints(proj.id, snapshot)
        }
    }

    private fun persistDebugSettings() {
        val proj = _project.value ?: return
        val settings = ProjectRepository.DebugSettings(
            breakOnError = _breakOnError.value,
            panelOpen = _showDebugPanel.value,
        )
        viewModelScope.launch {
            repo.saveDebugSettings(proj.id, settings)
        }
    }

    fun debugContinue() {
        engine.debugContinue()
    }

    fun debugStep() {
        engine.debugStep()
    }

    fun debugStepOut() {
        engine.debugStepOut()
    }

    fun debugStop() {
        engine.debugStop()
    }

    fun toggleDebugPanel() {
        _showDebugPanel.value = !_showDebugPanel.value
        persistDebugSettings()
    }

    fun addWatch(expr: String) {
        val e = expr.trim()
        if (e.isEmpty()) return
        val next = (_watches.value + e).distinct()
        _watches.value = next
        engine.setWatches(next)
        persistWatches()
    }

    fun removeWatch(expr: String) {
        val next = _watches.value.filterNot { it == expr }
        _watches.value = next
        engine.setWatches(next)
        persistWatches()
    }

    fun clearWatches() {
        _watches.value = emptyList()
        engine.setWatches(emptyList())
        persistWatches()
    }

    private fun persistWatches() {
        val proj = _project.value ?: return
        val snapshot = _watches.value
        viewModelScope.launch {
            repo.saveWatches(proj.id, snapshot)
        }
    }

    fun exportLogs(format: String = "log", filtered: Boolean = true) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val dir = File(ctx.cacheDir, "log-export").apply { mkdirs() }
                    val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val text = if (format == "json") logs.exportJson(filtered) else logs.exportText(filtered)
                    val ext = if (format == "json") "json" else "log"
                    val file = File(dir, "luaxide-$ts.$ext")
                    val tmp = File(dir, ".${file.name}.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        file.writeText(text)
                        tmp.delete()
                    }
                    file to (if (format == "json") "application/json" else "text/plain")
                }
            }.onSuccess { (file, mime) ->
                val ctx = getApplication<Application>()
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "export logs").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(chooser)
            }.onFailure {
                logs.log(
                    LogLevel.ERROR,
                    LogSource.SYSTEM,
                    "export logs failed: ${it.message}",
                    tag = "logs",
                )
            }
        }
    }

    init {
        nativeLogs.start()
        // Autosave + hot reload. drop(1) skips the value set when opening a file
        // (that content is already on disk and gets run explicitly on open).
        viewModelScope.launch {
            _code.debounce(250).drop(1).collect { src ->
                saveCurrent(src)
                if (!_debugEnabled.value && isLuaPath(_openPath.value)) {
                    val ds = engine.debugState.value
                    if (ds !is DebugState.Running && ds !is DebugState.Paused) {
                        execute(src, sourceLabel = _openPath.value)
                    }
                }
            }
        }
        viewModelScope.launch {
            var lastPauseLine = -1
            engine.debugState.collect { state ->
                if (state is DebugState.Paused) {
                    if (state.pause.line != lastPauseLine) {
                        lastPauseLine = state.pause.line
                        jumpToLine(state.pause.line)
                    }
                } else {
                    lastPauseLine = -1
                    _instantEval.value = null
                }
            }
        }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val list = repo.listProjects()
        _projects.value = list
        list.firstOrNull()?.let { openProject(it) }
    }

    fun openProject(p: Project) {
        viewModelScope.launch {
            val current = _project.value
            if (current != null && current.id != p.id) {
                runCatching { saveCurrent(_code.value) }
            }
            _project.value = p
            sandbox = withContext(Dispatchers.IO) {
                NoRootRuntime.sandbox(getApplication(), p.id).also { NoRootRuntime.ensureLayout(it) }
            }
            val install = withContext(Dispatchers.IO) {
                runCatching { ProotRootfs.ensure(getApplication()) }.getOrNull()
            }
            prootInstall = install
            if (install != null) {
                prootExecutor = ProotExecutor(install, sandbox)
                engine.setRootfs(install.root.absolutePath)
            }
            runCatching { engine.setModuleRoot(repo.srcDirOf(p.id).absolutePath) }
            val bootNote = buildString {
                append(NoRootRuntime.describe(sandbox))
                if (install != null) append(" · ").append(install.note)
            }
            _programSession.value = ProgramSession.boot(bootNote)
            _tree.value = repo.loadTree(p.id)
            breakpointsByPath.clear()
            repo.loadBreakpoints(p.id).forEach { (path, map) ->
                breakpointsByPath[path] = map.mapValues { (line, bp) ->
                    BreakpointSpec(
                        line = line,
                        condition = bp.condition,
                        logMessage = bp.logMessage,
                        logOnly = bp.logOnly,
                    )
                }.toMutableMap()
            }
            _breakpoints.value = emptyMap()
            val w = repo.loadWatches(p.id)
            _watches.value = w
            engine.setWatches(w)
            val settings = repo.loadDebugSettings(p.id)
            _breakOnError.value = settings.breakOnError
            _showDebugPanel.value = settings.panelOpen
            engine.setBreakOnError(settings.breakOnError)
            openFile(p.entryFile, run = true)
            _projects.value = repo.listProjects()
        }
    }

    fun refreshTree() {
        val proj = _project.value ?: return
        viewModelScope.launch {
            _tree.value = repo.loadTree(proj.id)
            _projects.value = repo.listProjects()
        }
    }

    /** Open a file into the buffer. Persists any pending edits to the previous file first. */
    private fun isLuaPath(path: String?): Boolean =
        !path.isNullOrBlank() && path.endsWith(".lua", ignoreCase = true)

    private fun isTextEditablePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".lua") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".md") ||
            lower.endsWith(".json") ||
            lower.endsWith(".csv")
    }

    fun openFile(relPath: String, run: Boolean = true) {
        val proj = _project.value ?: return
        viewModelScope.launch {
            val prev = _openPath.value
            if (prev != null && prev != relPath) {
                val prevKind = FileKind.of(repo.absoluteSrcFileSync(proj.id, prev))
                if (prevKind == FileKind.LUA) {
                    runCatching { repo.writeFile(proj.id, prev, _code.value) }
                }
            }

            val abs = repo.absoluteSrcFileSync(proj.id, relPath)
            val kind = FileKind.of(abs)
            _openPath.value = relPath
            when {
                kind == FileKind.IMAGE || kind == FileKind.FONT -> {
                    _assetPreviewPath.value = relPath
                    publishBreakpoints(relPath)
                }
                isLuaPath(relPath) -> {
                    val content = repo.readFile(proj.id, relPath)
                    _code.value = content
                    _assetPreviewPath.value = null
                    publishBreakpoints(relPath)
                    if (run) execute(content, sourceLabel = relPath)
                }
                isTextEditablePath(relPath) -> {
                    val content = repo.readFile(proj.id, relPath)
                    _code.value = content
                    _assetPreviewPath.value = null
                    publishBreakpoints(relPath)
                }
                else -> {
                    _assetPreviewPath.value = relPath
                    publishBreakpoints(relPath)
                }
            }
        }
    }

    fun clearAssetPreview() {
        _assetPreviewPath.value = null
    }

    fun projectSrcRoot(): java.io.File? {
        val id = _project.value?.id ?: return null
        return repo.srcDirOf(id)
    }

    fun assetAbsoluteFile(relPath: String): java.io.File? {
        val proj = _project.value ?: return null
        return repo.absoluteSrcFileSync(proj.id, relPath).takeIf { it.isFile }
    }

    fun insertAtCursor(snippet: String) {
        val cur = _code.value
        val gap = if (cur.isEmpty() || cur.endsWith("\n")) "" else "\n"
        _code.value = cur + gap + snippet.trimEnd() + "\n"
        toast("已插入示例到文件末尾")
    }

    fun importAsset(uri: Uri, preferredName: String? = null) {
        val proj = _project.value ?: return
        viewModelScope.launch {
            runCatching {
                val cr = getApplication<Application>().contentResolver
                val name = preferredName
                    ?: cr.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                    }
                    ?: "asset.bin"
                val safe = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                val ext = safe.substringAfterLast('.', "").lowercase()
                val folder = when (ext) {
                    in setOf("png", "jpg", "jpeg", "webp", "gif", "svg") -> "assets/images"
                    in setOf("ttf", "otf", "ttc") -> "assets/fonts"
                    else -> "assets/misc"
                }
                val rel = "$folder/$safe"
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取文件")
                if (bytes.isEmpty()) error("文件为空")
                repo.writeBinary(proj.id, rel, bytes)
                _tree.value = repo.loadTree(proj.id)
                openFile(rel, run = false)
                toast("已导入 $rel")
            }.onFailure {
                toast(it.message ?: "导入失败")
            }
        }
    }

    fun ensureAssetFolders() {
        val proj = _project.value ?: return
        viewModelScope.launch {
            runCatching {
                repo.newFolder(proj.id, "assets")
                repo.newFolder(proj.id, "assets/images")
                repo.newFolder(proj.id, "assets/fonts")
            }
            _tree.value = repo.loadTree(proj.id)
            toast("已准备 assets/images 与 assets/fonts")
        }
    }

    fun runDeviceChecklist() {
        if (_checklist.value.running) return
        viewModelScope.launch {
            var report = ChecklistReport(items = DeviceChecklistCatalog.items, running = true)
            _checklist.value = report
            val app = getApplication<Application>()

            suspend fun step(id: String, block: suspend () -> String) {
                report = report.update(id, CheckStatus.Running)
                _checklist.value = report
                report = try {
                    val msg = block()
                    report.update(id, CheckStatus.Pass, msg)
                } catch (t: Throwable) {
                    report.update(id, CheckStatus.Fail, t.message ?: "failed")
                }
                _checklist.value = report
            }

            step("sandbox") {
                val projId = _project.value?.id ?: "_check"
                sandbox = NoRootRuntime.sandbox(app, projId)
                NoRootRuntime.ensureLayout(sandbox)
                val ok = sandbox.root.isDirectory && sandbox.home.isDirectory
                if (!ok) error("沙箱目录不完整")
                NoRootRuntime.describe(sandbox)
            }

            step("proot") {
                var install = ProotRootfs.ensure(app)
                prootInstall = install
                if (install.prootBin == null) error(install.note)
                prootExecutor = ProotExecutor(install, sandbox)
                engine.setRootfs(install.root.absolutePath)
                var result = withContext(Dispatchers.IO) { prootExecutor!!.selfTest() }
                if (!result.ok) {
                    install = ProotRootfs.forceReinstall(app)
                    prootInstall = install
                    prootExecutor = ProotExecutor(install, sandbox)
                    engine.setRootfs(install.root.absolutePath)
                    result = withContext(Dispatchers.IO) { prootExecutor!!.selfTest() }
                }
                if (!result.ok) error(result.output.ifBlank { "proot self-test failed · ${result.exitCode}" }.take(400))
                "proot ok · ${install.note}"
            }

            step("stdin") {
                val src = """
print("checklist-stdin")
io.write("name: ")
local n = io.read()
print("got", n)
""".trimIndent()
                _busy.value = true
                _waitingStdin.value = false
                val poll = viewModelScope.launch {
                    while (_busy.value) {
                        kotlinx.coroutines.delay(40)
                        _waitingStdin.value = engine.waitingStdin()
                    }
                }
                val job = viewModelScope.launch { engine.run(src) }
                var waited = false
                repeat(50) {
                    kotlinx.coroutines.delay(40)
                    if (engine.waitingStdin() || _waitingStdin.value) {
                        waited = true
                        return@repeat
                    }
                }
                poll.cancel()
                if (!waited) {
                    engine.cancel()
                    job.cancel()
                    _busy.value = false
                    error("未进入阻塞 stdin")
                }
                "io.read() 已阻塞"
            }

            step("cancel") {
                engine.cancel()
                _busy.value = false
                _waitingStdin.value = false
                kotlinx.coroutines.delay(80)
                if (engine.waitingStdin()) error("取消后仍在等待输入")
                "已取消并恢复"
            }

            step("template") {
                val names = app.assets.list("runtime")?.toList().orEmpty()
                val apk = names.firstOrNull { it.endsWith(".apk", true) }
                    ?: error("assets/runtime 缺少模板 APK")
                "模板 $apk"
            }

            step("install") {
                val can = ApkInstaller.canRequestInstall(app)
                if (!can) {
                    throw IllegalStateException("未授权安装未知应用 · 构建后请在系统设置允许")
                }
                "可安装 APK"
            }

            _checklist.value = report.copy(running = false, finishedAt = System.currentTimeMillis())
            val failed = report.items.count { it.status == CheckStatus.Fail }
            val passed = report.items.count { it.status == CheckStatus.Pass }
            toast("自检完成 · 通过 $passed · 失败 $failed")
        }
    }

    fun onCodeChange(new: String) {
        val old = _code.value
        _code.value = new
        remapOpenBreakpoints(old, new)
    }

    private fun remapOpenBreakpoints(oldText: String, newText: String) {
        val path = _openPath.value ?: return
        val current = breakpointsByPath[path].orEmpty()
        if (current.isEmpty()) return
        val lineMap = BreakpointRemap.remap(oldText, newText, current.keys)
        if (lineMap == current.keys) return
        val next = linkedMapOf<Int, BreakpointSpec>()
        current.forEach { (line, bp) ->
            // remap individually via set remap on singleton
            val mapped = BreakpointRemap.remap(oldText, newText, setOf(line)).firstOrNull()
            if (mapped != null) next[mapped] = bp.copy(line = mapped)
        }
        if (next.isEmpty()) breakpointsByPath.remove(path) else breakpointsByPath[path] = next
        publishBreakpoints(path)
        persistBreakpoints()
    }

    fun run() {
        viewModelScope.launch {
            val path = _openPath.value
            if (!isLuaPath(path)) {
                val entry = _project.value?.entryFile ?: "main.lua"
                val proj = _project.value
                if (proj == null) {
                    toast("当前不是 Lua 文件")
                    return@launch
                }
                if (!repo.exists(proj.id, entry)) {
                    toast("当前不是 Lua 文件，且入口 $entry 不存在")
                    return@launch
                }
                toast("当前不是 Lua · 运行入口 $entry")
                val src = repo.readFile(proj.id, entry)
                execute(src, sourceLabel = entry)
                return@launch
            }
            saveCurrent(_code.value)
            execute(_code.value, sourceLabel = path)
        }
    }

    fun openExamplesStdinAndRun() {
        viewModelScope.launch {
            val list = repo.listProjects()
            val examples = list.firstOrNull { it.name == "Examples" } ?: list.firstOrNull()
            if (examples == null) {
                toast("未找到示例项目")
                return@launch
            }
            val current = _project.value
            if (current != null && current.id != examples.id) {
                runCatching { saveCurrent(_code.value) }
            }
            _project.value = examples
            sandbox = withContext(Dispatchers.IO) {
                NoRootRuntime.sandbox(getApplication(), examples.id).also { NoRootRuntime.ensureLayout(it) }
            }
            val install = withContext(Dispatchers.IO) {
                runCatching { ProotRootfs.ensure(getApplication()) }.getOrNull()
            }
            prootInstall = install
            if (install != null) {
                prootExecutor = ProotExecutor(install, sandbox)
                engine.setRootfs(install.root.absolutePath)
            }
            runCatching { engine.setModuleRoot(repo.srcDirOf(examples.id).absolutePath) }
            _programSession.value = ProgramSession.boot(NoRootRuntime.describe(sandbox))
            _tree.value = repo.loadTree(examples.id)
            val path = if (repo.exists(examples.id, "program/stdin.lua")) "program/stdin.lua" else examples.entryFile
            val content = repo.readFile(examples.id, path)
            _openPath.value = path
            _code.value = content
            publishBreakpoints(path)
            execute(content, sourceLabel = path)
            _projects.value = repo.listProjects()
        }
    }


    fun onEvent(handlerId: Int) {
        viewModelScope.launch { _result.value = engine.invoke(handlerId) }
    }

    fun newFile(name: String) = newFileIn("", name)

    fun newFileIn(dirRelPath: String, name: String) {
        val proj = _project.value ?: return
        val raw = name.trim().trim('/')
        if (raw.isEmpty()) {
            toast("name is empty")
            return
        }
        val leaf = if ('.' in raw.substringAfterLast('/')) raw else "$raw.lua"
        val relPath = joinPath(dirRelPath, leaf)
        viewModelScope.launch {
            runCatching {
                if (repo.exists(proj.id, relPath)) error("already exists: $relPath")
                val seed = if (leaf.endsWith(".lua", ignoreCase = true)) "-- $leaf\n" else ""
                repo.newFile(proj.id, relPath, seed)
                _tree.value = repo.loadTree(proj.id)
                openFile(relPath, run = true)
                logs.log(LogLevel.INFO, LogSource.SYSTEM, "created $relPath", tag = "project")
            }.onFailure {
                toast(it.message ?: "create file failed")
                logs.log(LogLevel.WARNING, LogSource.SYSTEM, it.message ?: "create file failed", tag = "project")
            }
        }
    }

    fun newFolder(dirRelPath: String, name: String) {
        val proj = _project.value ?: return
        val leaf = name.trim().trim('/')
        if (leaf.isEmpty()) {
            toast("name is empty")
            return
        }
        val relPath = joinPath(dirRelPath, leaf)
        viewModelScope.launch {
            runCatching {
                if (repo.exists(proj.id, relPath)) error("already exists: $relPath")
                repo.newFolder(proj.id, relPath)
                _tree.value = repo.loadTree(proj.id)
                logs.log(LogLevel.INFO, LogSource.SYSTEM, "created folder $relPath", tag = "project")
            }.onFailure {
                toast(it.message ?: "create folder failed")
                logs.log(LogLevel.WARNING, LogSource.SYSTEM, it.message ?: "create folder failed", tag = "project")
            }
        }
    }

    fun renameNode(relPath: String, newName: String) {
        val proj = _project.value ?: return
        val leaf = newName.trim()
        if (leaf.isBlank() || leaf == relPath.substringAfterLast('/')) return
        viewModelScope.launch {
            runCatching {
                _openPath.value?.let { repo.writeFile(proj.id, it, _code.value) }
                val newRel = repo.rename(proj.id, relPath, leaf)
                _tree.value = repo.loadTree(proj.id)
                remapBreakpoints(relPath, newRel)
                if (proj.entryFile == relPath || proj.entryFile.startsWith("$relPath/")) {
                    val nextEntry = if (proj.entryFile == relPath) newRel else newRel + proj.entryFile.removePrefix(relPath)
                    val updated = repo.setEntryFile(proj.id, nextEntry)
                    _project.value = updated
                    _projects.value = repo.listProjects()
                }
                val open = _openPath.value
                if (open != null && (open == relPath || open.startsWith("$relPath/"))) {
                    val suffix = open.removePrefix(relPath)
                    openFile(newRel + suffix, run = true)
                }
                logs.log(LogLevel.INFO, LogSource.SYSTEM, "renamed $relPath → $newRel", tag = "project")
            }.onFailure {
                toast(it.message ?: "rename failed")
                logs.log(LogLevel.WARNING, LogSource.SYSTEM, it.message ?: "rename failed", tag = "project")
            }
        }
    }

    fun deleteNode(relPath: String) {
        val proj = _project.value ?: return
        viewModelScope.launch {
            runCatching {
                repo.delete(proj.id, relPath)
                dropBreakpoints(relPath)
                val newTree = repo.loadTree(proj.id)
                _tree.value = newTree
                if (proj.entryFile == relPath || proj.entryFile.startsWith("$relPath/")) {
                    val fallbackEntry = firstFile(newTree) ?: run {
                        repo.newFile(proj.id, "main.lua", "-- main.lua\n")
                        _tree.value = repo.loadTree(proj.id)
                        "main.lua"
                    }
                    val updated = repo.setEntryFile(proj.id, fallbackEntry)
                    _project.value = updated
                    _projects.value = repo.listProjects()
                }
                val open = _openPath.value
                if (open != null && (open == relPath || open.startsWith("$relPath/"))) {
                    val fallback = firstFile(_tree.value) ?: (_project.value?.entryFile ?: "main.lua")
                    _openPath.value = null
                    openFile(fallback, run = true)
                }
                logs.log(LogLevel.INFO, LogSource.SYSTEM, "deleted $relPath", tag = "project")
            }.onFailure {
                toast(it.message ?: "delete failed")
                logs.log(LogLevel.WARNING, LogSource.SYSTEM, it.message ?: "delete failed", tag = "project")
            }
        }
    }

    fun setEntryFile(relPath: String) {
        val proj = _project.value ?: return
        if (!relPath.endsWith(".lua", ignoreCase = true)) {
            toast("entry must be a .lua file")
            return
        }
        viewModelScope.launch {
            runCatching {
                if (!repo.exists(proj.id, relPath)) error("file not found")
                val updated = repo.setEntryFile(proj.id, relPath)
                _project.value = updated
                _projects.value = repo.listProjects()
                toast("entry → $relPath")
                logs.log(LogLevel.INFO, LogSource.SYSTEM, "entry file set to $relPath", tag = "project")
            }.onFailure {
                toast(it.message ?: "set entry failed")
            }
        }
    }


    private fun remapBreakpoints(from: String, to: String) {
        val updates = mutableMapOf<String, MutableMap<Int, BreakpointSpec>>()
        val remove = mutableListOf<String>()
        breakpointsByPath.forEach { (path, lines) ->
            when {
                path == from -> {
                    remove += path
                    updates[to] = lines.toMutableMap()
                }
                path.startsWith("$from/") -> {
                    remove += path
                    updates[to + path.removePrefix(from)] = lines.toMutableMap()
                }
            }
        }
        remove.forEach { breakpointsByPath.remove(it) }
        breakpointsByPath.putAll(updates)
        publishBreakpoints(_openPath.value)
        persistBreakpoints()
    }

    private fun dropBreakpoints(relPath: String) {
        val remove = breakpointsByPath.keys.filter { it == relPath || it.startsWith("$relPath/") }
        if (remove.isEmpty()) return
        remove.forEach { breakpointsByPath.remove(it) }
        publishBreakpoints(_openPath.value)
        persistBreakpoints()
    }

    private fun firstFile(node: FileNode?): String? {
        if (node == null) return null
        if (!node.isDirectory) return node.relPath
        node.children.forEach { child -> firstFile(child)?.let { return it } }
        return null
    }

    private fun joinPath(dir: String, leaf: String): String =
        if (dir.isEmpty()) leaf else "$dir/$leaf"

    fun createProgramProject(name: String) {
        val n = name.trim()
        if (n.isEmpty()) {
            toast("name is empty")
            return
        }
        viewModelScope.launch {
            runCatching {
                val current = _project.value
                if (current != null) runCatching { saveCurrent(_code.value) }
                val p = repo.createProject(n, kind = "program")
                _projects.value = repo.listProjects()
                openProject(p)
                toast("created $n (program)")
            }.onFailure {
                toast(it.message ?: "create program failed")
            }
        }
    }

    fun createProject(name: String) {
        val n = name.trim()
        if (n.isEmpty()) {
            toast("name is empty")
            return
        }
        viewModelScope.launch {
            runCatching {
                val current = _project.value
                if (current != null) runCatching { saveCurrent(_code.value) }
                val p = repo.createProject(n)
                _projects.value = repo.listProjects()
                openProject(p)
                toast("created $n")
            }.onFailure {
                toast(it.message ?: "create project failed")
            }
        }
    }

    fun renameProject(newName: String) {
        val proj = _project.value ?: return
        val n = newName.trim()
        if (n.isEmpty()) {
            toast("name is empty")
            return
        }
        viewModelScope.launch {
            runCatching {
                val updated = repo.renameProject(proj.id, n)
                _project.value = updated
                _projects.value = repo.listProjects()
                toast("renamed project")
            }.onFailure {
                toast(it.message ?: "rename project failed")
            }
        }
    }

    fun duplicateProject(source: Project? = null, newName: String? = null) {
        val proj = source ?: _project.value ?: return
        viewModelScope.launch {
            runCatching {
                runCatching { saveCurrent(_code.value) }
                val copy = repo.duplicateProject(proj.id, newName)
                _projects.value = repo.listProjects()
                openProject(copy)
                toast("duplicated ${copy.name}")
            }.onFailure {
                toast(it.message ?: "duplicate failed")
            }
        }
    }

    fun deleteProject(target: Project? = null) {
        val proj = target ?: _project.value ?: return
        viewModelScope.launch {
            runCatching {
                val list = repo.listProjects()
                if (list.size <= 1) error("cannot delete the last project")
                val wasOpen = _project.value?.id == proj.id
                if (wasOpen) runCatching { saveCurrent(_code.value) }
                repo.deleteProject(proj.id)
                val remaining = repo.listProjects()
                _projects.value = remaining
                if (wasOpen) {
                    remaining.firstOrNull()?.let { openProject(it) }
                        ?: error("no projects left")
                }
                toast("deleted ${proj.name}")
            }.onFailure {
                toast(it.message ?: "delete project failed")
            }
        }
    }

    private suspend fun saveCurrent(src: String) {
        val proj = _project.value ?: return
        val path = _openPath.value ?: return
        if (!isTextEditablePath(path)) return
        if (_assetPreviewPath.value != null) return
        repo.writeFile(proj.id, path, src)
    }

    private suspend fun execute(src: String, sourceLabel: String? = null) {
        _busy.value = true
        _waitingStdin.value = false
        foldSessionForRun()
        val path = sourceLabel ?: _openPath.value
        if (!isLuaPath(path) && sourceLabel == null) {
            _busy.value = false
            toast("只能运行 .lua 文件")
            return
        }
        publishBreakpoints(_openPath.value)
        engine.setDebugEnabled(_debugEnabled.value)
        engine.setBreakOnError(_breakOnError.value)
        val projId = _project.value?.id ?: "_anon"
        sandbox = NoRootRuntime.sandbox(getApplication(), projId)
        NoRootRuntime.ensureLayout(sandbox)
        val proot = runCatching { ProotRootfs.ensure(getApplication()) }.getOrNull()
        prootInstall = proot
        if (proot != null) {
            prootExecutor = ProotExecutor(proot, sandbox)
            engine.setRootfs(proot.root.absolutePath)
        }
        val srcRoot = runCatching { repo.srcDirOf(projId).absolutePath }.getOrNull().orEmpty()
        if (srcRoot.isNotEmpty()) engine.setModuleRoot(srcRoot)
        logs.log(
            LogLevel.DEBUG,
            LogSource.ENGINE,
            NoRootRuntime.describe(sandbox) + if (proot != null) " · ${proot.note}" else "",
            tag = "sandbox",
        )
        val poll = viewModelScope.launch {
            while (_busy.value) {
                kotlinx.coroutines.delay(80)
                val waiting = engine.waitingStdin()
                if (_waitingStdin.value != waiting) {
                    _waitingStdin.value = waiting
                    if (waiting) {
                        _programSession.value = _programSession.value.append(
                            TermLine(TermLine.Kind.Meta, "等待输入…"),
                        ).copy(running = true)
                    }
                }
            }
        }
        val result = try {
            engine.run(src)
        } finally {
            poll.cancel()
            _waitingStdin.value = false
        }
        _result.value = result
        absorbProgramResult(result, sourceLabel = (sourceLabel ?: path)?.takeIf { isLuaPath(it) } ?: path ?: "main.lua")
        _busy.value = false
    }

    private fun foldSessionForRun() {
        val label = NoRootRuntime.describe(sandbox)
        val prev = dropTrailingStatus(_programSession.value)
        val had = prev.lines.any {
            it.kind == TermLine.Kind.Output ||
                it.kind == TermLine.Kind.Input ||
                it.kind == TermLine.Kind.Error ||
                it.kind == TermLine.Kind.Prompt
        }
        val lines = if (had) {
            listOf(TermLine(TermLine.Kind.Meta, "── 上一轮已折叠 ──"))
        } else {
            emptyList()
        }
        _programSession.value = prev.copy(
            lines = lines,
            running = true,
            lastExitOk = null,
            sandboxLabel = label,
        )
    }

    private fun absorbProgramResult(result: dev.luaxide.engine.RunResult, sourceLabel: String) {
        if (resolvePreviewKind(result) != PreviewKind.Terminal &&
            !(result.ok.not() && result.tree == null)
        ) {
            return
        }
        val label = NoRootRuntime.describe(sandbox)
        var session = dropTrailingStatus(_programSession.value).copy(sandboxLabel = label, running = true)
        session = session.append(TermLine(TermLine.Kind.Prompt, "lua $sourceLabel"))
        val out = result.output
        var wrote = false
        if (out.isNotEmpty()) {
            out.lineSequence().forEach { line ->
                if (line.isNotEmpty()) {
                    session = session.append(TermLine(TermLine.Kind.Output, line))
                    wrote = true
                }
            }
        }
        if (!wrote && result.ok) {
            session = session.append(TermLine(TermLine.Kind.Meta, "无输出"))
        }
        if (!result.ok) {
            val err = result.error?.trim().orEmpty().ifEmpty { "unknown error" }
            session = session.append(TermLine(TermLine.Kind.Error, err))
        }
        _programSession.value = session.copy(running = false, lastExitOk = result.ok)
    }

    private fun softenReplLine(raw: String): String {
        val line = raw.trimEnd()
        val t = line.trim()
        if (t.isEmpty()) return line
        if (t.startsWith(".")) return t
        if (t.startsWith("=")) return t
        val statement = Regex(
            """^(local|function|for|while|if|return|do|repeat|break|print|io\.|require|os\.|type)\b""",
        )
        if (statement.containsMatchIn(t)) return t
        if (Regex("""^[A-Za-z_][\w.]*\s*=[^=]""").containsMatchIn(t)) return t
        if (t.startsWith("\"") || t.startsWith("'")) return "print($t)"
        if (Regex("""^[\w.]+$""").matches(t)) {
            return """print("$t")"""
        }
        if (!t.contains("(") && !t.contains("[") && !t.contains("{") &&
            t.any { it.isLetter() } &&
            !t.contains("=")
        ) {
            val escaped = t.replace("\\", "\\\\").replace("\"", "\\\"")
            return """print("$escaped")"""
        }
        if (!t.contains("=") || t.contains("==") || t.contains("~=")) {
            return "print($t)"
        }
        return t
    }



    private fun isStatusMeta(text: String): Boolean {
        val s = text.trim().lowercase()
        return s == "done" ||
            s == "stopped" || s == "已停止" ||
            s == "no output" || s == "无输出" ||
            s == "proot ok" ||
            s.startsWith("proot failed") ||
            s.startsWith("[finished") ||
            s.startsWith("[failed") ||
            s.startsWith("[cancelled") ||
            s.startsWith("[proot") ||
            s == "waiting for input…" ||
            s == "等待输入…" ||
            s.startsWith("── 上一轮") ||
            s == "waiting for input..."
    }

    private fun dropTrailingStatus(session: ProgramSession): ProgramSession {
        val lines = session.lines.toMutableList()
        while (lines.isNotEmpty()) {
            val last = lines.last()
            if (last.kind == TermLine.Kind.Meta && isStatusMeta(last.text)) {
                lines.removeAt(lines.lastIndex)
            } else {
                break
            }
        }
        return session.copy(lines = lines)
    }

    private fun appendEngineOutput(session: ProgramSession, output: String): ProgramSession {
        var s = session
        if (output.isEmpty()) return s
        output.lineSequence().forEach { line ->
            if (line.isNotEmpty()) s = s.append(TermLine(TermLine.Kind.Output, line))
        }
        return s
    }

    fun submitTerminalLine(raw: String) {
        val line = raw.trimEnd()
        if (line.isBlank() && !_waitingStdin.value) return
        viewModelScope.launch {
            when {
                line == ".clear" -> {
                    _programSession.value = ProgramSession.boot(NoRootRuntime.describe(sandbox)).copy(lastExitOk = null)
                }
                line == ".help" -> {
                    _programSession.value = dropTrailingStatus(_programSession.value).append(
                        TermLine(TermLine.Kind.Input, line),
                        TermLine(
                            TermLine.Kind.Meta,
                            "帮助 · .run  .clear  .stop  .proot\n" +
                                "在此输入 Lua · print(...)  =1+2  io.read() 会等待输入",
                        ),
                    )
                }
                line == ".run" -> run()
                line == ".stop" -> cancelProgram()
                line == ".proot" -> {
                    val install = runCatching { ProotRootfs.ensure(getApplication()) }.getOrElse {
                        _programSession.value = _programSession.value.append(
                            TermLine(TermLine.Kind.Error, "proot install failed: ${it.message}"),
                        )
                        return@launch
                    }
                    prootInstall = install
                    prootExecutor = ProotExecutor(install, sandbox)
                    engine.setRootfs(install.root.absolutePath)
                    _programSession.value = dropTrailingStatus(_programSession.value).append(
                        TermLine(TermLine.Kind.Input, ".proot"),
                        TermLine(TermLine.Kind.Meta, "checking proot…"),
                    ).copy(running = true)
                    val result = withContext(Dispatchers.IO) { prootExecutor!!.selfTest() }
                    var s = _programSession.value
                    result.output.lineSequence().forEach { o ->
                        if (o.isNotBlank()) s = s.append(TermLine(TermLine.Kind.Output, o))
                    }
                    s = s.append(
                        TermLine(
                            if (result.ok) TermLine.Kind.Meta else TermLine.Kind.Error,
                            if (result.ok) "proot ok"
                            else "proot failed · exit ${result.exitCode}",
                        ),
                    )
                    _programSession.value = s.copy(running = false, lastExitOk = result.ok)
                }
                _waitingStdin.value || _busy.value -> {
                    _programSession.value = dropTrailingStatus(_programSession.value).append(
                        TermLine(TermLine.Kind.Input, line),
                    )
                    engine.pushStdin(line)
                }
                else -> {
                    var session = dropTrailingStatus(_programSession.value).append(
                        TermLine(TermLine.Kind.Input, line),
                    )
                    session = session.copy(running = true)
                    _programSession.value = session
                    val code = if (line.contains('\n')) line else softenReplLine(line)
                    val result = engine.repl(code)
                    var next = appendEngineOutput(_programSession.value, result.output)
                    if (!result.ok) {
                        next = next.append(
                            TermLine(TermLine.Kind.Error, result.error?.trim().orEmpty().ifEmpty { "error" }),
                        )
                    }
                    _programSession.value = next.copy(running = false, lastExitOk = result.ok)
                    if (result.output.isNotEmpty() || !result.ok) {
                        logs.logLines(
                            if (result.ok) LogLevel.INFO else LogLevel.ERROR,
                            LogSource.LUA,
                            if (result.ok) result.output else (result.error ?: "error"),
                            tag = "repl",
                        )
                    }
                }
            }
        }
    }

    fun cancelProgram() {
        prootExecutor?.cancel()
        engine.cancel()
        _busy.value = false
        _waitingStdin.value = false
        _programSession.value = dropTrailingStatus(_programSession.value).append(
            TermLine(TermLine.Kind.Meta, "已停止"),
        ).copy(running = false, lastExitOk = false)
    }

    fun clearTerminal() {
        _programSession.value = ProgramSession.boot(NoRootRuntime.describe(sandbox))
    }

    override fun onCleared() {
        nativeLogs.stop()
        engine.close()
        super.onCleared()
    }
}

