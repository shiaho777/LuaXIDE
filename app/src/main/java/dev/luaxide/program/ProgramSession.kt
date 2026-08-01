package dev.luaxide.program

data class TermLine(
    val kind: Kind,
    val text: String,
    val id: Long = nextId(),
) {
    enum class Kind { Prompt, Input, Output, Error, Meta }

    companion object {
        private val seq = java.util.concurrent.atomic.AtomicLong(1)
        private fun nextId(): Long = seq.getAndIncrement()
    }
}

data class ProgramSession(
    val lines: List<TermLine> = emptyList(),
    val running: Boolean = false,
    val lastExitOk: Boolean? = null,
    val sandboxLabel: String = "no-root · app sandbox",
) {
    fun append(vararg more: TermLine): ProgramSession =
        copy(lines = (lines + more.toList()).takeLast(MAX_LINES))

    fun clearKeepBanner(): ProgramSession =
        copy(
            lines = emptyList(),
            lastExitOk = null,
            running = false,
        )

    companion object {
        const val MAX_LINES = 2000

        fun boot(sandboxLabel: String): ProgramSession =
            ProgramSession(
                lines = emptyList(),
                sandboxLabel = sandboxLabel,
            )
    }
}
