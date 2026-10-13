package dev.luaxide.ui.docs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.docs.ApiDoc
import dev.luaxide.docs.ApiDocs
import dev.luaxide.ui.S

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiDocsSheet(
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ApiDocsContent(
            onInsert = onInsert,
            onDismiss = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun ApiDocsContent(
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("all") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val cats = listOf("all") + ApiDocs.categories()
    val list = remember(query, category) {
        ApiDocs.search(query).filter { category == "all" || it.category == category }
    }

    Column(modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "API 速查",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = S.CANCEL)
            }
        }
        Text(
            "ui.* / io.* / 终端命令 · 点插入可写到编辑器",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("搜索 ui.image / io.read…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cats.forEach { c ->
                val label = when (c) {
                    "all" -> "全部"
                    "ui" -> "ui.*"
                    "io" -> "io.*"
                    "term" -> "终端"
                    else -> c
                }
                FilterChip(
                    selected = category == c,
                    onClick = { category = c },
                    label = { Text(label) },
                )
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 12.dp),
        ) {
            items(list, key = { it.id }) { doc ->
                DocCard(
                    doc = doc,
                    open = expanded == doc.id,
                    onToggle = { expanded = if (expanded == doc.id) null else doc.id },
                    onInsert = { onInsert(doc.example) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun DocCard(
    doc: ApiDoc,
    open: Boolean,
    onToggle: () -> Unit,
    onInsert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    doc.name,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    doc.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Text(doc.summary, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            if (open) {
                Text(
                    doc.signature,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                )
                doc.props.forEach { (k, v) ->
                    Text("· $k — $v", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Surface(
                    color = cs.surface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        doc.example,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = cs.onSurface,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onInsert) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.padding(end = 4.dp))
                        Text("插入示例")
                    }
                }
            }
        }
    }
}
