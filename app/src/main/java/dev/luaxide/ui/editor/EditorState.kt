package dev.luaxide.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** A single undoable snapshot of the buffer. */
private data class Snapshot(val text: String, val selection: TextRange, val at: Long)

/** A found match range in the buffer (start inclusive, end exclusive). */
data class Match(val start: Int, val end: Int)

/**
 * A pending "jump to line" navigation request (e.g. from tapping an error in
 * the log panel). [id] is a monotonic tiebreaker so requesting the same line
 * twice in a row still fires — a plain (line) value would dedupe as "unchanged"
 * in a StateFlow.
 */
data class JumpRequest(val line: Int, val id: Long)

/**
 * Editor buffer with undo/redo and find/replace, decoupled from Compose UI.
 *
 * Undo granularity: edits within [MERGE_WINDOW_MS] of each other coalesce into
 * one checkpoint unless a boundary character (newline/whitespace after a word)
 * was crossed, so undo steps feel word/line-sized rather than per-keystroke.
 *
 * External sync: when the host loads a different file, [syncExternal] resets the
 * buffer and clears history so undo can't cross file boundaries.
 */
class EditorState(
    initial: String = "",
    private val onTextChange: (String) -> Unit = {},
) {
    var value by mutableStateOf(TextFieldValue(initial))
        private set

    val text: String get() = value.text

    // Invariant: undoStack is never empty and its last element always equals the
    // committed [value]. redoStack holds states undone-away-from.
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastCommitAt = 0L

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val find = FindState()

    init {
        undoStack.addLast(Snapshot(initial, TextRange(initial.length), 0L))
        refreshFlags()
    }

    /** Called on every edit from the text field. */
    fun onValueChange(new: TextFieldValue) {
        val old = value
        if (new.text != old.text) {
            value = new
            commit(new)
            onTextChange(new.text)
            find.recompute(new.text)
        } else {
            // selection / cursor move only — no history, no reparse
            value = new
        }
    }

    /** Record [snap] as the new tip, merging into the previous tip when adjacent. */
    private fun commit(snap: TextFieldValue) {
        val now = System.currentTimeMillis()
        val prevTip = undoStack.last()
        val merge = (now - lastCommitAt) < MERGE_WINDOW_MS && !endsAtBoundary(prevTip.text)
        val s = Snapshot(snap.text, snap.selection, now)
        if (merge) {
            undoStack.removeLast()
            undoStack.addLast(s)
        } else {
            undoStack.addLast(s)
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        }
        lastCommitAt = now
        redoStack.clear()
        refreshFlags()
    }

    fun undo() {
        if (undoStack.size <= 1) return
        val tip = undoStack.removeLast()
        redoStack.addLast(tip)
        val restore = undoStack.last()
        applyRestored(restore)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(next)
        applyRestored(next)
    }

    private fun applyRestored(snap: Snapshot) {
        value = TextFieldValue(snap.text, snap.selection.coerceIn(snap.text.length))
        onTextChange(snap.text)
        find.recompute(snap.text)
        lastCommitAt = 0L
        refreshFlags()
    }

    /**
     * Insert [snippet] at the cursor (replacing any selection). Multi-line
     * snippets are re-indented to the current line's leading whitespace so
     * pasted components line up with their surroundings. Undoable.
     */
    fun insertText(snippet: String) {
        val text = value.text
        val sel = value.selection
        val start = minOf(sel.start, sel.end).coerceIn(0, text.length)
        val end = maxOf(sel.start, sel.end).coerceIn(0, text.length)

        // leading whitespace of the line the cursor sits on
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val indent = text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }

        val indented = snippet.split('\n').mapIndexed { i, line ->
            if (i == 0) line else indent + line
        }.joinToString("\n")

        val newText = text.substring(0, start) + indented + text.substring(end)
        val caret = start + indented.length
        commitReplace(newText, caret)
        find.recompute(newText)
    }

    /**
     * Move the cursor to the start of [line] (1-based) and select the whole
     * line so it's visible at a glance. Silently clamps out-of-range lines
     * rather than throwing — a stale error position should never crash the
     * editor.
     */
    fun jumpToLine(line: Int) {
        val text = value.text
        if (text.isEmpty()) return
        var start = 0
        var current = 1
        while (current < line && start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) { start = text.length; break }
            start = nl + 1
            current++
        }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        value = value.copy(selection = TextRange(start, end))
    }


    fun replaceRange(start: Int, end: Int, insert: String) {
        val text = value.text
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val newText = text.substring(0, s) + insert + text.substring(e)
        val caret = s + insert.length
        commitReplace(newText, caret)
        find.recompute(newText)
    }

    fun applyCompletion(prefixStart: Int, insert: String) {
        val caret = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        replaceRange(prefixStart, caret, insert)
    }

    fun selectRange(start: Int, end: Int) {
        val len = value.text.length
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        value = value.copy(selection = TextRange(s, e))
    }

    fun jumpToOffset(offset: Int) {
        val text = value.text
        if (text.isEmpty()) return
        val o = offset.coerceIn(0, text.length)
        var end = o
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '.')) end++
        value = value.copy(selection = TextRange(o, end))
    }

    /** Replace the whole buffer from outside (e.g. opening a different file). Clears history. */
    fun syncExternal(newText: String) {
        if (newText == value.text) return
        value = TextFieldValue(newText, TextRange(0))
        undoStack.clear()
        redoStack.clear()
        undoStack.addLast(Snapshot(newText, TextRange(0), 0L))
        lastCommitAt = 0L
        find.recompute(newText)
        refreshFlags()
    }

    private fun refreshFlags() {
        canUndo = undoStack.size > 1
        canRedo = redoStack.isNotEmpty()
    }

    private fun endsAtBoundary(t: String): Boolean =
        t.isNotEmpty() && (t.last() == '\n' || t.last().isWhitespace())

    // ---- find / replace ----

    /** Move selection to the current match so the field scrolls it into view. */
    private fun selectMatch(m: Match) {
        value = value.copy(selection = TextRange(m.start, m.end))
    }

    /** Commit a replace edit as an undoable checkpoint (always a boundary). */
    private fun commitReplace(newText: String, caret: Int) {
        value = TextFieldValue(newText, TextRange(caret.coerceIn(0, newText.length)))
        lastCommitAt = 0L // force a distinct checkpoint (never merge replaces)
        commit(value)
        onTextChange(newText)
    }

    inner class FindState {
        var visible by mutableStateOf(false)
            private set
        var query by mutableStateOf("")
            private set
        var replacement by mutableStateOf("")
        var useRegex by mutableStateOf(false)
            private set
        var matches by mutableStateOf<List<Match>>(emptyList())
            private set
        var current by mutableStateOf(-1)
            private set

        fun toggle() {
            visible = !visible
            if (visible) recompute(text) else { matches = emptyList(); current = -1 }
        }

        fun close() {
            visible = false
            matches = emptyList()
            current = -1
        }

        fun updateQuery(q: String) {
            query = q
            recompute(text)
        }

        fun updateRegex(on: Boolean) {
            useRegex = on
            recompute(text)
        }

        fun recompute(src: String) {
            if (!visible || query.isEmpty()) { matches = emptyList(); current = -1; return }
            val found = buildList {
                if (useRegex) {
                    val re = runCatching { Regex(query) }.getOrNull() ?: return@buildList
                    for (m in re.findAll(src)) {
                        if (m.value.isEmpty()) continue
                        add(Match(m.range.first, m.range.last + 1))
                    }
                } else {
                    var i = src.indexOf(query, 0, ignoreCase = true)
                    while (i >= 0) {
                        add(Match(i, i + query.length))
                        i = src.indexOf(query, i + query.length, ignoreCase = true)
                    }
                }
            }
            matches = found
            current = if (found.isEmpty()) -1 else current.coerceIn(0, found.lastIndex)
            if (current >= 0) selectMatch(found[current])
        }

        fun next() {
            if (matches.isEmpty()) return
            current = (current + 1) % matches.size
            selectMatch(matches[current])
        }

        fun prev() {
            if (matches.isEmpty()) return
            current = (current - 1 + matches.size) % matches.size
            selectMatch(matches[current])
        }

        fun replaceCurrent() {
            val idx = current
            if (idx !in matches.indices) return
            val m = matches[idx]
            val newText = value.text.substring(0, m.start) + replacement + value.text.substring(m.end)
            commitReplace(newText, m.start + replacement.length)
            recompute(newText)
        }

        fun replaceAll() {
            if (matches.isEmpty()) return
            // replace from the end so earlier offsets stay valid
            var t = value.text
            for (m in matches.sortedByDescending { it.start }) {
                t = t.substring(0, m.start) + replacement + t.substring(m.end)
            }
            commitReplace(t, t.length)
            recompute(t)
        }
    }

    private companion object {
        const val MERGE_WINDOW_MS = 600L
        const val MAX_HISTORY = 200
    }
}

private fun TextRange.coerceIn(len: Int): TextRange =
    TextRange(start.coerceIn(0, len), end.coerceIn(0, len))
