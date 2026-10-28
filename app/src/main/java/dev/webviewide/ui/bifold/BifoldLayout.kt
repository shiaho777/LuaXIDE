package dev.webviewide.ui.bifold

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Widths of the code pane, the hinge, and the preview.
 *
 * The hinge is full at an even split and collapses to nothing at either edge,
 * so a settled code or preview face gets the whole width. The three spans add
 * up to [totalPx].
 */
internal data class BifoldSpans(
    val code: Float,
    val hinge: Float,
    val preview: Float,
)

internal fun bifoldSpans(codeFrac: Float, totalPx: Float, hingePx: Float): BifoldSpans {
    val total = totalPx.coerceAtLeast(0f)
    val f = codeFrac.coerceIn(0f, 1f)
    val hingeFactor = (1f - abs(f - 0.5f) * 2f).coerceIn(0f, 1f)
    val hinge = min(hingePx.coerceAtLeast(0f) * hingeFactor, total)
    val usable = total - hinge
    return BifoldSpans(
        code = usable * f,
        hinge = hinge,
        preview = usable * (1f - f),
    )
}

/** Code-pane width. 0 at preview, the full width (no hinge) at code. */
internal fun bifoldContentWidth(frac: Float, totalPx: Float, hingePx: Float): Float =
    bifoldSpans(frac, totalPx, hingePx).code

/**
 * Measure each pane at the wider of the two endpoints. The spring then only
 * changes the visible slot, so the editor and the preview tree are not
 * remeasured on every frame.
 */
internal data class BifoldMeasure(
    val code: Float,
    val preview: Float,
)

internal fun bifoldMeasureSpans(
    fromFrac: Float,
    toFrac: Float,
    totalPx: Float,
    hingePx: Float,
): BifoldMeasure {
    val a = bifoldSpans(fromFrac, totalPx, hingePx)
    val b = bifoldSpans(toFrac, totalPx, hingePx)
    return BifoldMeasure(
        code = max(a.code, b.code),
        preview = max(a.preview, b.preview),
    )
}

internal data class BifoldSpanPx(
    val code: Int,
    val hinge: Int,
    val preview: Int,
)

/** Integer spans that add up to the rounded total. The remainder stays on the preview. */
internal fun bifoldSpanPx(codeFrac: Float, totalPx: Float, hingePx: Float): BifoldSpanPx {
    val spans = bifoldSpans(codeFrac, totalPx, hingePx)
    val total = totalPx.roundToInt().coerceAtLeast(0)
    if (total == 0) return BifoldSpanPx(0, 0, 0)
    val code = spans.code.roundToInt().coerceIn(0, total)
    val hinge = spans.hinge.roundToInt().coerceIn(0, total - code)
    return BifoldSpanPx(code, hinge, total - code - hinge)
}

/**
 * Where the preview's measured content sits in the visible slot.
 * The pane is the right-hand one; centering keeps a fixed-size board in the
 * middle of the opening while the slot grows, instead of pinning a corner.
 */
internal fun bifoldPreviewOffsetPx(slotPx: Int, measurePx: Int): Int =
    (slotPx - measurePx) / 2
