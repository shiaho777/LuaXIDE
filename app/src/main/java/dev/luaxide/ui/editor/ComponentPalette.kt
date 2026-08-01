package dev.luaxide.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.ui.runtime.CatalogEntry
import dev.luaxide.ui.runtime.ComponentCatalog

/**
 * Low-code component palette: a grid of insertable components. Tapping one
 * emits its [CatalogEntry] so the host can insert the snippet at the cursor.
 * The palette authors real Lua — nothing here bypasses the engine.
 */
@Composable
fun ComponentPalette(
    onInsert: (CatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 84.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ComponentCatalog.entries, key = { it.type }) { entry ->
                PaletteTile(entry = entry, onClick = { onInsert(entry) })
            }
        }
    }
}

@Composable
private fun PaletteTile(entry: CatalogEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                entry.icon,
                contentDescription = entry.label,
                tint = cs.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = entry.label,
                color = cs.onSurface,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
