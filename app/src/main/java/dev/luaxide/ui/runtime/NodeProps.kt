package dev.luaxide.ui.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.luaxide.engine.UiNode

internal fun parseHexColor(hex: String): Color? {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6 && h.length != 8 || h.any { it.digitToIntOrNull(16) == null }) return null
    val v = h.toLongOrNull(16) ?: return null
    return Color(if (h.length == 6) 0xFF000000L or v else v)
}

internal fun UiNode.dpProp(key: String): Dp? = number(key, Double.NaN)
    .takeIf { it.isFinite() && it >= 0 && it.toFloat().isFinite() }?.dp

internal fun UiNode.weightProp(): Float? = number("weight", Double.NaN).toFloat()
    .takeIf { it.isFinite() && it > 0f }

/** Generic padding; Card keeps its animated 16dp interior default (no doubling). */
internal fun nodeStyle(node: UiNode): Modifier {
    var m: Modifier = Modifier
    node.dpProp("width")?.let { m = m.width(it) }
    node.dpProp("height")?.let { m = m.height(it) }
    node.dpProp("radius")?.let { m = m.clip(RoundedCornerShape(it)) }
    if (node.type !in setOf("card", "button", "input", "listitem", "image")) {
        parseHexColor(node.string("background"))?.let { m = m.background(it) }
    }
    if (node.type != "card") m = m.padding(node.dpProp("padding") ?: 0.dp)
    return m
}

/** Column child: horizontal align (start/left/center/end) + positive finite weight.
 * Weight is silently ignored by Compose when the parent's main axis is unbounded
 * (e.g. inside a scrollview's content); bound the container's height to use it.
 */
internal fun ColumnScope.childModifier(node: UiNode): Modifier {
    var m: Modifier = Modifier
    when (node.string("align").lowercase()) {
        "start", "left" -> m = m.align(Alignment.Start)
        "center" -> m = m.align(Alignment.CenterHorizontally)
        "end" -> m = m.align(Alignment.End)
    }
    node.weightProp()?.let { m = m.weight(it) }
    return m
}

/** Row child: vertical align (top/center/bottom) + positive finite weight. */
internal fun RowScope.childModifier(node: UiNode): Modifier {
    var m: Modifier = Modifier
    when (node.string("align").lowercase()) {
        "top" -> m = m.align(Alignment.Top)
        "center" -> m = m.align(Alignment.CenterVertically)
        "bottom" -> m = m.align(Alignment.Bottom)
    }
    node.weightProp()?.let { m = m.weight(it) }
    return m
}

/** Box child: nine named positions. */
internal fun BoxScope.childModifier(node: UiNode): Modifier = Modifier.align(
    when (node.string("align").lowercase()) {
        "top" -> Alignment.TopCenter
        "topright" -> Alignment.TopEnd
        "left" -> Alignment.CenterStart
        "center" -> Alignment.Center
        "right" -> Alignment.CenterEnd
        "bottomleft" -> Alignment.BottomStart
        "bottom" -> Alignment.BottomCenter
        "bottomright" -> Alignment.BottomEnd
        else -> Alignment.TopStart
    },
)

/** Selected pages alone participate in stack layout and viewport ownership. */
internal fun UiNode.visibleChildren(): List<UiNode> {
    if (type != "stack") return children
    val pages = children.filter { it.type == "page" }
    if (pages.isEmpty()) return children
    return listOf(pages.firstOrNull { it.string("key") == string("selected") } ?: pages.first())
}

internal fun UiNode.needsFiniteViewport(): Boolean {
    if (dpProp("height") != null) return false
    if (type == "scrollview") return true
    val vertical = type in setOf("app", "column", "card", "list", "listitem", "stack", "page")
    return visibleChildren().any { child ->
        (vertical && child.weightProp() != null) || child.needsFiniteViewport()
    }
}

/**
 * Dampens stale engine echoes for locally buffered controls (input text, slider
 * position). The engine worker serializes invokes, but a slow script echo can
 * still arrive after the user has kept typing/dragging; without damping the
 * field visibly reverts. Rule: echoes from events we emitted ourselves
 * ([edit]) never overwrite the local buffer; external engine-driven value
 * changes (a script resetting the field) do.
 */
internal class EchoBuffer<T>(initial: T) {
    private val pending = ArrayDeque<T>().apply { addLast(initial) }

    fun edit(local: T, emitted: Boolean) {
        if (emitted) {
            if (pending.size >= 64) pending.removeFirst()
            pending.addLast(local)
        }
    }

    fun receive(incoming: T): T? {
        val index = pending.indexOf(incoming)
        if (index >= 0) {
            repeat(index + 1) { pending.removeFirst() }
            return null
        }
        pending.clear()
        return incoming
    }
}



/** Identical host policy in preview and packaged runtime, including app > column > scrollview.
 * Plain trees keep host scrolling; explicit scrollers / weighted children own the finite viewport.
 */
@Composable
fun TreeViewport(node: UiNode, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Box(
        modifier
            .fillMaxSize()
            .then(if (node.needsFiniteViewport()) Modifier else Modifier.verticalScroll(scroll))
            .padding(16.dp),
    ) { content() }
}
