package dev.luaxide.ui.logs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.log.LogStore
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Anchored heights for the log sheet, as a fraction of screen height. */
private object SheetAnchors {
    const val COLLAPSED = 0f      // handle only
    const val HALF = 0.42f
    const val FULL = 0.82f
}

/**
 * A bottom pull-up sheet hosting the [LogPanel]. Drag the handle to move between
 * three anchors (collapsed / half / full); a fling snaps to the nearest. The
 * body height is driven by a single [Animatable] fraction so motion stays a
 * spring and never jumps.
 *
 * The sheet is a thin overlay: when collapsed it's just a tappable header strip,
 * so it never steals space from the Bifold below.
 */
@Composable
fun LogSheet(
    store: LogStore,
    onJumpToLine: (Int) -> Unit = {},
    onExport: (format: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val fraction = remember { Animatable(SheetAnchors.COLLAPSED) }
    val scope = rememberCoroutineScope()
    val headerHeight = 40.dp

    fun snapNearest(target: Float) {
        val anchors = listOf(SheetAnchors.COLLAPSED, SheetAnchors.HALF, SheetAnchors.FULL)
        val nearest = anchors.minBy { abs(it - target) }
        scope.launch { fraction.animateTo(nearest, spring(stiffness = Spring.StiffnessMediumLow)) }
    }

    val count by store.rawCount.collectAsState()

    Surface(
        color = cs.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // draggable header
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(headerHeight)
                    .clickable {
                        // tap toggles collapsed <-> half
                        val target = if (fraction.value < 0.01f) SheetAnchors.HALF else SheetAnchors.COLLAPSED
                        scope.launch { fraction.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) }
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                val delta = -dragAmount / screenHeightPx
                                scope.launch {
                                    fraction.snapTo((fraction.value + delta).coerceIn(0f, SheetAnchors.FULL))
                                }
                            },
                            onDragEnd = { snapNearest(fraction.value) },
                        )
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "logs",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Box(Modifier.width(8.dp))
                    Text(
                        text = "$count",
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                // grip
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(cs.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }

            // body — height follows the animated fraction
            val bodyHeightDp = with(density) { (screenHeightPx * fraction.value).toDp() }
            if (fraction.value > 0.001f) {
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
                            scope.launch { fraction.animateTo(SheetAnchors.COLLAPSED, spring(stiffness = Spring.StiffnessMediumLow)) }
                        },
                        onExport = onExport,
                    )
                }
            }
        }
    }
}
