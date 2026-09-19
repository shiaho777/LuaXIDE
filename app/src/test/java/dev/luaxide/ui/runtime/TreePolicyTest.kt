package dev.luaxide.ui.runtime

import dev.luaxide.engine.PropValue
import dev.luaxide.engine.UiNode
import org.junit.Assert.*
import org.junit.Test

class TreePolicyTest {
    private fun node(type: String, key: String = "", vararg children: UiNode) =
        UiNode(type, if (key.isEmpty()) emptyMap() else mapOf("key" to PropValue.Str(key)), children.toList())
    private fun UiNode.num(name: String, value: Double) = copy(props = props + (name to PropValue.Num(value)))

    @Test fun duplicateKeysAreDetectedAcrossTypesAndIdAliases() {
        assertEquals("a", duplicateChildKey(listOf(node("input", "a"), node("text", "a"))))
        assertEquals("a", duplicateChildKey(listOf(node("input", "a"), node("text").copy(props = mapOf("id" to PropValue.Str("a"))))))
        assertNull(duplicateChildKey(listOf(node("text"), node("text"))))
        assertNull(duplicateChildKey(listOf(node("text", "a"), node("text", "b"))))
    }

    @Test fun reorderAndReinsertPreserveTrackingUntilExitCompletes() {
        val tracks = androidx.compose.runtime.mutableStateListOf<TrackedChild>()
        val a = node("input", "a")
        val b = node("input", "b")
        applyIncoming(tracks, mapIncoming(listOf(a, b), "root"), false)
        val original = tracks.first()
        applyIncoming(tracks, mapIncoming(listOf(b, a), "root"), true)
        assertSame(original, tracks.last())
        applyIncoming(tracks, mapIncoming(listOf(b), "root"), true)
        assertFalse(original.appear.targetState)
        applyIncoming(tracks, mapIncoming(listOf(a, b), "root"), true)
        assertSame(original, tracks.first())
        assertTrue(original.appear.targetState)
        assertEquals(2, tracks.size)
    }

    @Test fun typeReplacementCreatesNewTracking() {
        val tracks = androidx.compose.runtime.mutableStateListOf<TrackedChild>()
        applyIncoming(tracks, mapIncoming(listOf(node("input", "a")), "root"), false)
        val original = tracks.first()
        applyIncoming(tracks, mapIncoming(listOf(node("text", "a")), "root"), true)
        val replacement = tracks.first { it.node.type == "text" }
        assertNotSame(original, replacement)
        assertFalse(original.appear.targetState)
        assertTrue(replacement.appear.targetState)
    }

    @Test fun sameKeyDifferentTypeHasDifferentIdentity() {
        assertNotEquals(nodeIdentity(node("input", "field"), "root"), nodeIdentity(node("text", "field"), "root"))
    }

    @Test fun keySurvivesReorderAndTextChange() {
        val first = node("input", "a")
        assertEquals(nodeIdentity(first, "root/input[0]"), nodeIdentity(first, "root/input[1]"))
        assertEquals(nodeIdentity(first, "root"), nodeIdentity(first.copy(props = first.props + ("value" to PropValue.Str("new"))), "root"))
    }

    @Test fun explicitKeyPrecedesId() {
        val first = node("text", "a")
        assertEquals(nodeIdentity(first, "root"), nodeIdentity(first.copy(props = first.props + ("id" to PropValue.Str("b"))), "root"))
    }

    @Test fun unkeyedNodesKeepPositionIdentity() {
        assertNotEquals(nodeIdentity(node("input"), "root/input[0]"), nodeIdentity(node("input"), "root/input[1]"))
    }

    @Test fun horizontalWeightDoesNotDisableVerticalHostScrolling() {
        assertFalse(node("row", "", node("text").num("weight", 1.0)).needsFiniteViewport())
    }

    @Test fun verticalWeightNeedsFiniteViewport() {
        assertTrue(node("column", "", node("text").num("weight", 1.0)).needsFiniteViewport())
    }

    @Test fun explicitHeightContainsViewportRequirement() {
        assertFalse(node("column", "", node("text").num("weight", 1.0)).num("height", 200.0).needsFiniteViewport())
        assertFalse(node("scrollview").num("height", 120.0).needsFiniteViewport())
        assertTrue(node("scrollview").needsFiniteViewport())
    }

    @Test fun onlySelectedPageParticipates() {
        val stack = node("stack", "", node("page", "plain", node("text")), node("page", "scroll", node("scrollview")))
        assertFalse(stack.needsFiniteViewport())
        assertTrue(stack.copy(props = mapOf("selected" to PropValue.Str("scroll"))).needsFiniteViewport())
    }

    @Test fun meaninglessRootAndBoxWeightsDoNotDisableScrolling() {
        assertFalse(node("text").num("weight", 1.0).needsFiniteViewport())
        assertFalse(node("box", "", node("text").num("weight", 1.0)).needsFiniteViewport())
    }
}
