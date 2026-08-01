package dev.luaxide.ui.runtime

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * An insertable component: a well-formed Lua snippet plus display metadata.
 * The snippet is real `ui.<type>{...}` source — inserting it and running the
 * normal pipeline keeps "execution is truth", the palette never fabricates a
 * tree directly.
 */
data class CatalogEntry(
    val type: String,
    val label: String,
    val icon: ImageVector,
    val snippet: String,
)

/**
 * The low-code palette catalog. Entries are keyed by the same [type] names as
 * [ComponentRegistry], so the palette and the renderer stay in lockstep — the
 * registry is the single source of truth for what exists, this adds only the
 * authoring snippet + icon for each.
 *
 * "app" is intentionally excluded: it's the root wrapper, not something you
 * drop into an existing tree.
 */
object ComponentCatalog {

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            type = "text",
            label = "text",
            icon = Icons.Filled.TextFields,
            snippet = """ui.text { text = "text", size = 16 }""",
        ),
        CatalogEntry(
            type = "button",
            label = "button",
            icon = Icons.Filled.SmartButton,
            snippet = """ui.button {
    text = "button",
    onClick = function()
        print("clicked")
    end,
}""",
        ),
        CatalogEntry(
            type = "column",
            label = "column",
            icon = Icons.Filled.ViewColumn,
            snippet = """ui.column {
    ui.text { text = "item 1" },
    ui.text { text = "item 2" },
}""",
        ),
        CatalogEntry(
            type = "row",
            label = "row",
            icon = Icons.AutoMirrored.Filled.ViewList,
            snippet = """ui.row {
    ui.text { text = "left" },
    ui.text { text = "right" },
}""",
        ),
        CatalogEntry(
            type = "card",
            label = "card",
            icon = Icons.Filled.CropSquare,
            snippet = """ui.card {
    ui.text { text = "card content" },
}""",
        ),
        CatalogEntry(
            type = "input",
            label = "input",
            icon = Icons.Filled.Edit,
            snippet = """ui.input { label = "label", value = "" }""",
        ),
        CatalogEntry(
            type = "image",
            label = "image",
            icon = Icons.Filled.Image,
            snippet = """ui.image { src = "assets/images/logo.png", size = 96 }""",
        ),
        CatalogEntry(
            type = "spacer",
            label = "spacer",
            icon = Icons.Filled.SpaceBar,
            snippet = """ui.spacer { size = 12 }""",
        ),
        CatalogEntry(
            type = "divider",
            label = "divider",
            icon = Icons.Filled.HorizontalRule,
            snippet = """ui.divider {}""",
        ),
        CatalogEntry(
            type = "scrollview",
            label = "scroll",
            icon = Icons.AutoMirrored.Filled.Notes,
            snippet = """ui.scrollview {
    ui.text { text = "scrollable" },
}""",
        ),
        CatalogEntry(
            type = "list",
            label = "list",
            icon = Icons.AutoMirrored.Filled.ViewList,
            snippet = """ui.list {
    ui.listitem { title = "Item A", subtitle = "tap me" },
    ui.listitem { title = "Item B" },
}""",
        ),
        CatalogEntry(
            type = "listitem",
            label = "list item",
            icon = Icons.Filled.CropSquare,
            snippet = """ui.listitem {
    title = "Title",
    subtitle = "Subtitle",
    onClick = function() print("item") end,
}""",
        ),
        CatalogEntry(
            type = "stack",
            label = "stack",
            icon = Icons.Filled.ViewColumn,
            snippet = """ui.stack {
    selected = "home",
    ui.page {
        key = "home",
        ui.text { text = "Home page" },
    },
    ui.page {
        key = "detail",
        ui.text { text = "Detail page" },
    },
}""",
        ),
        CatalogEntry(
            type = "page",
            label = "page",
            icon = Icons.Filled.CropSquare,
            snippet = """ui.page {
    key = "home",
    ui.text { text = "page body" },
}""",
        ),
        CatalogEntry(
            type = "switch",
            label = "switch",
            icon = Icons.Filled.SmartButton,
            snippet = """ui.switch {
    label = "enabled",
    checked = true,
    onChange = function() print("toggled") end,
}""",
        ),
    )

    /**
     * Warn (don't crash) if the registry knows a renderable type the palette
     * can't author — a drift guard. "app" is expected to be palette-absent.
     */
    fun validateAgainstRegistry() {
        val cataloged = entries.map { it.type }.toSet()
        val missing = ComponentRegistry.knownTypes - cataloged - "app"
        if (missing.isNotEmpty()) {
            Log.w("ComponentCatalog", "no palette entry for renderable types: $missing")
        }
    }
}
