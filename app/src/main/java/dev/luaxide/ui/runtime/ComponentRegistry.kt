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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.assets.LocalAssetResolver
import dev.luaxide.engine.UiNode

typealias RenderChildren = @Composable (List<UiNode>) -> Unit

typealias OnEvent = (handlerId: Int) -> Unit

typealias Component = @Composable (node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) -> Unit

object ComponentRegistry {
    private val components: Map<String, Component> = buildMap {
        put("app") { n, e, r -> AppComponent(n, e, r) }
        put("column") { n, e, r -> ColumnComponent(n, e, r) }
        put("row") { n, e, r -> RowComponent(n, e, r) }
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
    }

    operator fun get(type: String): Component? = components[type]

    val knownTypes: Set<String> get() = components.keys
}

@Composable
private fun AppComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
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
        renderChildren(node.children)
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
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        renderChildren(node.children)
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
        modifier = Modifier.animateContentSize(animationSpec = Motion.contentSize),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        renderChildren(node.children)
    }
}

@Composable
private fun TextComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    val text = node.string("text", node.string("value", ""))
    val size = node.number("size", 16.0).sp
    val fontPath = node.string("font", node.string("typeface", ""))
    val color = parseColor(node.string("color"), cs.onSurface)
    val animate = node.bool("animate", true)
    val resolver = LocalAssetResolver.current
    val family = remember(fontPath) {
        val file = if (fontPath.isBlank()) null else resolver.resolveFile(fontPath)
        if (file != null) FontFamily(Font(file = file, weight = FontWeight.Normal)) else FontFamily.Default
    }
    if (!animate) {
        // Cheap path for fast-updating text (game boards, clocks): no AnimatedContent.
        Text(
            text = text,
            color = color,
            fontSize = size,
            fontFamily = family,
        )
        return
    }
    AnimatedContent(
        targetState = text,
        transitionSpec = { Motion.textSwap() },
        label = "text",
    ) { value ->
        Text(
            text = value,
            color = color,
            fontSize = size,
            fontFamily = family,
            modifier = Modifier.animateContentSize(animationSpec = Motion.contentSize),
        )
    }
}

/** Parse "#RRGGBB" or "#AARRGGBB" (also bare RRGGBB); falls back to [fallback]. */
private fun parseColor(hex: String, fallback: Color): Color {
    val h = hex.removePrefix("#")
    if (h.length != 6 && h.length != 8) return fallback
    val v = h.toLongOrNull(16) ?: return fallback
    return if (h.length == 8) {
        Color((v and 0xFFFFFFFFL).toInt())
    } else {
        Color(0xFF000000L or v)
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
        onClick = { handlerId?.let(onEvent) },
        shape = RoundedCornerShape(16.dp),
        interactionSource = interaction,
        modifier = Modifier
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
        targetValue = node.number("radius", 16.0).dp,
        animationSpec = Motion.softDp,
        label = "card-radius",
    )
    val pad by animateDpAsState(
        targetValue = node.number("padding", 16.0).dp,
        animationSpec = Motion.softDp,
        label = "card-padding",
    )
    Card(
        shape = RoundedCornerShape(radius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
    ) {
        Column(
            modifier = Modifier.padding(pad),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            renderChildren(node.children)
        }
    }
}

@Composable
private fun InputComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val incoming = node.string("value")
    val label = node.string("label", "input")
    var text by remember { mutableStateOf(incoming) }
    LaunchedEffect(incoming) {
        if (text != incoming) text = incoming
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = {
            AnimatedContent(
                targetState = label,
                transitionSpec = { Motion.textSwap() },
                label = "input-label",
            ) { value -> Text(value) }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
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
    val src = node.string("src", node.string("path", node.string("file", "")))
    val resolver = LocalAssetResolver.current
    val bmp = remember(src) {
        if (src.isBlank()) null else resolver.decodeBitmap(src)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = src,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
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
    Spacer(modifier = Modifier.height(h))
}

@Composable
private fun DividerComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    HorizontalDivider()
}

@Composable
private fun ScrollViewComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing by animateDpAsState(
        targetValue = node.number("spacing", 8.0).dp,
        animationSpec = Motion.softDp,
        label = "scroll-spacing",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .animateContentSize(animationSpec = Motion.contentSize),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        renderChildren(node.children)
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
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        renderChildren(node.children)
    }
}

@Composable
private fun ListItemComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    val title = node.string("title", node.string("text", ""))
    val subtitle = node.string("subtitle")
    val click = node.handler("onClick")
    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (click != null) Modifier.clickable { onEvent(click) } else Modifier,
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
                    renderChildren(node.children)
                }
            }
        }
    }
}

@Composable
private fun StackComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val selected = node.string("selected", node.string("page", ""))
    val pages = node.children.filter { it.type.equals("page", ignoreCase = true) }
    val page = when {
        pages.isEmpty() -> null
        selected.isEmpty() -> pages.first()
        else -> pages.firstOrNull {
            it.string("key") == selected || it.string("name") == selected || it.string("id") == selected
        } ?: pages.first()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
    ) {
        if (page != null) {
            renderChildren(page.children)
        } else {
            renderChildren(node.children)
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.contentSize),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        renderChildren(node.children)
    }
}

@Composable
private fun SwitchComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    val label = node.string("label", node.string("text", "switch"))
    val checkedProp = node.bool("checked", node.bool("value", false))
    var checked by remember(checkedProp) { mutableStateOf(checkedProp) }
    LaunchedEffect(checkedProp) { checked = checkedProp }
    val onChange = node.handler("onChange")
    val onToggle = node.handler("onToggle") ?: onChange
    Row(
        modifier = Modifier
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
                if (onToggle != null) onEvent(onToggle)
            },
        )
    }
}
