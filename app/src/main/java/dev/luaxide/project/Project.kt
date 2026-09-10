package dev.luaxide.project

import dev.luaxide.lang.Language
import java.io.File

/** File classification, used for tree icons and open behavior. */
enum class FileKind {
    CODE, IMAGE, FONT, FOLDER, OTHER;

    companion object {
        fun of(file: File): FileKind = when {
            file.isDirectory -> FOLDER
            Language.ofPath(file.name) != null -> CODE
            file.extension.lowercase() in IMAGE_EXTS -> IMAGE
            file.extension.lowercase() in FONT_EXTS -> FONT
            else -> OTHER
        }

        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "svg")
        private val FONT_EXTS = setOf("ttf", "otf", "ttc")
    }
}

/**
 * A node in a project's file tree. Immutable snapshot — the repository rebuilds
 * the tree on any structural change rather than mutating in place, so Compose
 * always renders a consistent picture.
 *
 * [relPath] is relative to the project root (POSIX-style, "/" separated), which
 * is the stable identity used for open/select/expand state and list keys.
 */
data class FileNode(
    val name: String,
    val relPath: String,
    val kind: FileKind,
    val children: List<FileNode> = emptyList(),
) {
    val isDirectory: Boolean get() = kind == FileKind.FOLDER
}

/**
 * Project metadata, persisted as project.json. [schema] is versioned so future
 * format changes can migrate rather than break.
 */
data class Project(
    val id: String,
    val name: String,
    val entryFile: String = "main.lua",
    /** [dev.luaxide.lang.Language.id]; persisted so multi-language projects survive reloads. */
    val language: String = "lua",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val schema: Int = SCHEMA_VERSION,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
