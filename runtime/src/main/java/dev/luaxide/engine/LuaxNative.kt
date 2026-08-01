package dev.luaxide.engine

/**
 * Thin JNI surface over the LuaX C engine (libluax.so).
 *
 * All methods take an opaque [handle] (a native lx_State*). Callers must
 * serialize access to a single dedicated thread; the native side does no
 * locking. Nothing here throws for Lua-level errors: failures are returned
 * as data so a bad script can never crash the process.
 */
internal object LuaxNative {
    init {
        System.loadLibrary("luax")
    }

    /** Create a fresh engine state. Returns a native handle (0 = failure). */
    external fun nativeNew(): Long

    /** Destroy an engine state. Safe to call with 0. */
    external fun nativeClose(handle: Long)

    /** Cap interpreter steps per run (0 = unlimited). Guards infinite loops. */
    external fun nativeSetStepLimit(handle: Long, steps: Long)

    /**
     * Run [src]. Returns String[3]: [status("0"|"1"), errorMessage, treeJson].
     * Captured print output is fetched via [nativeTakeOutput].
     */
    external fun nativeRun(handle: Long, src: String): Array<String>

    /** Invoke a registered event handler by id. Same String[3] layout as [nativeRun]. */
    external fun nativeInvoke(handle: Long, handlerId: Int): Array<String>

    /** Captured print output accumulated since the last run/invoke. */
    external fun nativeTakeOutput(handle: Long): String
    external fun nativeClearOutput(handle: Long)
    external fun nativeRepl(handle: Long, src: String): Array<String>
    external fun nativeCancel(handle: Long)
    external fun nativeClearCancel(handle: Long)
    external fun nativeIsCancelled(handle: Long): Boolean
    external fun nativePushStdin(handle: Long, line: String)
    external fun nativeWaitingStdin(handle: Long): Boolean
    external fun nativeSetRootfs(handle: Long, path: String)
    external fun nativeSetModroot(handle: Long, path: String)
}
