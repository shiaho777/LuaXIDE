package dev.luaxide.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import dev.luaxide.ui.S
import dev.luaxide.program.ProgramSession
import dev.luaxide.program.TermLine
import dev.luaxide.ui.runtime.Motion
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalFace(
    session: ProgramSession,
    waitingStdin: Boolean = false,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = Motion.softFloat,
        label = "term-appear",
    )
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    val history = remember { mutableListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    var historyDraftBackup by remember { mutableStateOf("") }
    val bringInput = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val busy = session.running || waitingStdin

    fun historyUp() {
        if (history.isEmpty()) return
        if (historyIndex < 0) {
            historyDraftBackup = draft
            historyIndex = history.lastIndex
        } else if (historyIndex > 0) {
            historyIndex -= 1
        }
        draft = history[historyIndex]
    }

    fun historyDown() {
        if (historyIndex < 0) return
        if (historyIndex < history.lastIndex) {
            historyIndex += 1
            draft = history[historyIndex]
        } else {
            historyIndex = -1
            draft = historyDraftBackup
        }
    }

    fun commitHistory(line: String) {
        val t = line.trimEnd()
        if (t.isBlank()) return
        if (history.lastOrNull() != t) history.add(t)
        while (history.size > 80) history.removeAt(0)
        historyIndex = -1
        historyDraftBackup = ""
    }

    val contentLines = remember(session.lines) {
        session.lines.filter { line ->
            when (line.kind) {
                TermLine.Kind.Output -> line.text.isNotEmpty()
                TermLine.Kind.Input -> line.text.isNotEmpty()
                TermLine.Kind.Error -> line.text.isNotEmpty()
                TermLine.Kind.Prompt -> line.text.isNotEmpty()
                TermLine.Kind.Meta -> {
                    val s = line.text.trim().lowercase()
                    line.text.isNotEmpty() &&
                        s != "done" &&
                        s != "stopped" &&
                        s != "已停止" &&
                        s != "无输出" &&
                        s != "no output" &&
                        !s.startsWith("[finished") &&
                        !s.startsWith("[failed") &&
                        !s.startsWith("[cancelled")
                }
            }
        }
    }
    val hasUserActivity = contentLines.isNotEmpty()

    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    LaunchedEffect(contentLines.size, session.running, waitingStdin) {
        if (contentLines.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(contentLines.lastIndex.coerceAtLeast(0))
        }
    }

    fun send() {
        val line = draft
        if (line.isBlank() && !waitingStdin) return
        commitHistory(line)
        onSubmit(line)
        draft = ""
    }

    fun quick(cmd: String) {
        onSubmit(cmd)
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
        color = cs.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TerminalHeader(
                    running = session.running,
                    waitingStdin = waitingStdin,
                    lastOk = session.lastExitOk,
                    sandboxLabel = session.sandboxLabel,
                    onClear = onClear,
                    onCancel = onCancel,
                    onRun = { quick(".run") },
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (!hasUserActivity && !busy) {
                        EmptyConsole(
                            onHelp = { quick(".help") },
                            onRun = { quick(".run") },
                            onProot = { quick(".proot") },
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 14.dp,
                                end = 14.dp,
                                top = 8.dp,
                                bottom = 18.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(
                                items = contentLines,
                                key = { _, line -> line.id },
                            ) { _, line ->
                                TermBubble(line)
                            }
                            if (busy) {
                                item(key = "busy") {
                                    BusyHint(waitingStdin = waitingStdin)
                                }
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !atBottom && hasUserActivity,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = cs.primaryContainer,
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable {
                                    scope.launch {
                                        if (contentLines.isNotEmpty()) {
                                            listState.animateScrollToItem(contentLines.lastIndex)
                                        }
                                    }
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDownward,
                                    contentDescription = "jump to latest",
                                    tint = cs.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                if (!waitingStdin && !session.running) {
                    QuickActions(
                        onHelp = { quick(".help") },
                        onRun = { quick(".run") },
                        onClear = onClear,
                        onProot = { quick(".proot") },
                        onInsert = { draft = it },
                    )
                }

                ComposerBar(
                    draft = draft,
                    onDraftChange = { draft = it },
                    waitingStdin = waitingStdin,
                    busy = busy,
                    bringInput = bringInput,
                    onSend = { send() },
                    onCancel = onCancel,
                    onFocused = {
                        scope.launch { bringInput.bringIntoView() }
                    },
                    onHistoryUp = { historyUp() },
                    onHistoryDown = { historyDown() },
                )
            }
        }
    }
}

@Composable
private fun TerminalHeader(
    running: Boolean,
    waitingStdin: Boolean,
    lastOk: Boolean?,
    sandboxLabel: String,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onRun: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val busy = running || waitingStdin
    val status = when {
        waitingStdin -> S.WAITING_INPUT
        running -> S.RUNNING
        lastOk == false -> S.FINISHED_ERROR
        lastOk == true -> S.READY
        else -> S.CONSOLE
    }
    val statusColor by animateColorAsState(
        targetValue = when {
            waitingStdin -> cs.primary
            running -> cs.tertiary
            lastOk == false -> cs.error
            else -> cs.onSurfaceVariant
        },
        label = "term-status",
    )
    val dot by animateColorAsState(
        targetValue = when {
            waitingStdin -> cs.primary
            running -> cs.tertiary
            lastOk == false -> cs.error
            lastOk == true -> cs.secondary
            else -> cs.outline
        },
        label = "term-dot",
    )

    Surface(color = cs.surface, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = S.CONSOLE,
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dot),
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (busy) {
                    Surface(
                        color = cs.errorContainer,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onCancel),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = null,
                                tint = cs.onErrorContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                S.STOP,
                                color = cs.onErrorContainer,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onRun) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = S.RUN,
                            tint = cs.primary,
                        )
                    }
                }
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.ClearAll,
                        contentDescription = S.CLEAR,
                        tint = cs.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = friendlySandbox(sandboxLabel),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun friendlySandbox(raw: String): String {
    val mode = when {
        raw.contains("proot-userland") || raw.contains("proot=on") -> "no-root · proot ready"
        raw.contains("rootfs-layout") -> "no-root · rootfs layout"
        else -> "no-root · app sandbox"
    }
    return mode
}

@Composable
private fun EmptyConsole(
    onHelp: () -> Unit,
    onRun: () -> Unit,
    onProot: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = cs.primaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = S.PROGRAM_CONSOLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onPrimaryContainer,
                )
                Text(
                    text = S.PROGRAM_CONSOLE_HINT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftChip(S.RUN_FILE, onClick = onRun)
                    SoftChip(S.HELP, onClick = onHelp)
                    SoftChip("proot", onClick = onProot)
                }
            }
        }
    }
}

@Composable
private fun SoftChip(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = cs.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun QuickActions(
    onHelp: () -> Unit,
    onRun: () -> Unit,
    onClear: () -> Unit,
    onProot: () -> Unit,
    onInsert: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionChip(S.RUN, onClick = onRun)
        ActionChip(S.HELP, onClick = onHelp)
        ActionChip(S.CLEAR, onClick = onClear)
        ActionChip("proot", onClick = onProot)
        ActionChip("print", onClick = { onInsert("print(\"hello\")") })
        ActionChip("read", onClick = { onInsert("io.write(\"> \"); print(io.read())") })
        Text(
            text = S.TAP_FILL,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BusyHint(waitingStdin: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (waitingStdin) cs.primaryContainer.copy(alpha = 0.55f) else cs.tertiaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = if (waitingStdin) {
                S.WAITING_BELOW
            } else {
                S.RUNNING
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (waitingStdin) cs.onPrimaryContainer else cs.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    waitingStdin: Boolean,
    busy: Boolean,
    bringInput: BringIntoViewRequester,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onFocused: () -> Unit,
    onHistoryUp: () -> Unit = {},
    onHistoryDown: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val canSend = draft.isNotBlank() || waitingStdin
    val container by animateColorAsState(
        targetValue = if (waitingStdin) cs.primaryContainer.copy(alpha = 0.45f) else cs.surfaceVariant.copy(alpha = 0.65f),
        label = "composer-bg",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringInput)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = container,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AnimatedVisibility(visible = waitingStdin) {
                Text(
                    text = S.ASKING_INPUT,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.padding(start = 2.dp, end = 0.dp)) {
                    IconButton(onClick = onHistoryUp, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onHistoryDown, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .onFocusEvent { if (it.isFocused) onFocused() }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionUp -> { onHistoryUp(); true }
                                Key.DirectionDown -> { onHistoryDown(); true }
                                else -> false
                            }
                        },
                    textStyle = TextStyle(
                        color = cs.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontFamily = FontFamily.SansSerif,
                    ),
                    cursorBrush = SolidColor(cs.primary),
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    text = when {
                                        waitingStdin -> S.TYPE_REPLY
                                        busy -> S.STILL_TYPE
                                        else -> S.TRY_LUA
                                    },
                                    color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            inner()
                        }
                    },
                )

                if (busy && !waitingStdin) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = S.STOP,
                            tint = cs.error,
                        )
                    }
                }

                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = cs.primary,
                        contentColor = cs.onPrimary,
                        disabledContainerColor = cs.surfaceVariant,
                        disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.45f),
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = S.SEND,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TermBubble(line: TermLine) {
    val cs = MaterialTheme.colorScheme
    when (line.kind) {
        TermLine.Kind.Input -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = cs.primaryContainer,
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(
                        text = line.text,
                        color = cs.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        TermLine.Kind.Output -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(
                    color = cs.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                    modifier = Modifier.widthIn(max = 340.dp),
                ) {
                    Text(
                        text = line.text.ifEmpty { " " },
                        color = cs.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        TermLine.Kind.Error -> {
            Surface(
                color = cs.errorContainer.copy(alpha = 0.75f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = S.ERROR,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onErrorContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = line.text,
                        color = cs.onErrorContainer,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        TermLine.Kind.Prompt -> {
            Text(
                text = line.text.removePrefix("› ").ifBlank { line.text },
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
        }
        TermLine.Kind.Meta -> {
            val pretty = when {
                line.text.startsWith("sandbox=") -> friendlySandbox(line.text)
                line.text.startsWith("help ·") || line.text.startsWith("help") -> line.text.removePrefix("help · ").removePrefix("help ")
                else -> line.text
            }
            Surface(
                color = cs.secondaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = pretty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

