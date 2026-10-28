package dev.webviewide.ui.bifold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BifoldLayoutTest {
    @Test fun endpointsDropTheHinge() {
        assertEquals(0f, bifoldContentWidth(0f, 1000f, 22f), 0.01f)
        assertEquals(1000f, bifoldContentWidth(1f, 1000f, 22f), 0.01f)

        val preview = bifoldSpans(0f, 1000f, 22f)
        assertEquals(0f, preview.code, 0.01f)
        assertEquals(0f, preview.hinge, 0.01f)
        assertEquals(1000f, preview.preview, 0.01f)

        val code = bifoldSpans(1f, 1000f, 22f)
        assertEquals(1000f, code.code, 0.01f)
        assertEquals(0f, code.hinge, 0.01f)
        assertEquals(0f, code.preview, 0.01f)
    }

    @Test fun evenSplitKeepsTheFullHinge() {
        val s = bifoldSpans(0.5f, 1000f, 22f)
        assertEquals(22f, s.hinge, 0.01f)
        assertEquals(489f, s.code, 0.01f)
        assertEquals(489f, s.preview, 0.01f)
    }

    @Test fun quarterSplitCollapsesTheHingeHalfway() {
        val s = bifoldSpans(0.25f, 1000f, 22f)
        assertEquals(11f, s.hinge, 0.01f)
        assertEquals(247.25f, s.code, 0.01f)
        assertEquals(741.75f, s.preview, 0.01f)
    }

    @Test fun spansCoverTheWidth() {
        for (f in listOf(0f, 0.12f, 0.25f, 0.5f, 0.88f, 1f)) {
            val s = bifoldSpans(f, 1080f, 40f)
            assertEquals(1080f, s.code + s.hinge + s.preview, 0.02f)
            val px = bifoldSpanPx(f, 1080f, 40f)
            assertEquals(1080, px.code + px.hinge + px.preview)
        }
    }

    @Test fun splitToPreviewMeasuresPreviewOnceAtFullWidth() {
        // The editor stays at the split width. The preview is measured at the
        // destination once, then the spring only opens the clip.
        val split = bifoldSpans(0.5f, 1080f, 22f)
        val measure = bifoldMeasureSpans(0.5f, 0f, 1080f, 22f)
        assertEquals(split.code, measure.code, 0.01f)
        assertEquals(1080f, measure.preview, 0.01f)
    }

    @Test fun liveSlotNeverExceedsTheMeasuredWidth() {
        val samples = (0..20).map { it / 20f }
        for (from in samples) {
            for (to in samples) {
                val measure = bifoldMeasureSpans(from, to, 1000f, 22f)
                for (i in 0..8) {
                    val t = from + (to - from) * (i / 8f)
                    val live = bifoldSpans(t, 1000f, 22f)
                    assertTrue(
                        "code at $t ($from→$to) ${live.code} > ${measure.code}",
                        live.code <= measure.code + 0.05f,
                    )
                    assertTrue(
                        "preview at $t ($from→$to) ${live.preview} > ${measure.preview}",
                        live.preview <= measure.preview + 0.05f,
                    )
                }
            }
        }
    }

    @Test fun previewContentStaysCenteredInTheOpeningSlot() {
        assertEquals(0, bifoldPreviewOffsetPx(489, 489))
        assertEquals(-255, bifoldPreviewOffsetPx(489, 1000))
        assertEquals(250, bifoldPreviewOffsetPx(1000, 500))
    }
}
