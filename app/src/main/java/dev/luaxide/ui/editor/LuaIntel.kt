package dev.luaxide.ui.editor

data class LuaSymbol(
    val name: String,
    val kind: Kind,
    val line: Int,
    val start: Int,
) {
    enum class Kind { Function, Local, Global }
}

data class CompletionItem(
    val label: String,
    val insert: String,
    val detail: String = "",
)

object LuaIntel {
    val KEYWORDS = setOf(
        "local", "function", "end", "return", "if", "then", "else", "elseif",
        "for", "do", "while", "repeat", "until", "and", "or", "not", "nil",
        "true", "false", "in", "break", "goto",
    )

    private val SNIPPETS = listOf(
        CompletionItem("print", "print()", "内置"),
        CompletionItem("require", "require(\"\")", "内置"),
        CompletionItem("pairs", "pairs()", "内置"),
        CompletionItem("ipairs", "ipairs()", "内置"),
        CompletionItem("tostring", "tostring()", "内置"),
        CompletionItem("tonumber", "tonumber()", "内置"),
        CompletionItem("type", "type()", "内置"),
        CompletionItem("io.read", "io.read()", "io"),
        CompletionItem("io.write", "io.write()", "io"),
        CompletionItem("ui.app", "ui.app {\n    \n}", "ui"),
        CompletionItem("ui.column", "ui.column {\n    \n}", "ui"),
        CompletionItem("ui.row", "ui.row {\n    \n}", "ui"),
        CompletionItem("ui.box", "ui.box { width = 240, height = 80,\n    ui.text { text = \"overlay\", align = \"center\" },\n}", "ui · 叠放容器"),
        CompletionItem("ui.text", "ui.text { text = \"\" }", "ui"),
        CompletionItem("ui.button", "ui.button {\n    text = \"\",\n    onClick = function()\n    end,\n}", "ui"),
        CompletionItem("ui.card", "ui.card {\n    \n}", "ui"),
        CompletionItem("ui.input", "ui.input {\n    label = \"\",\n    value = \"\",\n    onChange = function(text)\n        print(text)\n    end,\n    onSubmit = function(text)\n        print(text)\n    end,\n}", "ui"),
        CompletionItem("ui.image", "ui.image { src = \"assets/images/logo.png\", size = 96 }", "ui"),
        CompletionItem("ui.spacer", "ui.spacer { size = 12 }", "ui"),
        CompletionItem("ui.divider", "ui.divider {}", "ui"),
        CompletionItem("ui.scrollview", "ui.scrollview { height = 240,\n    ui.text(\"scrollable\"),\n}", "ui"),
        CompletionItem("ui.list", "ui.list {\n    \n}", "ui"),
        CompletionItem("ui.listitem", "ui.listitem {\n    title = \"\",\n    subtitle = \"\",\n    onClick = function()\n    end,\n}", "ui"),
        CompletionItem("ui.stack", "ui.stack { selected = \"home\",\n    ui.page { key = \"home\", ui.text(\"Home\") },\n}", "ui"),
        CompletionItem("ui.page", "ui.page { key = \"home\",\n    ui.text(\"Home\"),\n}", "ui"),
        CompletionItem("ui.switch", "ui.switch {\n    label = \"\",\n    checked = false,\n    onToggle = function(v)\n    end,\n}", "ui"),
        CompletionItem("ui.slider", "ui.slider { value = 0.4, from = 0, to = 1, step = 0.1,\n    onChange = function(value) print(tonumber(value)) end,\n}", "ui · 字符串数值回调；状态联动见 API view 示例"),
        CompletionItem("ui.progress", "ui.progress { value = 0.4 }", "ui · 0..1；省略 value 为不定态"),
        CompletionItem("string.format", "string.format(\"%s = %d\", \"a\", 1)", "string"),
        CompletionItem("string.find", "string.find(s, pat)", "string"),
        CompletionItem("string.gsub", "string.gsub(s, pat, repl)", "string"),
        CompletionItem("string.match", "string.match(s, pat)", "string"),
        CompletionItem("string.gmatch", "for w in string.gmatch(s, pat) do end", "string"),
        CompletionItem("string.sub", "string.sub(s, i, j)", "string"),
        CompletionItem("math.floor", "math.floor(x)", "math"),
        CompletionItem("math.ceil", "math.ceil(x)", "math"),
        CompletionItem("math.abs", "math.abs(x)", "math"),
        CompletionItem("math.sqrt", "math.sqrt(x)", "math"),
        CompletionItem("math.max", "math.max(a, b)", "math"),
        CompletionItem("math.min", "math.min(a, b)", "math"),
        CompletionItem("math.random", "math.random(m, n)", "math"),
        CompletionItem("table.sort", "table.sort(t, function(a, b) return a < b end)", "table"),
        CompletionItem("function", "function name()\n    \nend", "关键字"),
        CompletionItem("local function", "local function name()\n    \nend", "关键字"),
        CompletionItem("for i", "for i = 1, n do\n    \nend", "关键字"),
        CompletionItem("if", "if cond then\n    \nend", "关键字"),
        CompletionItem("while", "while cond do\n    \nend", "关键字"),
    )

    private val FUNC_RE = Regex(
        """(?:local\s+)?function\s+([A-Za-z_][\w.]*)\s*\(""",
    )
    private val LOCAL_RE = Regex(
        """\blocal\s+([A-Za-z_]\w*)\b""",
    )
    private val ASSIGN_RE = Regex(
        """^\s*([A-Za-z_]\w*)\s*=""",
        RegexOption.MULTILINE,
    )
    private val WORD_RE = Regex("""\b[A-Za-z_]\w*\b""")

    fun symbols(src: String): List<LuaSymbol> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<LuaSymbol>()
        val seen = HashSet<String>()
        for (m in FUNC_RE.findAll(src)) {
            val name = m.groupValues[1]
            val key = "f:$name"
            if (key in seen) continue
            seen += key
            out += LuaSymbol(name, LuaSymbol.Kind.Function, lineOf(src, m.range.first), m.range.first)
        }
        for (m in LOCAL_RE.findAll(src)) {
            val name = m.groupValues[1]
            if (name == "function") continue
            val key = "l:$name"
            if (key in seen) continue
            seen += key
            out += LuaSymbol(name, LuaSymbol.Kind.Local, lineOf(src, m.range.first), m.range.first)
        }
        for (m in ASSIGN_RE.findAll(src)) {
            val name = m.groupValues[1]
            if (name in KEYWORDS) continue
            val key = "g:$name"
            if (key in seen) continue
            seen += key
            out += LuaSymbol(name, LuaSymbol.Kind.Global, lineOf(src, m.range.first), m.range.first)
        }
        return out.sortedWith(compareBy({ it.line }, { it.name }))
    }

    fun wordAt(src: String, offset: Int): Pair<Int, String>? {
        if (src.isEmpty()) return null
        val i = offset.coerceIn(0, src.length)
        var start = i
        while (start > 0) {
            val c = src[start - 1]
            if (c.isLetterOrDigit() || c == '_' || c == '.') start-- else break
        }
        var end = i
        while (end < src.length) {
            val c = src[end]
            if (c.isLetterOrDigit() || c == '_' || c == '.') end++ else break
        }
        if (start >= end) return null
        val w = src.substring(start, end)
        if (w.isEmpty() || w.all { it == '.' }) return null
        return start to w
    }

    fun prefixAt(src: String, caret: Int): Pair<Int, String>? {
        if (src.isEmpty()) return null
        val i = caret.coerceIn(0, src.length)
        var start = i
        while (start > 0) {
            val c = src[start - 1]
            if (c.isLetterOrDigit() || c == '_' || c == '.') start-- else break
        }
        if (start >= i) return null
        val prefix = src.substring(start, i)
        if (prefix.isEmpty()) return null
        return start to prefix
    }

    fun completions(src: String, caret: Int, limit: Int = 12): List<CompletionItem> {
        val pref = prefixAt(src, caret) ?: return emptyList()
        val prefix = pref.second
        if (prefix.length < 1) return emptyList()
        val lower = prefix.lowercase()
        val words = LinkedHashSet<String>()
        for (m in WORD_RE.findAll(src)) {
            val w = m.value
            if (w.length >= 2 && w.lowercase().startsWith(lower) && w != prefix) words += w
        }
        val items = ArrayList<CompletionItem>()
        val seen = HashSet<String>()
        fun add(item: CompletionItem) {
            if (item.label in seen) return
            if (!item.label.lowercase().startsWith(lower) && !item.insert.lowercase().startsWith(lower)) return
            seen += item.label
            items += item
        }
        for (kw in KEYWORDS) {
            if (kw.startsWith(lower)) add(CompletionItem(kw, kw, "关键字"))
        }
        for (s in SNIPPETS) add(s)
        for (w in words) add(CompletionItem(w, w, "本文"))
        for (sym in symbols(src)) {
            if (sym.name.lowercase().startsWith(lower)) {
                add(
                    CompletionItem(
                        sym.name,
                        sym.name,
                        when (sym.kind) {
                            LuaSymbol.Kind.Function -> "函数 · L${sym.line}"
                            LuaSymbol.Kind.Local -> "局部 · L${sym.line}"
                            LuaSymbol.Kind.Global -> "变量 · L${sym.line}"
                        },
                    ),
                )
            }
        }
        return items.take(limit)
    }

    fun definitionOf(src: String, name: String): LuaSymbol? {
        val bare = name.substringAfterLast('.')
        val list = symbols(src)
        return list.firstOrNull { it.name == name && it.kind == LuaSymbol.Kind.Function }
            ?: list.firstOrNull { it.name == bare && it.kind == LuaSymbol.Kind.Function }
            ?: list.firstOrNull { it.name == name && it.kind == LuaSymbol.Kind.Local }
            ?: list.firstOrNull { it.name == bare && it.kind == LuaSymbol.Kind.Local }
            ?: list.firstOrNull { it.name == name || it.name == bare }
    }

    private fun lineOf(src: String, offset: Int): Int {
        var line = 1
        val end = offset.coerceIn(0, src.length)
        for (i in 0 until end) if (src[i] == '\n') line++
        return line
    }
}
