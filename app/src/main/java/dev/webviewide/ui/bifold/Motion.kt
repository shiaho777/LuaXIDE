package dev.webviewide.ui.bifold

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

internal object Motion {
    val paneRatio = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 380f,
    )
}
