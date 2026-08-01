package dev.luaxide.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class LogFilter(
    val levels: Set<LogLevel> = LogLevel.entries.toSet(),
    val query: String = "",
) {
    @Transient
    private val regex: Regex? = if (query.isBlank()) null
    else runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()

    fun matches(entry: LogEntry): Boolean {
        if (entry.level !in levels) return false
        if (query.isBlank()) return true
        val r = regex
        return if (r != null) {
            r.containsMatchIn(entry.message) || r.containsMatchIn(entry.tag)
        } else {
            entry.message.contains(query, ignoreCase = true) ||
                entry.tag.contains(query, ignoreCase = true)
        }
    }
}

class LogStore(
    scope: CoroutineScope,
    private val capacity: Int = 10_000,
) : LogSink {

    private val seq = AtomicLong(0)
    private val buffer = ArrayDeque<LogEntry>(capacity)
    private val lock = Any()

    private val _raw = MutableStateFlow<List<LogEntry>>(emptyList())

    private val _filter = MutableStateFlow(LogFilter())
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _droppedWhilePaused = MutableStateFlow(0)
    val droppedWhilePaused: StateFlow<Int> = _droppedWhilePaused.asStateFlow()

    val entries: StateFlow<List<LogEntry>> =
        combine(_raw, _filter) { raw, filter ->
            if (filter.levels.size == LogLevel.entries.size && filter.query.isBlank()) {
                raw
            } else {
                raw.filter(filter::matches)
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val rawCount: StateFlow<Int> = _raw
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    override fun log(
        level: LogLevel,
        source: LogSource,
        message: String,
        tag: String,
        stack: String?,
        line: Int?,
    ) {
        if (_paused.value) {
            _droppedWhilePaused.value += 1
            return
        }
        val entry = LogEntry(
            seq = seq.getAndIncrement(),
            time = System.currentTimeMillis(),
            level = level,
            source = source,
            tag = tag,
            message = message,
            stack = stack,
            line = line,
        )
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.pollFirst()
            buffer.addLast(entry)
            _raw.value = buffer.toList()
        }
    }

    fun snapshot(filtered: Boolean = false): List<LogEntry> {
        val raw = synchronized(lock) { buffer.toList() }
        return if (filtered) raw.filter(_filter.value::matches) else raw
    }

    fun exportText(filtered: Boolean = false): String {
        val entries = snapshot(filtered)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder(entries.size * 64)
        entries.forEach { e ->
            sb.append(fmt.format(Date(e.time)))
                .append(' ')
                .append(e.level.short)
                .append('/')
                .append(e.source.label)
                .append(' ')
            if (e.tag.isNotEmpty()) sb.append(e.tag).append(": ")
            sb.append(e.message)
            if (e.line != null) sb.append(" (line ").append(e.line).append(')')
            sb.append('\n')
            if (!e.stack.isNullOrBlank()) {
                sb.append(e.stack).append('\n')
            }
        }
        return sb.toString()
    }

    fun exportJson(filtered: Boolean = false): String {
        val entries = snapshot(filtered)
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("seq", e.seq)
                    .put("time", e.time)
                    .put("level", e.level.name)
                    .put("source", e.source.name)
                    .put("tag", e.tag)
                    .put("message", e.message)
                    .put("line", e.line)
                    .put("stack", e.stack),
            )
        }
        return arr.toString(2)
    }

    fun setLevels(levels: Set<LogLevel>) {
        _filter.value = _filter.value.copy(levels = levels)
    }

    fun toggleLevel(level: LogLevel) {
        val cur = _filter.value.levels
        val next = if (level in cur) cur - level else cur + level
        _filter.value = _filter.value.copy(levels = next)
    }

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    fun setPaused(paused: Boolean) {
        _paused.value = paused
        if (!paused) _droppedWhilePaused.value = 0
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _raw.value = emptyList()
        }
        _droppedWhilePaused.value = 0
    }
}
