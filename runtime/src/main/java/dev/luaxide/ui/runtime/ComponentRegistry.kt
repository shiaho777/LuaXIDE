package dev.luaxide.ui.runtime

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.assets.LocalAssetResolver
import dev.luaxide.engine.UiNode

typealias RenderChildren = @Composable (List<UiNode>, (UiNode) -> Modifier) -> Unit

typealias OnEvent = (handlerId: Int, payload: String?) -> Unit

typealias Component = @Composable (node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) -> Unit

object ComponentRegistry {
    private val components: Map<String, Component> = buildMap {
        put("app") { n, e, r -> AppComponent(n, e, r) }
        put("column") { n, e, r -> ColumnComponent(n, e, r) }
        put("row") { n, e, r -> RowComponent(n, e, r) }
        put("box") { n, e, r -> BoxComponent(n, e, r) }
        put("text") { n, e, r -> TextComponent(n, e, r) }
        put("button") { n, e, r -> ButtonComponent(n, e, r) }
        put("card") { n, e, r -> CardComponent(n, e, r) }
        put("input") { n, e, r -> InputComponent(n, e, r) }
        put("image") { n, e, r -> ImageComponent(n, e, r) }
        put("spacer") { n, e, r -> SpacerComponent(n, e, r) }
        put("divider") { n, e, r -> DividerComponent(n, e, r) }
        put("scrollview") { n, e, r -> ScrollViewComponent(n, e, r) }
        put("list") { n, e, r -> ListComponent(n, e, r) }
        put("listitem") { n, e, r -> ListItemComponent(n, e, r) }
        put("stack") { n, e, r -> StackComponent(n, e, r) }
        put("page") { n, e, r -> PageComponent(n, e, r) }
        put("switch") { n, e, r -> SwitchComponent(n, e, r) }
        put("slider") { n, e, r -> SliderComponent(n, e, r) }
        put("progress") { n, e, r -> ProgressComponent(n, e, r) }
    }

    operator fun get(type: String): Component? = components[type]

    val knownTypes: Set<String> get() = components.keys
}

@Composable
private fun AppComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
    ) {
        val title = node.string("title")
        if (title.isNotEmpty()) {
            AnimatedContent(
                targetState = title,
                transitionSpec = { Motion.textSwap() },
                label = "app-title",
            ) { value ->
                Text(
                    text = value,
                    color = cs.onSurface,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun ColumnComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing by animateDpAsState(
        targetValue = node.number("spacing", 8.0).dp,
        animationSpec = Motion.softDp,
        label = "col-spacing",
    )
    Column(
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalAlignment = Alignment.Start,
    ) {
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun RowComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing by animateDpAsState(
        targetValue = node.number("spacing", 8.0).dp,
        animationSpec = Motion.softDp,
        label = "row-spacing",
    )
    Row(
        modifier = nodeStyle(node).animateContentSize(animationSpec = Motion.contentSize),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun TextComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    val text = node.string("text")
    val size = node.number("size", 16.0).sp
    val fontPath = node.string("font")
    val color = parseHexColor(node.string("color")) ?: cs.onSurface
    val animate = node.bool("animate", true)
    val bold = node.bool("bold", false)
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    val resolver = LocalAssetResolver.current
    // Font file metadata stays Normal; bold comes from Text's fontWeight (synthetic bold).
    val family = remember(fontPath, resolver) {
        val file = if (fontPath.isBlank()) null else resolver.resolveFile(fontPath)
        if (file != null) FontFamily(Font(file = file, weight = FontWeight.Normal)) else FontFamily.Default
    }
    val textNode = @Composable { value: String ->
        Text(
            text = value,
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = family,
            modifier = nodeStyle(node),
        )
    }
    if (!animate) {
        // Cheap path for fast-updating text (game boards, clocks): no AnimatedContent.
        textNode(text)
        return
    }
    AnimatedContent(
        targetState = text,
        transitionSpec = { Motion.textSwap() },
        label = "text",
        modifier = nodeStyle(node),
    ) { value ->
        Text(
            text = value,
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = family,
            modifier = Modifier.animateContentSize(animationSpec = Motion.contentSize),
        )
    }
}

@Composable
private fun ButtonComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val handlerId = node.handler("onClick")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.snappyFloat,
        label = "btn-scale",
    )
    val label = node.string("text", "button")
    androidx.compose.material3.Button(
        onClick = { handlerId?.let { onEvent(it, null) } },
        shape = RoundedCornerShape(node.dpProp("radius") ?: 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = parseHexColor(node.string("background")) ?: MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        modifier = nodeStyle(node)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .animateContentSize(animationSpec = Motion.contentSize),
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = { Motion.textSwap() },
            label = "btn-label",
        ) { value ->
            Text(value)
        }
    }
}

@Composable
private fun CardComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing by animateDpAsState(
        targetValue = node.number("spacing", 8.0).dp,
        animationSpec = Motion.softDp,
        label = "card-spacing",
    )
    val radius by animateDpAsState(
        targetValue = node.dpProp("radius") ?: 16.dp,
        animationSpec = Motion.softDp,
        label = "card-radius",
    )
    val pad by animateDpAsState(
        targetValue = node.dpProp("padding") ?: 16.dp,
        animationSpec = Motion.softDp,
        label = "card-padding",
    )
    Card(
        shape = RoundedCornerShape(radius),
        colors = CardDefaults.cardColors(
            containerColor = parseHexColor(node.string("background"))
                ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
    ) {
        Column(
            modifier = Modifier.padding(pad),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            renderChildren(node.children) { childModifier(it) }
        }
    }
}

@Composable
private fun InputComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val incoming = node.string("value")
    val label = node.string("label", "input")
    val onSubmit = node.handler("onSubmit")
    val onChange = node.handler("onChange")
    val buffer = remember { EchoBuffer(incoming) }
    var text by remember { mutableStateOf(incoming) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(incoming) { buffer.receive(incoming)?.let { text = it } }
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            buffer.edit(it, onChange != null)
            if (onChange != null) onEvent(onChange, it)
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            // Event payload contract: the field's text is passed back to the script.
            if (onSubmit != null) onEvent(onSubmit, text)
            focusManager.clearFocus()
        }),
        label = {
            AnimatedContent(
                targetState = label,
                transitionSpec = { Motion.textSwap() },
                label = "input-label",
            ) { value -> Text(value) }
        },
        singleLine = true,
        shape = RoundedCornerShape(node.dpProp("radius") ?: 14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = parseHexColor(node.string("background")) ?: Color.Transparent,
            unfocusedContainerColor = parseHexColor(node.string("background")) ?: Color.Transparent,
        ),
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
    )
}

@Composable
private fun ImageComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    val size by animateDpAsState(
        targetValue = node.number("size", 96.0).dp,
        animationSpec = Motion.softDp,
        label = "image-size",
    )
    val src = node.string("src")
    val resolver = LocalAssetResolver.current
    val bmp = remember(src) {
        if (src.isBlank()) null else resolver.decodeBitmap(src)
    }
    Box(
        modifier = nodeStyle(node)
            .size(size)
            .clip(RoundedCornerShape(node.dpProp("radius") ?: 12.dp))
            .background(parseHexColor(node.string("background")) ?: cs.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = src,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                if (src.isBlank()) "image" else "missing",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SpacerComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val h by animateDpAsState(
        targetValue = node.number("size", 8.0).dp,
        animationSpec = Motion.softDp,
        label = "spacer",
    )
    Spacer(modifier = nodeStyle(node).height(h))
}

@Composable
private fun DividerComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    HorizontalDivider(
        modifier = nodeStyle(node),
        color = parseHexColor(node.string("color")) ?: MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun ScrollViewComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing by animateDpAsState(
        targetValue = node.number("spacing", 8.0).dp,
        animationSpec = Motion.softDp,
        label = "scroll-spacing",
    )
    BoxWithConstraints(modifier = nodeStyle(node).fillMaxWidth(), propagateMinConstraints = true) {
        if (!constraints.hasBoundedHeight) {
            Text(
                "scrollview needs a bounded height. Set height on this nested scrollview or its parent.",
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .animateContentSize(Motion.contentSize),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                renderChildren(node.children) { childModifier(it) }
            }
        }
    }
}

@Composable
private fun ListComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing by animateDpAsState(
        targetValue = node.number("spacing", 8.0).dp,
        animationSpec = Motion.softDp,
        label = "list-spacing",
    )
    Column(
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun ListItemComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    val title = node.string("title")
    val subtitle = node.string("subtitle")
    val click = node.handler("onClick")
    Surface(
        color = parseHexColor(node.string("background"))
            ?: cs.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(node.dpProp("radius") ?: 14.dp),
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .clip(RoundedCornerShape(node.dpProp("radius") ?: 14.dp))
            .then(
                if (click != null) Modifier.clickable { onEvent(click, null) } else Modifier,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (title.isNotEmpty()) {
                Text(title, color = cs.onSurface, style = MaterialTheme.typography.titleSmall)
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (node.children.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = if (title.isNotEmpty() || subtitle.isNotEmpty()) 8.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    renderChildren(node.children) { childModifier(it) }
                }
            }
        }
    }
}

@Composable
private fun StackComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    Column(
        modifier = nodeStyle(node).fillMaxWidth().animateContentSize(Motion.contentSize),
    ) {
        renderChildren(node.visibleChildren()) { childModifier(it) }
    }
}

@Composable
private fun PageComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing by animateDpAsState(
        targetValue = node.number("spacing", 8.0).dp,
        animationSpec = Motion.softDp,
        label = "page-spacing",
    )
    Column(
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun SwitchComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    val label = node.string("label", "switch")
    val checkedProp = node.bool("checked", false)
    var checked by remember(checkedProp) { mutableStateOf(checkedProp) }
    LaunchedEffect(checkedProp) { checked = checkedProp }
    val onToggle = node.handler("onToggle")
    Row(
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = cs.onSurface, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                if (onToggle != null) onEvent(onToggle, checked.toString())
            },
        )
    }
}

@Composable
private fun BoxComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    Box(modifier = nodeStyle(node).animateContentSize(Motion.contentSize)) {
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun SliderComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val from = node.number("from", 0.0)
    val to = node.number("to", 1.0)
    val step = node.number("step", 0.0)
    val raw = node.number("value", from)
    val valid = from.isFinite() && to.isFinite() && from.toFloat().isFinite() &&
        to.toFloat().isFinite() && to.toFloat() > from.toFloat() &&
        (to - from).toFloat().isFinite() && step.isFinite() && step >= 0 && raw.isFinite()
    if (!valid) {
        Text("slider requires finite value/from/to, to > from and step >= 0", modifier = nodeStyle(node),
            color = MaterialTheme.colorScheme.error)
        return
    }
    fun snap(value: Double): Double {
        val bounded = value.coerceIn(from, to)
        if (step == 0.0) return bounded
        // Increment anchored at from, not Material's evenly spaced tick count.
        // The upper endpoint remains reachable even for non-divisible ranges.
        val units = (bounded - from) / step
        if (!units.isFinite()) return bounded
        val lower = (from + kotlin.math.floor(units) * step).coerceIn(from, to)
        val upper = (lower + step).coerceIn(from, to)
        return if (bounded - lower < upper - bounded) lower else upper
    }
    val incoming = snap(raw)
    val buffer = remember(from, to, step) { EchoBuffer(incoming) }
    var value by remember(from, to, step) { mutableStateOf(incoming) }
    LaunchedEffect(incoming) { buffer.receive(incoming)?.let { value = it } }
    val onChange = node.handler("onChange")
    Slider(
        value = value.toFloat(),
        onValueChange = {
            val next = snap(it.toDouble())
            if (next != value) {
                value = next
                buffer.edit(next, onChange != null)
                onChange?.let { handler -> onEvent(handler, next.toString()) }
            }
        },
        valueRange = from.toFloat()..to.toFloat(),
        steps = 0,
        modifier = nodeStyle(node).fillMaxWidth(),
    )
}

@Composable
private fun ProgressComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val raw = node.number("value", Double.NaN)
    val color = parseHexColor(node.string("color")) ?: MaterialTheme.colorScheme.primary
    if (raw.isNaN()) {
        LinearProgressIndicator(color = color, modifier = nodeStyle(node).fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { raw.coerceIn(0.0, 1.0).toFloat() }, color = color,
            modifier = nodeStyle(node).fillMaxWidth())
    }
}
