package dev.luaxide.ui.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.engine.RunResult
import dev.luaxide.ui.S
import dev.luaxide.program.PreviewKind
import dev.luaxide.program.ProgramSession
import dev.luaxide.program.TermLine
import dev.luaxide.program.resolvePreviewKind
import dev.luaxide.ui.runtime.Motion
import dev.luaxide.ui.runtime.OnEvent
import dev.luaxide.ui.runtime.RenderTree
import dev.luaxide.ui.runtime.nodeIdentity
import dev.luaxide.ui.terminal.TerminalFace

@Composable
fun PreviewFace(
    result: RunResult?,
    onEvent: OnEvent,
    programSession: ProgramSession,
    waitingStdin: Boolean = false,
    onTerminalSubmit: (String) -> Unit,
    onTerminalClear: () -> Unit,
    onTerminalCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val kind = resolvePreviewKind(result)
    AnimatedContent(
        targetState = kind,
        transitionSpec = { Motion.fadeThrough() },
        modifier = modifier.fillMaxSize(),
        label = "preview-kind",
    ) { k ->
        when (k) {
            PreviewKind.Terminal -> TerminalFace(
                session = programSession,
                waitingStdin = waitingStdin,
                onSubmit = onTerminalSubmit,
                onClear = onTerminalClear,
                onCancel = onTerminalCancel,
            )
            PreviewKind.Error -> {
                if (result?.tree == null) {
                    TerminalFace(
                session = programSession,
                waitingStdin = waitingStdin,
                onSubmit = onTerminalSubmit,
                onClear = onTerminalClear,
                onCancel = onTerminalCancel,
            )
                } else {
                    UiPreview(result = result, onEvent = onEvent)
                }
            }
            PreviewKind.Ui -> UiPreview(result = result, onEvent = onEvent)
            PreviewKind.Idle -> {
                val hasHistory = programSession.lines.any {
                    it.kind == TermLine.Kind.Output ||
                        it.kind == TermLine.Kind.Input ||
                        it.kind == TermLine.Kind.Error
                }
                if (hasHistory) {
                    TerminalFace(
                session = programSession,
                waitingStdin = waitingStdin,
                onSubmit = onTerminalSubmit,
                onClear = onTerminalClear,
                onCancel = onTerminalCancel,
            )
                } else {
                    IdlePreview()
                }
            }
        }
    }
}

@Composable
private fun IdlePreview() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(S.PREVIEW_TITLE, color = cs.onSurface, style = MaterialTheme.typography.titleMedium)
            Text(
                S.PREVIEW_HINT,
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun UiPreview(
    result: RunResult?,
    onEvent: OnEvent,
) {
    val cs = MaterialTheme.colorScheme
    val phaseOk = result?.ok == true && result.tree != null
    val tree = result?.takeIf { it.ok }?.tree
    val rootKey = tree?.let { nodeIdentity(it, "root") } ?: "none"
    val scroll = rememberScrollState()
    val lastTree = remember { arrayOf(tree) }
    if (tree != null) lastTree[0] = tree
    val displayTree = tree ?: lastTree[0]

    val contentAlpha by animateFloatAsState(
        targetValue = if (phaseOk) 1f else 0f,
        animationSpec = Motion.softFloat,
        label = "content-alpha",
    )
    val contentScale by animateFloatAsState(
        targetValue = if (phaseOk) 1f else 0.98f,
        animationSpec = Motion.softFloat,
        label = "content-scale",
    )
    val contentY by animateFloatAsState(
        targetValue = if (phaseOk) 0f else 12f,
        animationSpec = Motion.softFloat,
        label = "content-y",
    )
    val overlayAlpha by animateFloatAsState(
        targetValue = if (phaseOk) 0f else 1f,
        animationSpec = Motion.softFloat,
        label = "overlay-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface),
    ) {
        if (displayTree != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = contentAlpha.coerceIn(0f, 1f)
                        scaleX = contentScale
                        scaleY = contentScale
                        translationY = contentY
                    }
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                key(rootKey) {
                    RenderTree(node = displayTree, onEvent = onEvent)
                }
            }
        }

        if (overlayAlpha > 0.01f && result?.ok == false) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = overlayAlpha.coerceIn(0f, 1f) }
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    color = cs.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(S.ERROR, color = cs.onErrorContainer, fontSize = 13.sp)
                        Text(
                            text = result.error ?: "未知错误",
                            color = cs.onErrorContainer,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
