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
 * JavaScript engine host (QuickJS). Mirrors [EngineHost]'s run/invoke contract
 * so the shell can treat Lua and JS projects uniformly. Debug/stdin/proot are
 * Lua-only for now; JS v1 covers run → preview tree + print → terminal.
 */
class JsEngineHost(
    private val logSink: LogSink? = null,
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "luaxjs-engine").apply { isDaemon = true }
    }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var handle: Long = 0L

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private fun ensureHandle() {
        if (handle == 0L) handle = JsNative.nativeNew()
    }

    suspend fun run(src: String): RunResult {
        _state.value = EngineState.Running
        val result = withContext(dispatcher) {
            recreate()
            execute { JsNative.nativeRun(handle, src) }
        }
        publish(result)
        return result
    }

    suspend fun invoke(handlerId: Int, payload: String? = null): RunResult {
        val result = withContext(dispatcher) {
            execute { JsNative.nativeInvoke(handle, handlerId, payload) }
        }
        publish(result)
        return result
    }

    fun cancel() {
        // QuickJS runs synchronously on the worker thread; there is no
        // interrupt hook wired yet. Kept for interface parity.
    }

    private inline fun execute(block: () -> Array<String>): RunResult = runCatching {
        ensureHandle()
        val res = block()
        val status = res.getOrElse(0) { "1" }
        val err = res.getOrElse(1) { "" }
        val treeJson = res.getOrElse(2) { "" }
        val output = JsNative.nativeTakeOutput(handle)
        if (status == "0") {
            RunResult(ok = true, tree = UiTreeParser.parse(treeJson), output = output, error = null)
        } else {
            val message = err.ifEmpty { "unknown error" }
            RunResult(ok = false, tree = null, output = output, error = message, errorLine = parseErrorLine(message))
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
            JsNative.nativeClose(handle)
            handle = 0L
        }
        ensureHandle()
    }

    fun close() {
        scope.launch {
            if (handle != 0L) {
                JsNative.nativeClose(handle)
                handle = 0L
            }
        }
        worker.shutdown()
    }

    private companion object {
        val LINE_PREFIX = Regex("""^line (\d+):""")
        fun parseErrorLine(message: String?): Int? =
            message?.let { LINE_PREFIX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }
}
