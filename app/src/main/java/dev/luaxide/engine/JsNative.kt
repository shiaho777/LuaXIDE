package dev.luaxide.engine

object JsNative {
    init {
        System.loadLibrary("luaxjs")
    }

    external fun nativeNew(): Long
    external fun nativeClose(handle: Long)
    external fun nativeRun(handle: Long, src: String): Array<String>
    external fun nativeInvoke(handle: Long, handlerId: Int, payload: String?): Array<String>
    external fun nativeTakeOutput(handle: Long): String
    external fun nativeClearOutput(handle: Long)
}
