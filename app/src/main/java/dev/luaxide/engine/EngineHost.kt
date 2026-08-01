package dev.luaxide.engine

import dev.luaxide.log.LogLevel
import dev.luaxide.log.LogSink
import dev.luaxide.log.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

data class RunResult(
    val ok: Boolean,
    val tree: UiNode?,
    val output: String,
    val error: String?,
    val errorLine: Int? = null,
)

data class BreakpointSpec(
    val line: Int,
    val condition: String = "",
    val logMessage: String = "",
    val logOnly: Boolean = false,
)

data class DebugLocal(val name: String, val value: String)

data class DebugFrame(
    val name: String,
    val line: Int,
    val defLine: Int,
)

data class DebugWatch(
    val expr: String,
    val value: String,
    val ok: Boolean,
)

data class DebugPause(
    val line: Int,
    val locals: List<DebugLocal>,
    val stack: List<DebugFrame> = emptyList(),
    val watches: List<DebugWatch> = emptyList(),
    val reason: Int = 0,
    val error: String? = null,
)

sealed interface DebugState {
    data object Idle : DebugState
    data object Running : DebugState
    data class Paused(val pause: DebugPause) : DebugState
}

private val LINE_PREFIX = Regex("""^line (\d+):""")

private fun parseErrorLine(message: String?): Int? =
    message?.let { LINE_PREFIX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

sealed interface EngineState {
    data object Idle : EngineState
    data object Running : EngineState
    data class Ready(val tree: UiNode?) : EngineState
    data class Error(val message: String) : EngineState
}

class EngineHost(
    private val stepLimit: Long = 50_000_000L,
    private val logSink: LogSink? = null,
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "luax-engine").apply { isDaemon = true }
    }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var handle: Long = 0L
    @Volatile private var rootfsPath: String = ""
    @Volatile private var modrootPath: String = ""

    @Volatile
    private var debugEnabled: Boolean = false
    @Volatile private var breakOnError: Boolean = true

    @Volatile
    private var breakpoints: IntArray = intArrayOf()
    private var breakpointConds: Array<String> = emptyArray()
    private var breakpointLogs: Array<String> = emptyArray()
    private var breakpointLogOnly: BooleanArray = booleanArrayOf()
    @Volatile private var watchExprs: List<String> = emptyList()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _debugState = MutableStateFlow<DebugState>(DebugState.Idle)
    val debugState: StateFlow<DebugState> = _debugState.asStateFlow()

    private var pollJob: Job? = null

    private fun ensureHandle() {
        if (handle == 0L) {
            handle = LuaxNative.nativeNew()
            LuaxNative.nativeSetStepLimit(handle, stepLimit)
            LuaxNative.nativeDebugEnable(handle, debugEnabled)
            LuaxNative.nativeDebugSetBreakOnError(handle, breakOnError)
            if (rootfsPath.isNotEmpty()) LuaxNative.nativeSetRootfs(handle, rootfsPath)
            if (modrootPath.isNotEmpty()) LuaxNative.nativeSetModroot(handle, modrootPath)
            applyBreakpointsNative()
        }
    }

    suspend fun run(src: String): RunResult {
        _state.value = EngineState.Running
        if (debugEnabled) startDebugPoll()
        val result = withContext(dispatcher) {
            recreate()
            execute {
                LuaxNative.nativeClearCancel(handle)
                LuaxNative.nativeRun(handle, src)
            }
        }
        pollJob?.cancel()
        if (debugEnabled) _debugState.value = DebugState.Idle
        publish(result)
        return result
    }

    suspend fun invoke(handlerId: Int): RunResult {
        val result = withContext(dispatcher) {
            execute { LuaxNative.nativeInvoke(handle, handlerId) }
        }
        publish(result)
        return result
    }

    suspend fun repl(line: String): RunResult {
        val result = withContext(dispatcher) {
            execute {
                LuaxNative.nativeClearCancel(handle)
                LuaxNative.nativeRepl(handle, line)
            }
        }
        publish(result)
        return result
    }

    fun cancel() {
        val h = handle
        if (h != 0L) LuaxNative.nativeCancel(h)
        debugStop()
    }

    fun pushStdin(line: String) {
        val h = handle
        if (h != 0L) LuaxNative.nativePushStdin(h, line)
    }

    fun waitingStdin(): Boolean {
        val h = handle
        return h != 0L && LuaxNative.nativeWaitingStdin(h)
    }

    fun setRootfs(path: String) {
        rootfsPath = path
        val h = handle
        if (h != 0L) LuaxNative.nativeSetRootfs(h, path)
    }

    fun setModuleRoot(path: String) {
        modrootPath = path
        val h = handle
        if (h != 0L) LuaxNative.nativeSetModroot(h, path)
    }

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
        val h = handle
        if (h != 0L) {
            LuaxNative.nativeDebugEnable(h, enabled)
            LuaxNative.nativeDebugSetBreakOnError(h, breakOnError)
        }
        if (!enabled) {
            pollJob?.cancel()
            _debugState.value = DebugState.Idle
        }
    }

    fun setBreakOnError(enabled: Boolean) {
        breakOnError = enabled
        val h = handle
        if (h != 0L) {
            LuaxNative.nativeDebugSetBreakOnError(h, enabled)
        }
    }

    fun setBreakpoints(lines: Set<Int>) {
        setBreakpointSpecs(lines.associateWith { BreakpointSpec(it) })
    }

    fun setBreakpoints(map: Map<Int, BreakpointSpec>) {
        setBreakpointSpecs(map)
    }

    private fun setBreakpointSpecs(map: Map<Int, BreakpointSpec>) {
        val sorted = map.filterKeys { it > 0 }.toList().sortedBy { it.first }
        breakpoints = sorted.map { it.first }.toIntArray()
        breakpointConds = sorted.map { it.second.condition }.toTypedArray()
        breakpointLogs = sorted.map { it.second.logMessage }.toTypedArray()
        breakpointLogOnly = BooleanArray(sorted.size) { sorted[it].second.logOnly }
        applyBreakpointsNative()
    }

    private fun applyBreakpointsNative() {
        val h = handle
        if (h == 0L) return
        val hasExtra = breakpointConds.any { it.isNotBlank() } ||
            breakpointLogs.any { it.isNotBlank() } ||
            breakpointLogOnly.any { it }
        if (hasExtra) {
            LuaxNative.nativeDebugSetBreakpointsFull(
                h, breakpoints, breakpointConds, breakpointLogs, breakpointLogOnly,
            )
        } else {
            LuaxNative.nativeDebugSetBreakpoints(h, breakpoints)
        }
    }

    fun setWatches(exprs: List<String>) {
        watchExprs = exprs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun evalExpression(expr: String): DebugWatch {
        val h = handle
        if (h == 0L) return DebugWatch(expr, "engine closed", false)
        val json = runCatching { LuaxNative.nativeDebugEval(h, expr) }.getOrDefault("""{"ok":false}""")
        return parseWatch(expr, json)
    }

    fun debugContinue() {
        val h = handle
        if (h == 0L) return
        LuaxNative.nativeDebugContinue(h)
        _debugState.value = DebugState.Running
    }

    fun debugStep() {
        val h = handle
        if (h == 0L) return
        LuaxNative.nativeDebugStep(h)
        _debugState.value = DebugState.Running
    }

    fun debugStepOut() {
        val h = handle
        if (h == 0L) return
        LuaxNative.nativeDebugStepOut(h)
        _debugState.value = DebugState.Running
    }

    fun debugStop() {
        val h = handle
        if (h == 0L) return
        LuaxNative.nativeDebugStop(h)
        pollJob?.cancel()
        _debugState.value = DebugState.Idle
    }

    private fun startDebugPoll() {
        pollJob?.cancel()
        pollJob = controlScope.launch {
            _debugState.value = DebugState.Running
            var lastLine = -1
            var lastLocals = ""
            while (isActive) {
                delay(40)
                val h = handle
                if (h == 0L) break
                val paused = LuaxNative.nativeDebugIsPaused(h)
                if (paused) {
                    val line = LuaxNative.nativeDebugPauseLine(h)
                    val localsJson = LuaxNative.nativeDebugLocals(h)
                    val stackJson = LuaxNative.nativeDebugStack(h)
                    val watches = watchExprs.map { expr ->
                        parseWatch(expr, LuaxNative.nativeDebugEval(h, expr))
                    }
                    val watchesKey = watches.joinToString("|") { "${it.expr}=${it.value}" }
                    val reasonPeek = LuaxNative.nativeDebugPauseReason(h)
                    val fingerprint = "$line|$localsJson|$stackJson|$watchesKey|$reasonPeek"
                    if (lastLocals != fingerprint || _debugState.value !is DebugState.Paused) {
                        lastLine = line
                        lastLocals = fingerprint
                        val reason = LuaxNative.nativeDebugPauseReason(h)
                        val errMsg = if (reason == 1) LuaxNative.nativeDebugLastError(h) else null
                        _debugState.value = DebugState.Paused(
                            DebugPause(
                                line = line,
                                locals = parseLocals(localsJson),
                                stack = parseStack(stackJson),
                                watches = watches,
                                reason = reason,
                                error = errMsg?.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                } else if (_debugState.value is DebugState.Paused) {
                    lastLine = -1
                    lastLocals = ""
                    _debugState.value = DebugState.Running
                }
            }
        }
    }

    private fun parseLocals(json: String): List<DebugLocal> = runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                if (name.isEmpty()) continue
                add(DebugLocal(name, formatLocalValue(o.opt("value"))))
            }
        }
    }.getOrDefault(emptyList())

    private fun formatLocalValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "nil"
        is String -> "\"$value\""
        is Number -> {
            val d = value.toDouble()
            if (d % 1.0 == 0.0 && d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
                d.toLong().toString()
            } else {
                value.toString()
            }
        }
        is Boolean -> value.toString()
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        else -> value.toString()
    }

    
    private fun parseWatch(expr: String, json: String): DebugWatch = runCatching {
        val o = JSONObject(json)
        val ok = o.optBoolean("ok", false)
        if (ok) {
            DebugWatch(expr, formatLocalValue(o.opt("value")), true)
        } else {
            DebugWatch(expr, o.optString("error", "error"), false)
        }
    }.getOrDefault(DebugWatch(expr, "error", false))

    private fun parseStack(json: String): List<DebugFrame> = runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "fn")
                val line = o.optInt("line", 0)
                val def = o.optInt("def", 0)
                add(DebugFrame(name = name, line = line, defLine = def))
            }
        }
    }.getOrDefault(emptyList())

private inline fun execute(block: () -> Array<String>): RunResult = runCatching {
        ensureHandle()
        val res = block()
        val status = res.getOrElse(0) { "1" }
        val err = res.getOrElse(1) { "" }
        val treeJson = res.getOrElse(2) { "" }
        val output = LuaxNative.nativeTakeOutput(handle)
        if (status == "0") {
            RunResult(ok = true, tree = UiTreeParser.parse(treeJson), output = output, error = null)
        } else {
            val message = err.ifEmpty { "unknown error" }
            if (message.contains("debug stopped by user")) {
                RunResult(ok = true, tree = null, output = output, error = null)
            } else {
                RunResult(ok = false, tree = null, output = output, error = message, errorLine = parseErrorLine(message))
            }
        }
    }.getOrElse { t ->
        RunResult(ok = false, tree = null, output = "", error = t.message ?: "engine failure")
    }

    private fun publish(result: RunResult) {
        logSink?.let { sink ->
            if (result.output.isNotEmpty()) {
                sink.logLines(LogLevel.INFO, LogSource.LUA, result.output, tag = "print")
            }
            if (!result.ok && result.error != null) {
                sink.log(LogLevel.ERROR, LogSource.ENGINE, result.error, tag = "run", line = result.errorLine)
            }
        }
        _state.value = if (result.ok) {
            if (result.tree != null) EngineState.Ready(result.tree) else EngineState.Idle
        } else {
            EngineState.Error(result.error ?: "unknown error")
        }
    }

    private fun recreate() {
        if (handle != 0L) {
            LuaxNative.nativeClose(handle)
            handle = 0L
        }
        ensureHandle()
    }

    fun close() {
        pollJob?.cancel()
        val h = handle
        if (h != 0L) {
            runCatching { LuaxNative.nativeDebugStop(h) }
        }
        scope.launch {
            if (handle != 0L) {
                LuaxNative.nativeClose(handle)
                handle = 0L
            }
        }
        worker.shutdown()
    }
}
