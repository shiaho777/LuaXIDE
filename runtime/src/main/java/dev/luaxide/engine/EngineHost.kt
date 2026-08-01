package dev.luaxide.engine

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class RunResult(
    val ok: Boolean,
    val tree: UiNode?,
    val output: String,
    val error: String?,
    val errorLine: Int? = null,
)

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
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "luax-engine").apply { isDaemon = true }
    }
    private val dispatcher = worker.asCoroutineDispatcher()

    @Volatile
    private var handle: Long = 0L
    @Volatile private var rootfsPath: String = ""
    @Volatile private var modrootPath: String = ""

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private fun ensureHandle() {
        if (handle == 0L) {
            handle = LuaxNative.nativeNew()
            LuaxNative.nativeSetStepLimit(handle, stepLimit)
        }
    }

    suspend fun run(src: String): RunResult {
        _state.value = EngineState.Running
        val result = withContext(dispatcher) {
            recreate()
            execute {
                LuaxNative.nativeClearCancel(handle)
                LuaxNative.nativeRun(handle, src)
            }
        }
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
            RunResult(ok = false, tree = null, output = output, error = message, errorLine = parseErrorLine(message))
        }
    }.getOrElse { t ->
        RunResult(ok = false, tree = null, output = "", error = t.message ?: "engine failure")
    }

    private fun publish(result: RunResult) {
        _state.value = if (result.ok) EngineState.Ready(result.tree)
        else EngineState.Error(result.error ?: "unknown error")
    }

    private fun recreate() {
        if (handle != 0L) {
            LuaxNative.nativeClose(handle)
            handle = 0L
        }
        ensureHandle()
    }

    fun close() {
        if (handle != 0L) {
            LuaxNative.nativeClose(handle)
            handle = 0L
        }
        worker.shutdown()
    }
}
