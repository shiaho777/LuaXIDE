package dev.luaxide.program

import dev.luaxide.engine.RunResult
import dev.luaxide.engine.UiNode

enum class PreviewKind {
    Idle,
    Ui,
    Terminal,
    Error,
}

fun resolvePreviewKind(result: RunResult?): PreviewKind = when {
    result == null -> PreviewKind.Idle
    !result.ok -> PreviewKind.Error
    result.tree != null -> PreviewKind.Ui
    else -> PreviewKind.Terminal
}

fun isUiTree(node: UiNode?): Boolean = node != null
