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

@Composable
fun RenderTree(
    node: UiNode,
    onEvent: OnEvent,
    path: String = "root",
) {
    val component = ComponentRegistry[node.type]
    if (component != null) {
        component(node, onEvent) { children, childModifier ->
            DiffedChildren(
                children = children,
                parentPath = path,
                onEvent = onEvent,
                childModifier = childModifier,
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
    childModifier: (UiNode) -> Modifier,
) {
    val tracks: SnapshotStateList<TrackedChild> = remember { mutableStateListOf() }
    val seeded = remember { booleanArrayOf(false) }
    val conflict = duplicateChildKey(children)
    if (conflict != null) {
        Text("duplicate child key '$conflict' at $parentPath", color = MaterialTheme.colorScheme.error)
        return
    }
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
            Box(modifier = childModifier(track.node).animatePlacement()) {
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
