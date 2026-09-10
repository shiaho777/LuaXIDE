package dev.luaxide.ui.runtime

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import kotlinx.coroutines.launch

object Motion {
    val softFloat = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 420f,
    )
    val snappyFloat = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = 900f,
    )
    val softDp = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 420f,
    )
    val softInt = spring<Int>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 420f,
    )
    val softOffset = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 420f,
    )
    val contentSize = spring<IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val expandSize = spring<IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 380f,
    )

    /** Segmented-control indicator settling (BifoldRail) — snappy, no bounce. */
    val indicator = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 520f,
    )
    /** Pane split ratio (BifoldScaffold). */
    val paneRatio = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 380f,
    )
    /** Press feedback: dip to 0.96 and spring back. */
    val press = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 700f,
    )

    private val enterTween = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)
    private val exitTween = tween<Float>(durationMillis = 160, easing = FastOutSlowInEasing)
    private val enterOffset = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 380f,
    )
    private val exitOffset = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 480f,
    )

    fun fadeThrough() =
        (
            fadeIn(animationSpec = enterTween) +
                scaleIn(initialScale = 0.98f, animationSpec = softFloat)
            ) togetherWith (
            fadeOut(animationSpec = exitTween) +
                scaleOut(targetScale = 0.98f, animationSpec = softFloat)
            )

    fun sharedAxisY(forward: Boolean = true) =
        (
            fadeIn(animationSpec = enterTween) +
                slideInVertically(animationSpec = enterOffset) {
                    if (forward) it / 14 else -it / 14
                }
            ) togetherWith (
            fadeOut(animationSpec = exitTween) +
                slideOutVertically(animationSpec = exitOffset) {
                    if (forward) -it / 18 else it / 18
                }
            )

    fun sharedAxisX(forward: Boolean = true) =
        (
            fadeIn(animationSpec = enterTween) +
                slideInHorizontally(animationSpec = enterOffset) {
                    if (forward) it / 16 else -it / 16
                }
            ) togetherWith (
            fadeOut(animationSpec = exitTween) +
                slideOutHorizontally(animationSpec = exitOffset) {
                    if (forward) -it / 20 else it / 20
                }
            )

    fun listEnter() =
        fadeIn(animationSpec = enterTween) +
            expandVertically(
                animationSpec = expandSize,
                expandFrom = Alignment.Top,
                clip = false,
            ) +
            scaleIn(initialScale = 0.97f, animationSpec = softFloat)

    fun listExit() =
        fadeOut(animationSpec = exitTween) +
            shrinkVertically(
                animationSpec = expandSize,
                shrinkTowards = Alignment.Top,
                clip = false,
            ) +
            scaleOut(targetScale = 0.97f, animationSpec = softFloat)

    fun textSwap() =
        (
            fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                slideInVertically(animationSpec = enterOffset) { it / 10 }
            ) togetherWith (
            fadeOut(animationSpec = tween(140, easing = FastOutSlowInEasing)) +
                slideOutVertically(animationSpec = exitOffset) { -it / 10 }
            )
}

private val IntOffsetConverter = TwoWayConverter<IntOffset, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.x.toFloat(), it.y.toFloat()) },
    convertFromVector = { IntOffset(it.v1.toInt(), it.v2.toInt()) },
)

/**
 * Uniform press feedback: scales down while pressed, springs back on release.
 * [interactionSource] must be the same instance handed to the element's
 * clickable — see [pressableClickable] for the one-call version.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.press,
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Click + uniform press-scale feedback in one modifier. */
@Composable
fun Modifier.pressableClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .pressScale(interactionSource)
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        )
}

@Composable
fun Modifier.animatePlacement(): Modifier {
    val scope = rememberCoroutineScope()
    var targetOffset by remember { mutableStateOf(IntOffset.Zero) }
    val animatable = remember { Animatable(IntOffset.Zero, IntOffsetConverter) }
    var placed by remember { mutableStateOf(false) }
    return this
        .onPlaced { coordinates ->
            val next = coordinates.positionInParent().round()
            if (!placed) {
                placed = true
                targetOffset = next
                scope.launch { animatable.snapTo(next) }
            } else if (next != targetOffset) {
                val previous = targetOffset
                targetOffset = next
                scope.launch {
                    animatable.snapTo(previous)
                    animatable.animateTo(next, Motion.softOffset)
                }
            }
        }
        .offset {
            animatable.value - targetOffset
        }
}
