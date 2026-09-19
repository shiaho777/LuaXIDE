package dev.luaxide.ui.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.luaxide.engine.PropValue
import dev.luaxide.engine.UiNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TreeRenderingTest {
    @get:Rule val compose = createComposeRule()

    private fun n(type: String, vararg props: Pair<String, PropValue>, children: List<UiNode> = emptyList()) =
        UiNode(type, props.toMap(), children)
    private fun s(value: String) = PropValue.Str(value)
    private fun num(value: Int) = PropValue.Num(value.toDouble())
    private fun text(label: String, weight: Int? = null, align: String? = null): UiNode {
        val props = mutableMapOf<String, PropValue>("text" to s(label), "animate" to PropValue.Bool(false))
        weight?.let { props["weight"] = num(it) }
        align?.let { props["align"] = s(it) }
        return UiNode("text", props, emptyList())
    }
    private fun show(node: UiNode) {
        compose.setContent { MaterialTheme { Box(Modifier.size(300.dp)) { RenderTree(node, { _, _ -> }) } } }
    }

    @Test fun rowWeightsAllocateOneToTwo() {
        show(n("row", "width" to num(300), "spacing" to num(0), children = listOf(text("first", 1), text("second", 2))))
        val a = compose.onNodeWithText("first").getUnclippedBoundsInRoot()
        val b = compose.onNodeWithText("second").getUnclippedBoundsInRoot()
        assertEquals(100f, (b.left - a.left).value, 1f)
    }

    @Test fun columnWeightsAllocateOneToTwo() {
        show(n("column", "height" to num(300), "spacing" to num(0), children = listOf(text("first", 1), text("second", 2))))
        val a = compose.onNodeWithText("first").getUnclippedBoundsInRoot()
        val b = compose.onNodeWithText("second").getUnclippedBoundsInRoot()
        assertEquals(100f, (b.top - a.top).value, 1f)
    }

    @Test fun columnChildCentersHorizontally() {
        show(n("column", "width" to num(300), children = listOf(text("centered", align = "center"))))
        val b = compose.onNodeWithText("centered").getUnclippedBoundsInRoot()
        assertEquals(150f, (b.left.value + b.right.value) / 2, 1f)
    }

    @Test fun keyedInputStateFollowsReorder() {
        val a = n("input", "key" to s("a"), "label" to s("Alpha"), "value" to s(""))
        val b = n("input", "key" to s("b"), "label" to s("Beta"), "value" to s(""))
        val tree = mutableStateOf(n("column", children = listOf(a, b)))
        compose.setContent { MaterialTheme { RenderTree(tree.value, { _, _ -> }) } }
        compose.onNode(hasSetTextAction() and hasText("Alpha")).performTextInput("apple")
        compose.onNode(hasSetTextAction() and hasText("Beta")).performTextInput("banana")
        compose.runOnIdle { tree.value = n("column", children = listOf(b, a)) }
        compose.onNode(hasSetTextAction() and hasText("Alpha") and hasText("apple")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("Beta") and hasText("banana")).assertExists()
    }

    @Test fun duplicateSiblingKeysShowDiagnostic() {
        show(n("column", children = listOf(n("text", "key" to s("same")), n("input", "key" to s("same")))))
        compose.onNodeWithText("duplicate child key 'same' at root").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun boundedNestedScrollWorks() {
        val inner = n("scrollview", "height" to num(80), children = (1..8).map { text("item $it") })
        show(n("scrollview", "height" to num(240), children = listOf(inner)))
        compose.onNodeWithText("item 8").performScrollTo().assertIsDisplayed()
    }

    @Test fun unboundedNestedScrollShowsDiagnostic() {
        show(n("scrollview", "height" to num(240), children = listOf(n("scrollview", children = listOf(text("hidden"))))))
        compose.onNodeWithText("scrollview needs a bounded height. Set height on this nested scrollview or its parent.").assertExists()
        compose.onNodeWithText("hidden").assertDoesNotExist()
    }
}
