package dev.luaxide.ui.logs

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.log.LogStore
import dev.luaxide.ui.runtime.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Anchored heights for the log sheet, as a fraction of screen height. */
private object SheetAnchors {
    const val COLLAPSED = 0f
    const val HALF = 0.42f
}

/**
 * Owns the sheet fraction. Drag updates are synchronous so a finger-down
 * cannot lose a race with a coroutine. Release picks the next anchor after a
 * short pull instead of requiring the finger to cross the midpoint.
 */
private class SheetMotion(private val scope: CoroutineScope) {
    var fraction by mutableFloatStateOf(SheetAnchors.COLLAPSED)

    /**
     * Highest expansion whose drag handle stays below the status bar and the
     * top system-gesture zone. A downward pull that starts inside that zone
     * is stolen by the notification shade, then the sheet springs back.
     */
    var full: Float = 0.72f
    var screenHeightPx: Float = 1f
    var thresholdFrac: Float = 0.04f
    var origin: Float = 0f
    var tracking: Boolean = false
    private var job: Job? = null

    private val half: Float
        get() = minOf(SheetAnchors.HALF, (full - 0.12f).coerceAtLeast(0.28f))

    private fun anchors(): FloatArray = floatArrayOf(SheetAnchors.COLLAPSED, half, full)

    private fun nearest(pos: Float): Float = anchors().minBy { abs(it - pos) }

    private fun lowerThan(pos: Float): Float =
        anchors().filter { it < pos - 0.001f }.maxOrNull() ?: SheetAnchors.COLLAPSED

    private fun higherThan(pos: Float): Float =
        anchors().filter { it > pos + 0.001f }.minOrNull() ?: full

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Keep the handle under the system gesture zone as the sheet moves. */
    fun noteHeaderTop(headerTopPx: Float, limitPx: Float) {
        val h = screenHeightPx.coerceAtLeast(1f)
        val computed = (fraction + (headerTopPx - limitPx) / h).coerceIn(0.34f, 0.92f)
        if (abs(computed - full) >= 0.006f) full = computed
        if (fraction > full) fraction = full
    }

    /** @param dyPx finger delta, positive downward. @return the dy actually consumed. */
    fun dragByDy(dyPx: Float): Float {
        stop()
        val h = screenHeightPx.coerceAtLeast(1f)
        val before = fraction
        val after = (before - dyPx / h).coerceIn(SheetAnchors.COLLAPSED, full)
        fraction = after
        return -(after - before) * h
    }

    fun settle(velocityY: Float) {
        val h = screenHeightPx.coerceAtLeast(1f)
        val vFrac = (-velocityY / h).coerceIn(-12f, 12f)
        val delta = fraction - origin
        val target = when {
            vFrac <= -1.2f -> SheetAnchors.COLLAPSED
            vFrac >= 1.2f -> full
            vFrac <= -0.2f || delta <= -thresholdFrac -> {
                val floor = lowerThan(origin)
                if (fraction <= floor) nearest(fraction) else floor
            }
            vFrac >= 0.2f || delta >= thresholdFrac -> {
                val ceil = higherThan(origin)
                if (fraction >= ceil) nearest(fraction) else ceil
            }
            else -> nearest(origin)
        }
        animateTo(target.coerceIn(SheetAnchors.COLLAPSED, full), vFrac)
    }

    fun toggle() {
        origin = fraction
        val target = if (fraction < 0.01f) half else SheetAnchors.COLLAPSED
        animateTo(target, 0f)
    }

    fun collapse() {
        origin = fraction
        animateTo(SheetAnchors.COLLAPSED, 0f)
    }

    private fun animateTo(target: Float, vFrac: Float) {
        stop()
        val clamped = target.coerceIn(SheetAnchors.COLLAPSED, full)
        val start = fraction
        job = scope.launch {
            val anim = Animatable(start)
            anim.animateTo(clamped, Motion.snappyFloat, initialVelocity = vFrac) {
                fraction = value.coerceIn(SheetAnchors.COLLAPSED, full)
            }
        }
    }
}

/**
 * A bottom pull-up sheet hosting the [LogPanel]. Drag the handle between
 * collapsed / half / full. The log list scrolls on its own; once it is at the
 * top, a further downward pull collapses the sheet. A short downward pull on
 * the handle drops one anchor — it does not have to cross the midpoint.
 */
@Composable
fun LogSheet(
    store: LogStore,
    onJumpToLine: (Int) -> Unit = {},
    onExport: (format: String) -> Unit = {},
    waitingStdin: Boolean = false,
    onConsoleSubmit: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.coerceAtLeast(1f)
    val scope = rememberCoroutineScope()
    val motion = remember { SheetMotion(scope) }
    motion.screenHeightPx = screenHeightPx
    motion.thresholdFrac = with(density) { 16.dp.toPx() } / screenHeightPx
    // Status bar draws over the app, and the mandatory gesture zone is taller
    // still. A pull that starts in that band opens the notification shade.
    val handleLimitPx = maxOf(
        WindowInsets.statusBars.getTop(density),
        WindowInsets.mandatorySystemGestures.getTop(density),
    ).toFloat() + with(density) { 8.dp.toPx() }

    val nested = remember(motion) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (!motion.tracking) {
                    motion.tracking = true
                    motion.origin = motion.fraction
                }
                // Finger moving up: grow the sheet before the log list scrolls.
                if (available.y < 0f && motion.fraction < motion.full - 0.001f) {
                    return Offset(0f, motion.dragByDy(available.y))
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // Leftover downward drag means the list is already at the top.
                if (available.y > 0f && motion.fraction > 0.001f) {
                    return Offset(0f, motion.dragByDy(available.y))
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val moved = abs(motion.fraction - motion.origin) > 0.004f
                val wasTracking = motion.tracking
                motion.tracking = false
                if (wasTracking && moved) {
                    motion.settle(available.y)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val offAnchor = abs(motion.fraction) > 0.012f &&
                    abs(motion.fraction - motion.full) > 0.012f &&
                    abs(motion.fraction - minOf(SheetAnchors.HALF, motion.full)) > 0.012f
                if ((available.y > 0f && motion.fraction > 0.01f) || offAnchor) {
                    motion.origin = motion.fraction
                    motion.settle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val count by store.rawCount.collectAsState()
    val headerHeight = 48.dp

    Surface(
        color = cs.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(nested),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(headerHeight)
                    .onGloballyPositioned { coords ->
                        motion.noteHeaderTop(coords.positionInWindow().y, handleLimitPx)
                    }
                    .pointerInput(motion) {
                        val tracker = VelocityTracker()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            tracker.resetTracking()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            motion.stop()
                            motion.origin = motion.fraction
                            var dragged = false
                            val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                                dragged = true
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                motion.dragByDy(over)
                            }
                            if (slop == null) {
                                if (!dragged) motion.toggle()
                                return@awaitEachGesture
                            }
                            val completed = drag(slop.id) { change ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                motion.dragByDy(change.position.y - change.previousPosition.y)
                                change.consume()
                            }
                            val velocityY = if (completed) tracker.calculateVelocity().y else 0f
                            motion.settle(velocityY)
                        }
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dev.luaxide.ui.S.CONSOLE,
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Box(Modifier.width(8.dp))
                    Text(
                        text = "$count",
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(cs.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }

            val bodyHeightDp = with(density) { (screenHeightPx * motion.fraction).toDp() }
            if (motion.fraction > 0.001f) {
                Surface(
                    color = cs.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bodyHeightDp),
                ) {
                    LogPanel(
                        store = store,
                        onJumpToLine = { line ->
                            onJumpToLine(line)
                            motion.collapse()
                        },
                        onExport = onExport,
                        waitingStdin = waitingStdin,
                        onConsoleSubmit = onConsoleSubmit,
                    )
                }
            }
        }
    }
}
