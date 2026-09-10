package dev.luaxide.lang

/**
 * Language registry — the single place that knows which languages the IDE can
 * edit/run, which file extensions belong to them, and what a new project's
 * entry file is called.
 *
 * Today only [LUA] is executable (engine/lx.c). [JAVASCRIPT] and [PYTHON] are
 * declared so the UI can present them as "coming soon" and so project metadata
 * already carries a language field — when a second engine lands, nothing in
 * the project/drawer/shell layers needs to change shape.
 */
enum class Language(
    val id: String,
    val displayName: String,
    val extensions: List<String>,
    val defaultEntry: String,
    val lineComment: String,
    /** ARGB accent used for badges/dots; decorative only, never semantic. */
    val accent: Long,
    val supported: Boolean,
) {
    LUA(
        id = "lua",
        displayName = "Lua",
        extensions = listOf("lua"),
        defaultEntry = "main.lua",
        lineComment = "--",
        accent = 0xFF4E7BD9,
        supported = true,
    ),
    JAVASCRIPT(
        id = "javascript",
        displayName = "JavaScript",
        extensions = listOf("js", "mjs"),
        defaultEntry = "main.js",
        lineComment = "//",
        accent = 0xFFD9B84E,
        supported = true,
    ),
    PYTHON(
        id = "python",
        displayName = "Python",
        extensions = listOf("py"),
        defaultEntry = "main.py",
        lineComment = "#",
        accent = 0xFF4EA3D9,
        supported = false,
    ),
    ;

    companion object {
        /** Resolve a persisted language id; unknown/absent ids fall back to Lua. */
        fun byId(id: String?): Language = entries.firstOrNull { it.id == id } ?: LUA

        /** Resolve a file path to a language by extension, or null when unknown. */
        fun ofPath(path: String): Language? {
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return entries.firstOrNull { ext in it.extensions }
        }

        /** The primary extension used when creating files in this language. */
        fun primaryExtensionOf(language: Language): String = language.extensions.first()
    }
}
