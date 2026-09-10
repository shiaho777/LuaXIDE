package dev.luaxide.engine

/**
 * Thin JNI surface over the LuaXIDE Python engine (libluaxpy.so, MicroPython
 * facade). Same Array<String>{status, error, treeJson} convention as
 * [LuaxNative] / [JsNative]; failures are returned as data, never thrown.
 */
internal object PyNative {
    init {
        System.loadLibrary("luaxpy")
    }

    external fun nativeNew(): Long
    external fun nativeClose(handle: Long)
    external fun nativeRun(handle: Long, src: String): Array<String>
    external fun nativeInvoke(handle: Long, handlerId: Int, payload: String?): Array<String>
    external fun nativeCancel(handle: Long)
    external fun nativeClearCancel(handle: Long)
    external fun nativeSetStepLimit(handle: Long, steps: Long)
    external fun nativeTakeOutput(handle: Long): String
    external fun nativeClearOutput(handle: Long)
}
