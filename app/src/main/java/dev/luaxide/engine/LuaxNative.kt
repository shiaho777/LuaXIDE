package dev.luaxide.engine

object LuaxNative {
    init {
        System.loadLibrary("luax")
    }

    external fun nativeNew(): Long
    external fun nativeClose(handle: Long)
    external fun nativeSetStepLimit(handle: Long, steps: Long)
    external fun nativeRun(handle: Long, src: String): Array<String>
    external fun nativeInvoke(handle: Long, handlerId: Int, payload: String?): Array<String>
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
    external fun nativeSetLogger(logger: Any?)

    external fun nativeDebugEnable(handle: Long, enabled: Boolean)
    external fun nativeDebugSetBreakOnError(handle: Long, enabled: Boolean)
    external fun nativeDebugSetBreakpoints(handle: Long, lines: IntArray)
    external fun nativeDebugSetBreakpointsEx(handle: Long, lines: IntArray, conds: Array<String>)
    external fun nativeDebugSetBreakpointsFull(handle: Long, lines: IntArray, conds: Array<String>, logs: Array<String>, logOnly: BooleanArray)
    external fun nativeDebugContinue(handle: Long)
    external fun nativeDebugStep(handle: Long)
    external fun nativeDebugStepOut(handle: Long)
    external fun nativeDebugStop(handle: Long)
    external fun nativeDebugIsPaused(handle: Long): Boolean
    external fun nativeDebugPauseLine(handle: Long): Int
    external fun nativeDebugPauseReason(handle: Long): Int
    external fun nativeDebugLastError(handle: Long): String
    external fun nativeDebugLocals(handle: Long): String
    external fun nativeDebugStack(handle: Long): String
    external fun nativeDebugEval(handle: Long, expr: String): String
}
