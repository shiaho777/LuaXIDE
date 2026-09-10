package dev.luaxide.ui.bifold

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

private val HingeWidth = 22.dp

enum class BifoldMode { CODE, BOTH, PREVIEW }

class BifoldState(
    initialMode: BifoldMode = BifoldMode.BOTH,
    initialRatio: Float = 0.5f,
) {
    var mode by mutableStateOf(initialMode)
    var ratio by mutableFloatStateOf(initialRatio)
    var bothRatio by mutableFloatStateOf(initialRatio.coerceIn(0.18f, 0.82f))
}

@Composable
fun rememberBifoldState(): BifoldState = remember { BifoldState() }

@Composable
fun BifoldScaffold(
    state: BifoldState,
    modifier: Modifier = Modifier,
    codeFace: @Composable BoxScope.() -> Unit,
    previewFace: @Composable BoxScope.() -> Unit,
) {
    val ratioAnim = remember { Animatable(state.ratio) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hingePx = with(density) { HingeWidth.toPx() }

    LaunchedEffect(state.mode, dragging) {
        if (!dragging) {
            val target = when (state.mode) {
                BifoldMode.CODE -> 1f
                BifoldMode.PREVIEW -> 0f
                BifoldMode.BOTH -> {
                    val r = state.bothRatio.coerceIn(0.18f, 0.82f)
                    state.ratio = r
                    r
                }
            }
            ratioAnim.animateTo(
                target,
                dev.luaxide.ui.runtime.Motion.paneRatio,
            )
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val usable = (widthPx - hingePx).coerceAtLeast(1f)
        val codeFrac = ratioAnim.value.coerceIn(0f, 1f)
        val codeW = usable * codeFrac
        val previewW = usable * (1f - codeFrac)
        val codeVisible = codeW > 0.5f
        val previewVisible = previewW > 0.5f
        val codeAlpha = ((codeFrac - 0.02f) / 0.10f).coerceIn(0f, 1f)
        val previewAlpha = ((0.98f - codeFrac) / 0.10f).coerceIn(0f, 1f)
        val codeScale = 0.985f + 0.015f * codeAlpha
        val previewScale = 0.985f + 0.015f * previewAlpha

        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(with(density) { codeW.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = if (codeVisible) codeAlpha else 0f
                        scaleX = codeScale
                        scaleY = codeScale
                        translationX = (1f - codeAlpha) * -10f
                        clip = true
                    },
            ) {
                if (codeVisible) codeFace()
            }

            Hinge(
                progress = codeFrac,
                onDrag = { delta ->
                    dragging = true
                    val newRatio = (ratioAnim.value + delta / usable).coerceIn(0f, 1f)
                    state.ratio = newRatio
                    if (newRatio in 0.18f..0.82f) {
                        state.bothRatio = newRatio
                    }
                    state.mode = BifoldMode.BOTH
                    scope.launch { ratioAnim.snapTo(newRatio) }
                },
                onDragEnd = { velocityPx ->
                    dragging = false
                    // velocityPx is px/s; normalize to ratio/s by the usable pane width.
                    // A fast flick commits to the far anchor even before the ratio crosses
                    // the positional threshold — "decide by momentum, not raw position".
                    val vRatio = velocityPx / usable
                    when {
                        vRatio > 1.5f -> state.mode = BifoldMode.CODE
                        vRatio < -1.5f -> state.mode = BifoldMode.PREVIEW
                        state.ratio < 0.12f -> state.mode = BifoldMode.PREVIEW
                        state.ratio > 0.88f -> state.mode = BifoldMode.CODE
                        else -> {
                            state.bothRatio = state.ratio.coerceIn(0.18f, 0.82f)
                            state.mode = BifoldMode.BOTH
                        }
                    }
                },
                modifier = Modifier
                    .width(HingeWidth)
                    .fillMaxHeight(),
            )

            Box(
                Modifier
                    .width(with(density) { previewW.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = if (previewVisible) previewAlpha else 0f
                        scaleX = previewScale
                        scaleY = previewScale
                        translationX = (1f - previewAlpha) * 10f
                        clip = true
                    },
            ) {
                if (previewVisible) previewFace()
            }
        }
    }
}

@Composable
private fun Hinge(
    progress: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val edge = abs((progress - 0.5f) * 2f).coerceIn(0f, 1f)
    Surface(
        color = cs.surfaceVariant,
        modifier = modifier
            .graphicsLayer {
                alpha = 0.55f + 0.45f * (1f - edge * 0.65f)
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = { onDragEnd(tracker.calculateVelocity().x) },
                    onDragCancel = { onDragEnd(0f) },
                    onHorizontalDrag = { change, dragAmount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        onDrag(dragAmount)
                    },
                )
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 28.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                repeat(7) {
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(cs.outline.copy(alpha = 0.55f + 0.45f * (1f - edge))),
                    )
                }
            }
            Surface(
                color = cs.primary,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier
                    .width(5.dp)
                    .height((40 + 16 * (1f - edge)).dp),
            ) {}
        }
    }
}
