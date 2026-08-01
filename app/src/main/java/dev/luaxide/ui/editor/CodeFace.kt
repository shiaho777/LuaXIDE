package dev.luaxide.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.engine.BreakpointSpec
import dev.luaxide.ui.S
import dev.luaxide.ui.theme.CodeTextStyle

private val TOKEN_REGEX = Regex(
    "--\\[\\[.*?\\]\\]|--.*" +
        "|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'" +
        "|\\[\\[.*?\\]\\]" +
        "|\\b\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?\\b" +
        "|\\b[A-Za-z_]\\w*\\b"
)

const val SAMPLE_LUA = """-- LuaXIDE · Bifold prototype
local ui = require("ui")

local count = 0

return ui.app {
    title = "My app",
    ui.column {
        ui.text { text = "Hello, LuaX!", size = 20 },
        ui.button {
            text = "count",
            onClick = function()
                count = count + 1
            end,
        },
    },
}
"""

@Composable
fun CodeFace(
    text: String,
    onTextChange: (String) -> Unit,
    jumpRequest: JumpRequest? = null,
    breakpoints: Map<Int, BreakpointSpec> = emptyMap(),
    pausedLine: Int? = null,
    errorLine: Int? = null,
    onToggleBreakpoint: (Int) -> Unit = {},
    onEditBreakpoint: (Int) -> Unit = {},
    onToast: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val editor = remember { EditorState(text, onTextChange) }
    val find = editor.find
    var paletteOpen by remember { mutableStateOf(false) }
    var symbolsOpen by remember { mutableStateOf(false) }
    var completions by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
    var completionPrefixStart by remember { mutableStateOf(0) }

    LaunchedEffect(text) { editor.syncExternal(text) }

    val density = LocalDensity.current

    LaunchedEffect(jumpRequest) {
        val req = jumpRequest ?: return@LaunchedEffect
        editor.jumpToLine(req.line)
        val lineHeightPx = with(density) { CodeTextStyle.lineHeight.toPx() }
        val target = (lineHeightPx * (req.line - 1) - scroll.viewportSize / 2f)
            .toInt()
            .coerceIn(0, (scroll.maxValue).coerceAtLeast(0))
        scroll.animateScrollTo(target)
    }

    LaunchedEffect(pausedLine) {
        val line = pausedLine ?: return@LaunchedEffect
        if (line <= 0) return@LaunchedEffect
        val lineHeightPx = with(density) { CodeTextStyle.lineHeight.toPx() }
        val target = (lineHeightPx * (line - 1) - scroll.viewportSize / 2f)
            .toInt()
            .coerceIn(0, (scroll.maxValue).coerceAtLeast(0))
        scroll.animateScrollTo(target)
    }

    LaunchedEffect(errorLine) {
        val line = errorLine ?: return@LaunchedEffect
        if (line <= 0) return@LaunchedEffect
        editor.jumpToLine(line)
        val lineHeightPx = with(density) { CodeTextStyle.lineHeight.toPx() }
        val target = (lineHeightPx * (line - 1) - scroll.viewportSize / 2f)
            .toInt()
            .coerceIn(0, (scroll.maxValue).coerceAtLeast(0))
        scroll.animateScrollTo(target)
    }

    fun refreshCompletions() {
        val caret = maxOf(editor.value.selection.start, editor.value.selection.end)
        val pref = LuaIntel.prefixAt(editor.text, caret)
        if (pref == null || pref.second.length < 1) {
            completions = emptyList()
            return
        }
        completionPrefixStart = pref.first
        completions = LuaIntel.completions(editor.text, caret)
    }

    fun applyItem(item: CompletionItem) {
        editor.applyCompletion(completionPrefixStart, item.insert)
        completions = emptyList()
    }

    fun goToDefinition() {
        val caret = maxOf(editor.value.selection.start, editor.value.selection.end)
        val word = LuaIntel.wordAt(editor.text, caret)
        if (word == null) {
            onToast(S.WORD_NONE)
            return
        }
        val def = LuaIntel.definitionOf(editor.text, word.second)
        if (def == null) {
            onToast(S.DEF_NOT_FOUND)
            return
        }
        editor.jumpToLine(def.line)
        editor.jumpToOffset(def.start)
    }

    val transformation = remember(
        cs, find.query, find.useRegex, find.matches, find.current, find.visible,
        pausedLine, errorLine, editor.text,
    ) {
        LuaHighlightTransformation(
            cs = cs,
            matches = if (find.visible) find.matches else emptyList(),
            current = find.current,
            pausedLine = pausedLine,
            errorLine = errorLine,
        )
    }

    if (symbolsOpen) {
        SymbolSearchDialog(
            symbols = LuaIntel.symbols(editor.text),
            onPick = { sym ->
                symbolsOpen = false
                editor.jumpToLine(sym.line)
                editor.jumpToOffset(sym.start)
            },
            onDismiss = { symbolsOpen = false },
        )
    }

    Column(modifier.fillMaxSize()) {
        EditorToolbar(
            canUndo = editor.canUndo,
            canRedo = editor.canRedo,
            onUndo = editor::undo,
            onRedo = editor::redo,
            onToggleFind = find::toggle,
            onTogglePalette = { paletteOpen = !paletteOpen },
            onOpenSymbols = { symbolsOpen = true },
            onGoDef = { goToDefinition() },
        )

        AnimatedVisibility(visible = find.visible) {
            FindReplaceBar(editor)
        }

        AnimatedVisibility(visible = paletteOpen) {
            ComponentPalette(
                onInsert = { entry ->
                    editor.insertText(entry.snippet)
                    paletteOpen = false
                },
            )
        }

        AnimatedVisibility(visible = completions.isNotEmpty()) {
            CompletionBar(
                items = completions,
                onPick = { applyItem(it) },
                onDismiss = { completions = emptyList() },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            LineNumbers(
                lineCount = editor.text.count { it == '\n' } + 1,
                color = cs.outline,
                breakpoints = breakpoints,
                pausedLine = pausedLine,
                errorLine = errorLine,
                onToggleBreakpoint = onToggleBreakpoint,
                onEditBreakpoint = onEditBreakpoint,
                modifier = Modifier.width(48.dp),
            )
            BasicTextField(
                value = editor.value,
                onValueChange = { v ->
                    editor.onValueChange(v)
                    refreshCompletions()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                textStyle = CodeTextStyle.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                visualTransformation = transformation,
            )
        }
    }
}

@Composable
private fun CompletionBar(
    items: List<CompletionItem>,
    onPick: (CompletionItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceVariant) {
        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    S.COMPLETE,
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.padding(0.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = S.CANCEL, tint = cs.onSurfaceVariant)
                }
            }
            LazyColumn {
                items(items, key = { it.label + it.detail }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(item) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.label,
                            color = cs.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.detail,
                            color = cs.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SymbolSearchDialog(
    symbols: List<LuaSymbol>,
    onPick: (LuaSymbol) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(symbols, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) symbols else symbols.filter { it.name.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.SYMBOLS) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniField(
                    value = query,
                    onChange = { query = it },
                    placeholder = S.SYMBOL_SEARCH,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(S.NO_SYMBOLS, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(filtered, key = { "${it.kind}:${it.name}:${it.line}" }) { sym ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(sym) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (sym.kind) {
                                        LuaSymbol.Kind.Function -> "ƒ"
                                        LuaSymbol.Kind.Local -> "ℓ"
                                        LuaSymbol.Kind.Global -> "•"
                                    },
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(20.dp),
                                )
                                Text(
                                    sym.name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "L${sym.line}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(S.CANCEL) }
        },
    )
}

@Composable
private fun EditorToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleFind: () -> Unit,
    onTogglePalette: () -> Unit,
    onOpenSymbols: () -> Unit,
    onGoDef: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = S.UNDO,
                tint = if (canUndo) cs.onSurface else cs.onSurface.copy(alpha = 0.3f),
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                Icons.AutoMirrored.Filled.Redo,
                contentDescription = S.REDO,
                tint = if (canRedo) cs.onSurface else cs.onSurface.copy(alpha = 0.3f),
            )
        }
        Box(Modifier.weight(1f))
        IconButton(onClick = onGoDef) {
            Icon(Icons.Filled.SubdirectoryArrowRight, contentDescription = S.GO_DEF, tint = cs.onSurfaceVariant)
        }
        IconButton(onClick = onOpenSymbols) {
            Icon(Icons.Filled.Code, contentDescription = S.SYMBOLS, tint = cs.onSurfaceVariant)
        }
        IconButton(onClick = onTogglePalette) {
            Icon(Icons.Filled.Add, contentDescription = S.INSERT_COMPONENT, tint = cs.onSurfaceVariant)
        }
        IconButton(onClick = onToggleFind) {
            Icon(Icons.Filled.Search, contentDescription = S.FIND, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun FindReplaceBar(editor: EditorState) {
    val cs = MaterialTheme.colorScheme
    val find = editor.find

    Surface(color = cs.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniField(
                    value = find.query,
                    onChange = find::updateQuery,
                    placeholder = S.FIND,
                    modifier = Modifier.weight(1f),
                )
                val countLabel = if (find.matches.isEmpty()) "0/0" else "${find.current + 1}/${find.matches.size}"
                Text(countLabel, color = cs.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                IconButton(onClick = find::prev) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "prev", tint = cs.onSurfaceVariant)
                }
                IconButton(onClick = find::next) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "next", tint = cs.onSurfaceVariant)
                }
                RegexToggle(on = find.useRegex, onToggle = { find.updateRegex(!find.useRegex) })
                IconButton(onClick = find::close) {
                    Icon(Icons.Filled.Close, contentDescription = S.CANCEL, tint = cs.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniField(
                    value = find.replacement,
                    onChange = { find.replacement = it },
                    placeholder = S.REPLACE,
                    modifier = Modifier.weight(1f),
                )
                TextChip(S.REPLACE, enabled = find.current >= 0, onClick = find::replaceCurrent)
                TextChip(S.REPLACE_ALL, enabled = find.matches.isNotEmpty(), onClick = find::replaceAll)
            }
        }
    }
}

@Composable
private fun MiniField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), modifier = modifier) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RegexToggle(on: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (on) cs.primary.copy(alpha = 0.18f) else Color.Transparent,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Text(
            text = ".*",
            color = if (on) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.6f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickableNoRipple(onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

@Composable
private fun TextChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (enabled) cs.primary.copy(alpha = 0.12f) else Color.Transparent,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            color = if (enabled) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f),
            fontSize = 13.sp,
            modifier = Modifier
                .clickableNoRipple { if (enabled) onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LineNumbers(
    lineCount: Int,
    color: Color,
    breakpoints: Map<Int, BreakpointSpec>,
    pausedLine: Int?,
    errorLine: Int?,
    onToggleBreakpoint: (Int) -> Unit,
    onEditBreakpoint: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.padding(top = 8.dp, bottom = 8.dp)) {
        for (i in 1..lineCount) {
            val bp = breakpoints[i]
            val isBp = bp != null
            val hasCond = !bp?.condition.isNullOrBlank()
            val isLog = bp?.logOnly == true || !bp?.logMessage.isNullOrBlank()
            val isPaused = pausedLine == i
            val isError = errorLine == i
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when {
                            isPaused -> cs.tertiary.copy(alpha = 0.22f)
                            isError -> cs.error.copy(alpha = 0.14f)
                            else -> Color.Transparent
                        },
                    )
                    .clickableNoRipple { onToggleBreakpoint(i) }
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .width(12.dp)
                        .clickableNoRipple {
                            if (isBp) onEditBreakpoint(i) else onToggleBreakpoint(i)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isBp) {
                        Text(
                            text = when {
                                isLog && bp?.logOnly == true -> "◇"
                                hasCond || isLog -> "◆"
                                else -> "●"
                            },
                            color = when {
                                bp?.logOnly == true -> cs.secondary
                                hasCond || isLog -> cs.tertiary
                                else -> cs.error
                            },
                            fontSize = 10.sp,
                            lineHeight = 20.sp,
                        )
                    } else if (isPaused) {
                        Text(
                            text = "▶",
                            color = cs.tertiary,
                            fontSize = 9.sp,
                            lineHeight = 20.sp,
                        )
                    } else if (isError) {
                        Text(
                            text = "!",
                            color = cs.error,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 20.sp,
                        )
                    }
                }
                Text(
                    text = i.toString(),
                    color = when {
                        isPaused -> cs.tertiary
                        isError -> cs.error
                        isBp -> cs.error
                        else -> color
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

private class LuaHighlightTransformation(
    private val cs: ColorScheme,
    private val matches: List<Match>,
    private val current: Int,
    private val pausedLine: Int?,
    private val errorLine: Int?,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            highlightLua(text.text, cs, matches, current, pausedLine, errorLine),
            OffsetMapping.Identity,
        )
    }
}

private fun highlightLua(
    src: String,
    cs: ColorScheme,
    matches: List<Match>,
    current: Int,
    pausedLine: Int?,
    errorLine: Int?,
): AnnotatedString =
    buildAnnotatedString {
        append(src)
        fun markLine(line: Int?, bg: Color, underline: Boolean) {
            if (line == null || line <= 0) return
            var ln = 1
            var start = 0
            while (start <= src.length) {
                val nl = src.indexOf('\n', start)
                val end = if (nl < 0) src.length else nl
                if (ln == line) {
                    val to = if (end > start) end else (start + 1).coerceAtMost(src.length)
                    if (to > start) {
                        addStyle(SpanStyle(background = bg), start, to)
                        if (underline) {
                            addStyle(
                                SpanStyle(
                                    color = cs.error,
                                    textDecoration = TextDecoration.Underline,
                                ),
                                start,
                                to,
                            )
                        }
                    }
                    break
                }
                if (nl < 0) break
                start = nl + 1
                ln++
            }
        }
        markLine(pausedLine, cs.tertiary.copy(alpha = 0.18f), false)
        markLine(errorLine, cs.error.copy(alpha = 0.12f), true)
        for (m in TOKEN_REGEX.findAll(src)) {
            val tok = m.value
            val style = when {
                tok.startsWith("--") -> SpanStyle(color = cs.outline)
                tok.startsWith("\"") || tok.startsWith("'") || tok.startsWith("[[") ->
                    SpanStyle(color = cs.secondary)
                tok.first().isDigit() -> SpanStyle(color = cs.tertiary)
                tok in LuaIntel.KEYWORDS -> SpanStyle(color = cs.primary, fontWeight = FontWeight.Bold)
                else -> null
            }
            if (style != null) addStyle(style, m.range.first, m.range.last + 1)
        }
        matches.forEachIndexed { i, mt ->
            if (mt.start in 0..src.length && mt.end in 0..src.length && mt.start < mt.end) {
                val bg = if (i == current) cs.primary.copy(alpha = 0.40f) else cs.primary.copy(alpha = 0.18f)
                addStyle(SpanStyle(background = bg), mt.start, mt.end)
            }
        }
    }
