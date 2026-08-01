package dev.luaxide.ui.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.log.LogEntry
import dev.luaxide.log.LogLevel
import dev.luaxide.log.LogStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log analyzer surface. Reads everything from [LogStore] via collected
 * flows and renders a filtered, auto-scrolling stream with level chips, regex
 * search, pause, and clear.
 *
 * Design rules honored: flat MD3 (no gradients/shadows), spring motion, stable
 * list keys ([LogEntry.seq]) so placement animates cleanly, and a jump-to-latest
 * FAB that only appears when the user has scrolled away from the tail.
 */
@Composable
fun LogPanel(
    store: LogStore,
    onJumpToLine: (Int) -> Unit = {},
    onExport: (format: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val entries by store.entries.collectAsState()
    val filter by store.filter.collectAsState()
    val paused by store.paused.collectAsState()
    val dropped by store.droppedWhilePaused.collectAsState()
    val total by store.rawCount.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // "at bottom" when the last visible item is the last entry (or list is empty).
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    // Sticky auto-scroll: follow the tail only while the user is already there.
    LaunchedEffect(entries.size) {
        if (atBottom && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Surface(color = cs.surface, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                LogToolbar(
                    activeLevels = filter.levels,
                    query = filter.query,
                    paused = paused,
                    count = total,
                    onToggleLevel = store::toggleLevel,
                    onQueryChange = store::setQuery,
                    onTogglePause = { store.setPaused(!paused) },
                    onClear = store::clear,
                    onExport = onExport,
                )

                if (paused && dropped > 0) {
                    PausedBanner(dropped)
                }

                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "no logs yet",
                            color = cs.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(entries, key = { it.seq }) { entry ->
                            LogRow(
                                entry = entry,
                                onJumpToLine = onJumpToLine,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ),
                            )
                        }
                    }
                }
            }

            // jump-to-latest FAB — only when scrolled away from the tail
            AnimatedVisibility(
                visible = !atBottom && entries.isNotEmpty(),
                enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = scaleOut(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Surface(
                    color = cs.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable {
                            scope.launch { listState.animateScrollToItem(entries.lastIndex) }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = "jump to latest",
                            tint = cs.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogToolbar(
    activeLevels: Set<LogLevel>,
    query: String,
    paused: Boolean,
    count: Int,
    onToggleLevel: (LogLevel) -> Unit,
    onQueryChange: (String) -> Unit,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onExport: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LogLevel.entries.forEach { level ->
                LevelChip(
                    level = level,
                    active = level in activeLevels,
                    onClick = { onToggleLevel(level) },
                )
            }
            Box(Modifier.width(8.dp))
            Text(
                text = "$count",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onTogglePause) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "resume" else "pause",
                    tint = cs.onSurfaceVariant,
                )
            }
            var exportOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { exportOpen = true }) {
                    Icon(Icons.Filled.Share, contentDescription = "export", tint = cs.onSurfaceVariant)
                }
                DropdownMenu(expanded = exportOpen, onDismissRequest = { exportOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("export .log") },
                        onClick = { exportOpen = false; onExport("log") },
                    )
                    DropdownMenuItem(
                        text = { Text("export .json") },
                        onClick = { exportOpen = false; onExport("json") },
                    )
                }
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Delete, contentDescription = "clear", tint = cs.onSurfaceVariant)
            }
        }
        SearchField(query = query, onQueryChange = onQueryChange)
    }
}

@Composable
private fun LevelChip(level: LogLevel, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val color = levelColor(level, cs)
    Surface(
        color = if (active) color.copy(alpha = 0.18f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = level.short.toString(),
            color = if (active) color else cs.onSurfaceVariant.copy(alpha = 0.5f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var local by remember(query) { mutableStateOf(query) }
    LaunchedEffect(local) {
        if (local == query) return@LaunchedEffect
        kotlinx.coroutines.delay(180)
        onQueryChange(local)
    }
    LaunchedEffect(query) {
        if (query != local) local = query
    }
    Surface(
        color = cs.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (local.isEmpty()) {
                Text("filter (regex)", color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            BasicTextField(
                value = local,
                onValueChange = { local = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PausedBanner(dropped: Int) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "paused · $dropped dropped",
            color = cs.onTertiaryContainer,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LogRow(
    entry: LogEntry,
    onJumpToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val color = levelColor(entry.level, cs)
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = entry.stack != null
    val hasLine = entry.line != null
    // A row is clickable if it can expand a stack OR jump to a line; jump wins
    // when both exist so the primary action (locate the bug) is one tap.
    val onClick: (() -> Unit)? = when {
        hasLine -> { { onJumpToLine(entry.line!!) } }
        hasDetail -> { { expanded = !expanded } }
        else -> null
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (entry.level == LogLevel.ERROR) color.copy(alpha = 0.06f) else Color.Transparent)
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        // severity bar
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(width = 3.dp, height = 14.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Box(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(entry.time),
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Box(Modifier.width(6.dp))
                Text(
                    text = entry.source.label.take(1),
                    color = cs.onSurfaceVariant.copy(alpha = 0.55f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                if (entry.tag.isNotEmpty()) {
                    Box(Modifier.width(8.dp))
                    Text(
                        text = entry.tag,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                if (entry.line != null) {
                    Box(Modifier.width(8.dp))
                    Text(
                        text = "→ line ${entry.line}",
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    )
                }
            }
            Text(
                text = entry.message,
                color = cs.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            if (expanded && entry.stack != null) {
                Text(
                    text = entry.stack,
                    color = cs.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun levelColor(level: LogLevel, cs: androidx.compose.material3.ColorScheme): Color = when (level) {
    LogLevel.VERBOSE -> cs.onSurfaceVariant.copy(alpha = 0.5f)
    LogLevel.DEBUG -> cs.onSurfaceVariant
    LogLevel.INFO -> cs.primary
    LogLevel.WARNING -> cs.tertiary
    LogLevel.ERROR -> cs.error
}

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private fun formatTime(t: Long): String = timeFmt.format(Date(t))
