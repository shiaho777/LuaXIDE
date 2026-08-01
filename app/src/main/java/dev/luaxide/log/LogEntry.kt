package dev.luaxide.log

/** Severity of a log entry, ordered from least to most severe. */
enum class LogLevel(val label: String, val short: Char) {
    VERBOSE("verbose", 'V'),
    DEBUG("debug", 'D'),
    INFO("info", 'I'),
    WARNING("warning", 'W'),
    ERROR("error", 'E'),
}

/** Where a log entry originated. */
enum class LogSource(val label: String) {
    LUA("lua"),       // user's print / error output
    ENGINE("engine"), // interpreter runtime errors, step limits
    NATIVE("native"), // NDK logcat bridge (future)
    SYSTEM("system"), // IDE-internal notices
}

/**
 * A single log line. Immutable — the store only appends and evicts. [seq] is a
 * monotonically increasing id used as the stable list key so Compose can animate
 * placement without diff churn.
 */
data class LogEntry(
    val seq: Long,
    val time: Long,
    val level: LogLevel,
    val source: LogSource,
    val tag: String,
    val message: String,
    val stack: String? = null,
    /** Source line the entry refers to (1-based), if known — enables jump-to-line in the editor. */
    val line: Int? = null,
)
