package dev.luaxide.log

/**
 * The single entry point for anything that produces logs (engine, build
 * pipeline, IDE internals). Kept deliberately small so producers depend only on
 * this contract, not on the concrete [LogStore]. This is the integration seam
 * named in the roadmap.
 */
interface LogSink {
    fun log(
        level: LogLevel,
        source: LogSource,
        message: String,
        tag: String = "",
        stack: String? = null,
        line: Int? = null,
    )

    /** Emit each non-blank line of [text] as a separate entry (e.g. captured print output). */
    fun logLines(level: LogLevel, source: LogSource, text: String, tag: String = "") {
        text.lineSequence()
            .filter { it.isNotEmpty() }
            .forEach { line -> log(level, source, line, tag) }
    }
}
