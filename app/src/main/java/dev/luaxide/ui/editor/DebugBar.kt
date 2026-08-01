package dev.luaxide.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.engine.BreakpointSpec
import dev.luaxide.engine.DebugFrame
import dev.luaxide.engine.DebugLocal
import dev.luaxide.engine.DebugState
import dev.luaxide.engine.DebugWatch

@Composable
fun DebugBar(
    enabled: Boolean,
    state: DebugState,
    breakpoints: Map<Int, BreakpointSpec> = emptyMap(),
    watches: List<String> = emptyList(),
    panelOpen: Boolean = false,
    breakOnError: Boolean = true,
    instantEval: DebugWatch? = null,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleBreakOnError: (Boolean) -> Unit = {},
    onContinue: () -> Unit,
    onStep: () -> Unit,
    onStepOut: () -> Unit = {},
    onStop: () -> Unit,
    onClearBreakpoints: () -> Unit = {},
    onJumpToLine: (Int) -> Unit = {},
    onEditBreakpoint: (Int) -> Unit = {},
    onRemoveBreakpoint: (Int) -> Unit = {},
    onAddWatch: (String) -> Unit = {},
    onRemoveWatch: (String) -> Unit = {},
    onEvalNow: (String) -> Unit = {},
    onClearInstantEval: () -> Unit = {},
    onTogglePanel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val paused = state as? DebugState.Paused
    val active = enabled && state !is DebugState.Idle
    val watchValues = paused?.pause?.watches.orEmpty().associateBy { it.expr }

    Surface(
        color = if (paused != null) cs.tertiaryContainer else cs.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "debug",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                )
                Column(modifier = Modifier.weight(1f).clickable(onClick = onTogglePanel)) {
                    Text(
                        text = when {
                            !enabled -> "off"
                            paused != null && paused.pause.reason == 1 -> "exception · L${paused.pause.line}"
                            paused != null -> "paused · L${paused.pause.line}"
                            state is DebugState.Running -> "running"
                            else -> "ready"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (paused != null) cs.onTertiaryContainer else cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildList {
                        if (breakpoints.isNotEmpty()) add("${breakpoints.size} bp")
                        if (watches.isNotEmpty()) add("${watches.size} watch")
                    }.joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
                IconButton(onClick = onTogglePanel) {
                    Icon(
                        if (panelOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "panel",
                        tint = cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClearBreakpoints, enabled = breakpoints.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "clear breakpoints",
                        tint = if (breakpoints.isNotEmpty()) cs.onSurfaceVariant else cs.onSurface.copy(alpha = 0.3f),
                    )
                }
                IconButton(onClick = onContinue, enabled = paused != null) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "continue",
                        tint = if (paused != null) cs.primary else cs.onSurface.copy(alpha = 0.3f),
                    )
                }
                IconButton(onClick = onStep, enabled = paused != null) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "step",
                        tint = if (paused != null) cs.primary else cs.onSurface.copy(alpha = 0.3f),
                    )
                }
                IconButton(onClick = onStepOut, enabled = paused != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardTab,
                        contentDescription = "step out",
                        tint = if (paused != null) cs.primary else cs.onSurface.copy(alpha = 0.3f),
                    )
                }
                IconButton(onClick = onStop, enabled = active) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "stop",
                        tint = if (active) cs.error else cs.onSurface.copy(alpha = 0.3f),
                    )
                }
            }

            AnimatedVisibility(visible = panelOpen || paused != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (panelOpen) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        ) {
                            Text(
                                text = "break on error",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = breakOnError,
                                onCheckedChange = onToggleBreakOnError,
                            )
                        }
                    }
                    if (paused?.pause?.error != null) {
                        Surface(
                            color = cs.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = paused.pause.error ?: "",
                                color = cs.onErrorContainer,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                    if (panelOpen && breakpoints.isNotEmpty()) {
                        BreakpointListPanel(
                            breakpoints = breakpoints,
                            onJumpToLine = onJumpToLine,
                            onEdit = onEditBreakpoint,
                            onRemove = onRemoveBreakpoint,
                        )
                    }
                    if (panelOpen || watches.isNotEmpty() || paused != null) {
                        WatchPanel(
                            watches = watches,
                            values = watchValues,
                            onAdd = onAddWatch,
                            onRemove = onRemoveWatch,
                        )
                    }
                    if (panelOpen || paused != null) {
                        InstantEvalPanel(
                            enabled = paused != null,
                            last = instantEval,
                            onEval = onEvalNow,
                            onClear = onClearInstantEval,
                            onPinWatch = onAddWatch,
                        )
                    }
                    if (paused != null) {
                        val stack = paused.pause.stack
                        if (stack.isNotEmpty()) {
                            StackPanel(stack = stack, onJumpToLine = onJumpToLine)
                        }
                        LocalsPanel(locals = paused.pause.locals)
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakpointListPanel(
    breakpoints: Map<Int, BreakpointSpec>,
    onJumpToLine: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("breakpoints", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp),
            ) {
                items(breakpoints.toList().sortedBy { it.first }, key = { it.first }) { (line, bp) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToLine(line) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val label = buildString {
                            append(when {
                                bp.logOnly -> "◇"
                                bp.condition.isNotBlank() || bp.logMessage.isNotBlank() -> "◆"
                                else -> "●"
                            })
                            append(" L$line")
                            if (bp.condition.isNotBlank()) append(" · if ${bp.condition}")
                            if (bp.logMessage.isNotBlank()) append(" · log ${bp.logMessage}")
                        }
                        Text(
                            text = label,
                            color = when {
                                bp.logOnly -> cs.secondary
                                bp.condition.isNotBlank() || bp.logMessage.isNotBlank() -> cs.tertiary
                                else -> cs.error
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "edit",
                            color = cs.primary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { onEdit(line) }
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            text = "x",
                            color = cs.error,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { onRemove(line) }
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchPanel(
    watches: List<String>,
    values: Map<String, DebugWatch>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var draft by remember { mutableStateOf("") }
    Surface(
        color = cs.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("watches", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            if (watches.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 110.dp),
                ) {
                    items(watches, key = { it }) { expr ->
                        val w = values[expr]
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = expr,
                                color = cs.primary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(0.4f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = w?.value ?: "—",
                                color = when {
                                    w == null -> cs.onSurfaceVariant
                                    w.ok -> cs.onSurface
                                    else -> cs.error
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(0.5f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "x",
                                color = cs.error,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { onRemove(expr) },
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            ) {
                Surface(
                    color = cs.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = cs.onSurface,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) {
                                Text(
                                    "add watch expr",
                                    color = cs.onSurfaceVariant.copy(alpha = 0.55f),
                                    fontSize = 12.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
                IconButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onAdd(draft.trim())
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "add watch", tint = cs.primary)
                }
            }
        }
    }
}


@Composable
private fun InstantEvalPanel(
    enabled: Boolean,
    last: DebugWatch?,
    onEval: (String) -> Unit,
    onClear: () -> Unit,
    onPinWatch: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var draft by remember { mutableStateOf("") }
    Surface(
        color = cs.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("eval", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    color = cs.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        enabled = enabled,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) {
                                Text(
                                    if (enabled) "expression then eval" else "pause to eval",
                                    color = cs.onSurfaceVariant.copy(alpha = 0.55f),
                                    fontSize = 12.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
                Text(
                    text = "run",
                    color = if (enabled && draft.isNotBlank()) cs.primary else cs.onSurface.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(enabled = enabled && draft.isNotBlank()) {
                            onEval(draft.trim())
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            if (last != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = last.expr,
                        color = cs.primary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(0.35f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = last.value,
                        color = if (last.ok) cs.onSurface else cs.error,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(0.45f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (last.ok) {
                        Text(
                            text = "pin",
                            color = cs.tertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { onPinWatch(last.expr) },
                        )
                    }
                    Text(
                        text = "x",
                        color = cs.error,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable(onClick = onClear),
                    )
                }
            }
        }
    }
}

@Composable
private fun StackPanel(stack: List<DebugFrame>, onJumpToLine: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = "stack",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 96.dp),
            ) {
                items(stack.size) { idx ->
                    val frame = stack[idx]
                    val label = if (frame.line > 0) "${frame.name} · L${frame.line}" else frame.name
                    Text(
                        text = label,
                        color = if (idx == 0) cs.tertiary else cs.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = frame.line > 0) { onJumpToLine(frame.line) }
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalsPanel(locals: List<DebugLocal>) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        if (locals.isEmpty()) {
            Text(
                text = "no locals",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(locals, key = { it.name }) { local ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = local.name,
                            color = cs.primary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(0.35f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = local.value,
                            color = cs.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(0.65f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
