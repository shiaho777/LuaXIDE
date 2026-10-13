package dev.luaxide.lang

/**
 * Language registry — which file extensions the IDE treats as runnable LuaX
 * source, and what a new project's entry file is called.
 *
 * LuaXIDE runs Lua only. Unknown persisted language ids fall back to [LUA].
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
