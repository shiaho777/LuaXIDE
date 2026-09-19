package dev.luaxide.ui.runtime

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.luaxide.engine.UiNode

internal class TrackedChild(
    val id: String,
    path: String,
    node: UiNode,
    val appear: MutableTransitionState<Boolean>,
) {
    var path by mutableStateOf(path)
    var node by mutableStateOf(node)
}

internal data class IncomingChild(
    val id: String,
    val path: String,
    val node: UiNode,
)

internal fun mapIncoming(children: List<UiNode>, parentPath: String): List<IncomingChild> {
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

internal fun applyIncoming(
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
    if (explicit.isNotEmpty()) return "k:$explicit:${node.type}"
    return "s:$structuralPath"
}

/** The first duplicated explicit key/id among siblings, or null. Unkeyed
 * children never conflict — their identity is structural position. */
internal fun duplicateChildKey(children: List<UiNode>): String? {
    val seen = HashSet<String>()
    for (child in children) {
        val explicit = child.string("key").ifEmpty { child.string("id") }.trim()
        if (explicit.isEmpty()) continue
        if (!seen.add(explicit)) return explicit
    }
    return null
}

