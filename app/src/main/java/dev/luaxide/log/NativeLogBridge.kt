package dev.luaxide.log

import android.os.Process
import dev.luaxide.engine.LuaxNative
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class NativeLogBridge(
    private val sink: LogSink,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val jniLogger = object {
        @Suppress("unused")
        fun onNativeLog(level: Int, tag: String?, message: String?) {
            val msg = message ?: return
            if (msg.isBlank()) return
            sink.log(mapLevel(level), LogSource.NATIVE, msg, tag = tag ?: "luax")
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        runCatching {
            LuaxNative.nativeSetLogger(jniLogger)
        }.onFailure {
            sink.log(
                LogLevel.WARNING,
                LogSource.SYSTEM,
                "native logger attach failed: ${it.message}",
                tag = "native",
            )
        }
        thread = Thread({ readLogcat() }, "luax-logcat").apply {
            isDaemon = true
            start()
        }
        sink.log(LogLevel.INFO, LogSource.NATIVE, "native log bridge started", tag = "logcat")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { LuaxNative.nativeSetLogger(null) }
        thread?.interrupt()
        thread = null
    }

    private fun readLogcat() {
        val pid = Process.myPid().toString()
        val cmd = arrayOf(
            "logcat",
            "--pid=$pid",
            "-v", "brief",
            "luax:S",
            "libc:W",
            "DEBUG:I",
            "AndroidRuntime:E",
            "*:S",
        )
        var process: java.lang.Process? = null
        try {
            process = Runtime.getRuntime().exec(cmd)
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    ingestLogcatLine(line)
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                sink.log(
                    LogLevel.WARNING,
                    LogSource.SYSTEM,
                    "logcat reader stopped: ${t.message}",
                    tag = "logcat",
                )
            }
        } finally {
            process?.destroy()
        }
    }

    private fun ingestLogcatLine(line: String) {
        if (line.isBlank() || line.startsWith("---------")) return
        val level = when {
            line.length >= 2 && line[1] == '/' -> when (line[0]) {
                'V' -> LogLevel.VERBOSE
                'D' -> LogLevel.DEBUG
                'I' -> LogLevel.INFO
                'W' -> LogLevel.WARNING
                'E', 'F' -> LogLevel.ERROR
                else -> LogLevel.DEBUG
            }
            else -> LogLevel.DEBUG
        }
        val slash = line.indexOf('/')
        val colon = line.indexOf(':', startIndex = if (slash >= 0) slash else 0)
        val tag = if (slash >= 0 && colon > slash) {
            line.substring(slash + 1, colon).substringBefore('(').trim()
        } else {
            "logcat"
        }
        val msg = if (colon >= 0 && colon + 1 < line.length) line.substring(colon + 1).trim() else line
        if (msg.isEmpty()) return
        sink.log(level, LogSource.NATIVE, msg, tag = tag)
    }

    private fun mapLevel(level: Int): LogLevel = when (level) {
        0 -> LogLevel.VERBOSE
        1 -> LogLevel.DEBUG
        2 -> LogLevel.INFO
        3 -> LogLevel.WARNING
        else -> LogLevel.ERROR
    }
}
