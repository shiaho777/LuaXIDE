package dev.luaxide.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Host contract the LuaX engine implements — the Kotlin mirror of
 * docs/PLATFORM_ABI.md. The engine emits ui-tree JSON from this surface;
 * debugging, the REPL, blocking stdin and the proot rootfs stay on
 * [EngineHost] and must not leak into generic callers.
 */
interface EngineAdapter {
    /** Run-state stream (Idle / Running / Ready(tree) / Error). */
    val state: StateFlow<EngineState>

    /** Run [src]; the result carries the ui tree, captured output and errors. */
    suspend fun run(src: String): RunResult

    /**
     * Invoke an event handler previously serialized as {"__handler": id};
     * [payload] (when non-null) is passed to the handler as its first
     * argument. If the handler returns a ui tree it replaces the view,
     * otherwise the previous tree is kept.
     */
    suspend fun invoke(handlerId: Int, payload: String? = null): RunResult

    /** Cooperatively stop the running script ("cancelled by user"). */
    fun cancel()

    /** Release native resources; safe to call once on teardown. */
    fun close()
}
