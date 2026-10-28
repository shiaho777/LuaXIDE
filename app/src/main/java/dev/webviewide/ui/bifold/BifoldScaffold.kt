package dev.webviewide.ui.bifold

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    // Endpoints of the in-flight spring. Updated when a transition starts or
    // settles, never on each frame — the faces read these as their measure width.
    var fromFrac by remember { mutableFloatStateOf(state.ratio) }
    var toFrac by remember { mutableFloatStateOf(state.ratio) }
    var releaseVelocity by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hingePx = with(density) { HingeWidth.toPx() }

    LaunchedEffect(state.mode, dragging) {
        if (dragging) return@LaunchedEffect
        val target = when (state.mode) {
            BifoldMode.CODE -> 1f
            BifoldMode.PREVIEW -> 0f
            BifoldMode.BOTH -> {
                val r = state.bothRatio.coerceIn(0.18f, 0.82f)
                state.ratio = r
                r
            }
        }
        val start = ratioAnim.value
        // A fling toward code or preview keeps the finger's velocity. Settling
        // back into the split must not: the target is where the finger let go,
        // and a leftover velocity would shove the hinge and pull it back.
        val velocity = if (start != target && (target == 0f || target == 1f)) releaseVelocity else 0f
        releaseVelocity = 0f
        if (start != target) {
            fromFrac = start
            toFrac = target
            // Reflow the wider pane once while the slot is still at rest, then move.
            withFrameNanos { }
            ratioAnim.animateTo(target, Motion.paneRatio, initialVelocity = velocity)
        }
        ratioAnim.snapTo(target)
        fromFrac = target
        toFrac = target
        state.ratio = target
    }

    // Compose the hidden face only once the spring has settled, so a blink in
    // the editor does not keep running under the preview.
    val showCode = dragging || fromFrac > 0.001f || toFrac > 0.001f
    val showPreview = dragging || fromFrac < 0.999f || toFrac < 0.999f
    val showHinge = dragging || hingeOpen(fromFrac) || hingeOpen(toFrac)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val totalPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        Box(Modifier.fillMaxSize()) {
            if (showCode) {
                Box(
                    Modifier.animSlot(
                        xPx = { 0 },
                        slotPx = { bifoldSpanPx(ratioAnim.value, totalPx, hingePx).code },
                        measurePx = {
                            val slot = bifoldSpanPx(ratioAnim.value, totalPx, hingePx).code
                            if (dragging) slot
                            else max(bifoldMeasureSpans(fromFrac, toFrac, totalPx, hingePx).code.roundToInt(), slot)
                        },
                        // The gutter stays on the left edge; the slot clips the rest.
                        childX = { _, _ -> 0 },
                        alpha = { bifoldCodeAlpha(ratioAnim.value) },
                    ),
                ) {
                    codeFace()
                }
            }
            if (showPreview) {
                Box(
                    Modifier.animSlot(
                        xPx = {
                            val span = bifoldSpanPx(ratioAnim.value, totalPx, hingePx)
                            span.code + span.hinge
                        },
                        slotPx = { bifoldSpanPx(ratioAnim.value, totalPx, hingePx).preview },
                        measurePx = {
                            val slot = bifoldSpanPx(ratioAnim.value, totalPx, hingePx).preview
                            if (dragging) slot
                            else max(bifoldMeasureSpans(fromFrac, toFrac, totalPx, hingePx).preview.roundToInt(), slot)
                        },
                        childX = { slot, measured -> bifoldPreviewOffsetPx(slot, measured) },
                        alpha = { bifoldPreviewAlpha(ratioAnim.value) },
                    ),
                ) {
                    previewFace()
                }
            }
            if (showHinge) {
                val spanNow = {
                    bifoldSpanPx(ratioAnim.value, totalPx, hingePx)
                }
                Box(
                    Modifier
                        .offset { IntOffset(spanNow().code, 0) }
                        .layout { measurable, constraints ->
                            val span = spanNow()
                            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                            val placeable = measurable.measure(
                                Constraints.fixed(span.hinge.coerceAtLeast(0), height.coerceAtLeast(0)),
                            )
                            layout(span.hinge, height) { placeable.place(0, 0) }
                        },
                ) {
                    Hinge(
                        progress = { ratioAnim.value },
                        onDrag = { delta ->
                            dragging = true
                            val usable = (totalPx - bifoldSpans(ratioAnim.value, totalPx, hingePx).hinge)
                                .coerceAtLeast(1f)
                            val newRatio = (ratioAnim.value + delta / usable).coerceIn(0f, 1f)
                            state.ratio = newRatio
                            if (newRatio in 0.18f..0.82f) state.bothRatio = newRatio
                            state.mode = BifoldMode.BOTH
                            scope.launch { ratioAnim.snapTo(newRatio) }
                        },
                        onDragEnd = { velocityPx ->
                            val usable = (totalPx - bifoldSpans(ratioAnim.value, totalPx, hingePx).hinge)
                                .coerceAtLeast(1f)
                            releaseVelocity = velocityPx / usable
                            dragging = false
                            when {
                                releaseVelocity > 1.5f -> state.mode = BifoldMode.CODE
                                releaseVelocity < -1.5f -> state.mode = BifoldMode.PREVIEW
                                state.ratio < 0.12f -> state.mode = BifoldMode.PREVIEW
                                state.ratio > 0.88f -> state.mode = BifoldMode.CODE
                                else -> {
                                    state.bothRatio = state.ratio.coerceIn(0.18f, 0.82f)
                                    state.mode = BifoldMode.BOTH
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun hingeOpen(frac: Float): Boolean = frac > 0.001f && frac < 0.999f

private fun bifoldCodeAlpha(codeFrac: Float): Float =
    ((codeFrac.coerceIn(0f, 1f) - 0.02f) / 0.10f).coerceIn(0f, 1f)

private fun bifoldPreviewAlpha(codeFrac: Float): Float =
    ((0.98f - codeFrac.coerceIn(0f, 1f)) / 0.10f).coerceIn(0f, 1f)

/**
 * Reports the animated slot size, and measures the child at [measurePx].
 * [measurePx] stays constant for the whole spring, so a width animation does
 * not remeasure the editor or the preview tree. The fraction is read inside
 * the layout and draw lambdas, not in composition.
 */
private fun Modifier.animSlot(
    xPx: () -> Int,
    slotPx: () -> Int,
    measurePx: () -> Int,
    childX: (slot: Int, measured: Int) -> Int,
    alpha: () -> Float,
): Modifier = this
    .offset { IntOffset(xPx(), 0) }
    .clipToBounds()
    .graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) }
    .layout { measurable, constraints ->
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val slot = slotPx().coerceAtLeast(0)
        val measured = measurePx().coerceAtLeast(slot)
        val child = measurable.measure(
            Constraints.fixed(measured.coerceAtLeast(0), height.coerceAtLeast(0)),
        )
        layout(slot, height) { child.place(childX(slot, measured), 0) }
    }

@Composable
private fun Hinge(
    progress: () -> Float,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    fun edge(): Float = abs((progress() - 0.5f) * 2f).coerceIn(0f, 1f)
    Surface(
        color = cs.surfaceVariant,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.55f + 0.45f * (1f - edge() * 0.65f) }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = { end(tracker.calculateVelocity().x) },
                    onDragCancel = { end(0f) },
                    onHorizontalDrag = { change, dragAmount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        drag(dragAmount)
                    },
                )
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 28.dp)
                    .graphicsLayer { alpha = 0.55f + 0.45f * (1f - edge()) },
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                repeat(7) {
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(cs.outline),
                    )
                }
            }
            Surface(
                color = cs.primary,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.layout { measurable, constraints ->
                    val h = (40.dp + 16.dp * (1f - edge())).roundToPx()
                    val w = 5.dp.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                },
            ) {}
        }
    }
}
