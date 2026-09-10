package dev.luaxide.engine

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
 * JavaScript engine host (QuickJS) — the :runtime packaging variant.
 * Implements the same [EngineAdapter] contract as the runtime [EngineHost],
 * so a packaged app runs whichever language its entry file uses (see
 * docs/PLATFORM_ABI.md). Output surfaces through the packaged console view;
 * debugging, the REPL and blocking stdin stay Lua-only.
 */
class JsEngineHost : EngineAdapter {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "luaxjs-engine").apply { isDaemon = true }
    }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var handle: Long = 0L

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private fun ensureHandle() {
        if (handle == 0L) {
            handle = JsNative.nativeNew()
            // same runaway-loop guard as the Lua engine's default
            JsNative.nativeSetStepLimit(handle, 50_000_000L)
        }
    }

    override suspend fun run(src: String): RunResult {
        _state.value = EngineState.Running
        val result = withContext(dispatcher) {
            recreate()
            execute { JsNative.nativeRun(handle, src) }
        }
        return result
    }

    override suspend fun invoke(handlerId: Int, payload: String?): RunResult {
        val result = withContext(dispatcher) {
            execute { JsNative.nativeInvoke(handle, handlerId, payload) }
        }
        return result
    }

    override fun cancel() {
        val h = handle
        if (h != 0L) JsNative.nativeCancel(h)
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
    }.also { result ->
        _state.value = when {
            !result.ok -> EngineState.Error(result.error ?: "unknown error")
            result.tree != null -> EngineState.Ready(result.tree)
            else -> EngineState.Idle
        }
    }

    private fun recreate() {
        if (handle != 0L) {
            JsNative.nativeClose(handle)
            handle = 0L
        }
        ensureHandle()
    }

    override fun close() {
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
