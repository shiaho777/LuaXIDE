package dev.luaxide.ui.editor

object BreakpointRemap {
    fun remap(oldText: String, newText: String, breakpoints: Set<Int>): Set<Int> {
        if (breakpoints.isEmpty() || oldText == newText) return breakpoints
        val mapping = lineMapping(oldText, newText)
        val out = linkedSetOf<Int>()
        for (bp in breakpoints) {
            val mapped = mapping[bp]
            if (mapped != null && mapped > 0) out += mapped
        }
        return out
    }

    fun remapMap(oldText: String, newText: String, breakpoints: Map<Int, String>): Map<Int, String> {
        if (breakpoints.isEmpty() || oldText == newText) return breakpoints
        val mapping = lineMapping(oldText, newText)
        val out = linkedMapOf<Int, String>()
        breakpoints.forEach { (line, cond) ->
            val mapped = mapping[line]
            if (mapped != null && mapped > 0) out[mapped] = cond
        }
        return out
    }

    private fun lineMapping(oldText: String, newText: String): Map<Int, Int> {
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)
        if (oldLines.isEmpty() || newLines.isEmpty()) return emptyMap()
        val mapping = mapLines(oldLines, newLines)
        val out = linkedMapOf<Int, Int>()
        for (i in mapping.indices) {
            val m = mapping[i]
            if (m >= 0) out[i + 1] = m + 1
        }
        return out
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = text.split('\n')
        return if (text.endsWith('\n')) lines.dropLast(1).ifEmpty { listOf("") } else lines
    }

    private fun mapLines(oldLines: List<String>, newLines: List<String>): IntArray {
        val n = oldLines.size
        val m = newLines.size
        val map = IntArray(n) { -1 }

        var i = 0
        var j = 0
        while (i < n && j < m && oldLines[i] == newLines[j]) {
            map[i] = j
            i++
            j++
        }
        var i2 = n - 1
        var j2 = m - 1
        while (i2 >= i && j2 >= j && oldLines[i2] == newLines[j2]) {
            map[i2] = j2
            i2--
            j2--
        }

        if (i > i2) return map

        val midOld = i2 - i + 1
        val midNew = j2 - j + 1
        if (midOld <= 0) return map
        if (midNew <= 0) return map

        if (midOld * midNew > 250_000) {
            for (k in i..i2) {
                val rel = (k - i).toDouble() / midOld.toDouble()
                val ni = j + (rel * midNew).toInt()
                if (ni in j..j2) map[k] = ni
            }
            return map
        }

        val lcs = Array(midOld + 1) { IntArray(midNew + 1) }
        for (a in 1..midOld) {
            for (b in 1..midNew) {
                lcs[a][b] = if (oldLines[i + a - 1] == newLines[j + b - 1]) {
                    lcs[a - 1][b - 1] + 1
                } else {
                    maxOf(lcs[a - 1][b], lcs[a][b - 1])
                }
            }
        }
        var a = midOld
        var b = midNew
        val pairs = ArrayList<Pair<Int, Int>>()
        while (a > 0 && b > 0) {
            when {
                oldLines[i + a - 1] == newLines[j + b - 1] -> {
                    pairs.add(Pair(i + a - 1, j + b - 1))
                    a--
                    b--
                }
                lcs[a - 1][b] >= lcs[a][b - 1] -> a--
                else -> b--
            }
        }
        for ((oi, ni) in pairs) map[oi] = ni
        return map
    }
}
