package dev.luaxide.engine

import dev.luaxide.log.LogLevel
import dev.luaxide.log.LogSink
import dev.luaxide.log.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Python engine host (MicroPython). Implements the shared [EngineAdapter]
 * contract (docs/PLATFORM_ABI.md) like the Lua and JS hosts. Debug, the REPL
 * and blocking stdin stay Lua-only. Python program output is captured by the
 * facade and logged under [LogSource.PY].
 */
class PyEngineHost(
    private val logSink: LogSink? = null,
) : EngineAdapter {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "luaxpy-engine").apply { isDaemon = true }
    }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var modrootPath: String = ""

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private fun ensureHandle() {
        if (handle == 0L) {
            handle = PyNative.nativeNew()
            // same runaway-loop guard as the other engines
            PyNative.nativeSetStepLimit(handle, 50_000_000L)
            if (modrootPath.isNotEmpty()) PyNative.nativeSetModroot(handle, modrootPath)
        }
    }

    /** Module search root for `import` (project source dir); mirrors
     *  [EngineHost.setModuleRoot]. Applies on the next engine handle. */
    fun setModuleRoot(path: String) {
        modrootPath = path
        val h = handle
        if (h != 0L) PyNative.nativeSetModroot(h, path)
    }

    override suspend fun run(src: String): RunResult {
        _state.value = EngineState.Running
        val result = withContext(dispatcher) {
            execute { PyNative.nativeRun(handle, src) }
        }
        return result
    }

    override suspend fun invoke(handlerId: Int, payload: String?): RunResult {
        val result = withContext(dispatcher) {
            execute { PyNative.nativeInvoke(handle, handlerId, payload) }
        }
        return result
    }

    override fun cancel() {
        val h = handle
        if (h != 0L) PyNative.nativeCancel(h)
    }

    private inline fun execute(block: () -> Array<String>): RunResult = runCatching {
        ensureHandle()
        val res = block()
        val status = res.getOrElse(0) { "1" }
        val err = res.getOrElse(1) { "" }
        val treeJson = res.getOrElse(2) { "" }
        val output = PyNative.nativeTakeOutput(handle)
        if (status == "0") {
            RunResult(ok = true, tree = UiTreeParser.parse(treeJson), output = output, error = null)
        } else {
            val message = err.ifEmpty { "unknown error" }
            RunResult(ok = false, tree = null, output = output, error = message, errorLine = parseErrorLine(message))
        }
    }.getOrElse { t ->
        RunResult(ok = false, tree = null, output = "", error = t.message ?: "engine failure")
    }.also { result ->
        logSink?.let { sink ->
            if (result.output.isNotEmpty()) {
                sink.logLines(LogLevel.INFO, LogSource.PY, result.output, tag = "print")
            }
            if (!result.ok && result.error != null) {
                sink.log(LogLevel.ERROR, LogSource.ENGINE, result.error, tag = "run", line = result.errorLine)
            }
        }
        _state.value = when {
            !result.ok -> EngineState.Error(result.error ?: "unknown error")
            result.tree != null -> EngineState.Ready(result.tree)
            else -> EngineState.Idle
        }
    }

    override fun close() {
        scope.launch {
            if (handle != 0L) {
                PyNative.nativeClose(handle)
                handle = 0L
            }
        }
        worker.shutdown()
    }

    private companion object {
        fun parseErrorLine(message: String?): Int? = null
    }
}
