package dev.luaxide.ui.runtime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.engine.UiNode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private class TrackedChild(
    val id: String,
    path: String,
    node: UiNode,
    val appear: MutableTransitionState<Boolean>,
) {
    var path by mutableStateOf(path)
    var node by mutableStateOf(node)
}

private data class IncomingChild(
    val id: String,
    val path: String,
    val node: UiNode,
)

@Composable
fun RenderTree(
    node: UiNode,
    onEvent: OnEvent,
    path: String = "root",
) {
    val component = ComponentRegistry[node.type]
    if (component != null) {
        component(node, onEvent) { children ->
            DiffedChildren(
                children = children,
                parentPath = path,
                onEvent = onEvent,
            )
        }
    } else {
        UnknownNode(node.type)
    }
}

@Composable
private fun DiffedChildren(
    children: List<UiNode>,
    parentPath: String,
    onEvent: OnEvent,
) {
    val tracks: SnapshotStateList<TrackedChild> = remember { mutableStateListOf() }
    val seeded = remember { booleanArrayOf(false) }
    val incoming = mapIncoming(children, parentPath)

    applyIncoming(
        tracks = tracks,
        incoming = incoming,
        animateEnter = seeded[0],
    )
    if (!seeded[0] && incoming.isNotEmpty()) {
        seeded[0] = true
    }

    tracks.forEach { track ->
        key(track.id) {
            Box(modifier = Modifier.animatePlacement()) {
                AnimatedVisibility(
                    visibleState = track.appear,
                    enter = Motion.listEnter(),
                    exit = Motion.listExit(),
                ) {
                    RenderTree(
                        node = track.node,
                        onEvent = onEvent,
                        path = track.path,
                    )
                }
            }
            LaunchedEffect(track.id, track.appear) {
                snapshotFlow {
                    Triple(
                        track.appear.currentState,
                        track.appear.targetState,
                        track.appear.isIdle,
                    )
                }
                    .map { (current, target, idle) -> !current && !target && idle }
                    .distinctUntilChanged()
                    .collect { gone ->
                        if (gone) {
                            tracks.removeAll {
                                it.id == track.id &&
                                    !it.appear.currentState &&
                                    !it.appear.targetState
                            }
                        }
                    }
            }
        }
    }
}

private fun mapIncoming(children: List<UiNode>, parentPath: String): List<IncomingChild> {
    val typeCounts = HashMap<String, Int>()
    return children.map { child ->
        val typeIndex = typeCounts.merge(child.type, 1) { a, _ -> a + 1 }!! - 1
        val structuralPath = "$parentPath/${child.type}[$typeIndex]"
        IncomingChild(
            id = nodeIdentity(child, structuralPath),
            path = structuralPath,
            node = child,
        )
    }
}

private fun applyIncoming(
    tracks: SnapshotStateList<TrackedChild>,
    incoming: List<IncomingChild>,
    animateEnter: Boolean,
) {
    val nextIds = LinkedHashSet<String>(incoming.size)
    for (item in incoming) nextIds.add(item.id)
    val existing = tracks.associateBy { it.id }

    for (track in tracks) {
        if (track.id !in nextIds && track.appear.targetState) {
            track.appear.targetState = false
        }
    }

    val rebuilt = ArrayList<TrackedChild>(incoming.size + 4)
    val placed = HashSet<String>()

    for (item in incoming) {
        val cur = existing[item.id]
        if (cur != null) {
            if (cur.node !== item.node) cur.node = item.node
            if (cur.path != item.path) cur.path = item.path
            if (!cur.appear.targetState) cur.appear.targetState = true
            rebuilt.add(cur)
        } else {
            val initial = if (animateEnter) false else true
            rebuilt.add(
                TrackedChild(
                    id = item.id,
                    path = item.path,
                    node = item.node,
                    appear = MutableTransitionState(initial).apply { targetState = true },
                ),
            )
        }
        placed.add(item.id)
    }

    val oldOrder = tracks.map { it.id }
    for (track in tracks) {
        if (track.id in placed) continue
        if (!track.appear.currentState && !track.appear.targetState) continue
        if (track.appear.targetState) track.appear.targetState = false
        val oldIndex = oldOrder.indexOf(track.id)
        val predId = oldOrder.take(oldIndex).lastOrNull { it in placed }
        val insertAt = if (predId == null) {
            0
        } else {
            val i = rebuilt.indexOfFirst { it.id == predId }
            if (i < 0) rebuilt.size else i + 1
        }
        rebuilt.add(insertAt.coerceIn(0, rebuilt.size), track)
    }

    val sameOrder = rebuilt.size == tracks.size &&
        rebuilt.indices.all { rebuilt[it].id == tracks[it].id }
    if (!sameOrder) {
        tracks.clear()
        tracks.addAll(rebuilt)
    }
}

fun nodeIdentity(node: UiNode, structuralPath: String): String {
    val explicit = node.string("key").ifEmpty { node.string("id") }.trim()
    if (explicit.isNotEmpty()) return "k:$explicit"
    return "s:$structuralPath"
}

@Composable
private fun UnknownNode(type: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "unknown component: $type",
            color = cs.onErrorContainer,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
