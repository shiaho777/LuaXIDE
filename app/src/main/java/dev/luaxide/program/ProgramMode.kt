package dev.luaxide.program

import dev.luaxide.engine.RunResult
import dev.luaxide.engine.UiNode

/**
 * What the preview face renders. Output-only programs no longer get their own
 * face — print output lives in the console sheet — so a successful run without
 * a ui tree simply falls back to Idle.
 */
enum class PreviewKind {
    Idle,
    Ui,
    Error,
}

fun resolvePreviewKind(result: RunResult?): PreviewKind = when {
    result == null -> PreviewKind.Idle
    !result.ok -> PreviewKind.Error
    result.tree != null -> PreviewKind.Ui
    else -> PreviewKind.Idle
}

fun isUiTree(node: UiNode?): Boolean = node != null
