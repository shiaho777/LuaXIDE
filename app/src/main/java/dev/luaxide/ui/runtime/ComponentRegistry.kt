package dev.luaxide.ui.runtime

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
        put("icon") { n, e, r -> IconComponent(n, e, r) }
        put("spacer") { n, e, r -> SpacerComponent(n, e, r) }
        put("divider") { n, e, r -> DividerComponent(n, e, r) }
        put("scrollview") { n, e, r -> ScrollViewComponent(n, e, r) }
        put("list") { n, e, r -> ListComponent(n, e, r) }
        put("listitem") { n, e, r -> ListItemComponent(n, e, r) }
        put("stack") { n, e, r -> StackComponent(n, e, r) }
        put("page") { n, e, r -> PageComponent(n, e, r) }
        put("switch") { n, e, r -> SwitchComponent(n, e, r) }
        put("checkbox") { n, e, r -> CheckboxComponent(n, e, r) }
        put("slider") { n, e, r -> SliderComponent(n, e, r) }
        put("progress") { n, e, r -> ProgressComponent(n, e, r) }
        put("dialog") { n, e, r -> DialogComponent(n, e, r) }
        put("tabs") { n, e, r -> TabsComponent(n, e, r) }
        put("tab") { n, e, r -> TabComponent(n, e, r) }
    }

    operator fun get(type: String): Component? = components[type]

    val knownTypes: Set<String> get() = components.keys
}

private class HostHaptics(private val view: View) {
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    fun toggle() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

@Composable
private fun rememberHostHaptics(): HostHaptics {
    val view = LocalView.current
    return remember(view) { HostHaptics(view) }
}

private fun pageKey(pages: List<UiNode>, page: UiNode): String {
    val key = page.string("key")
    return key.ifEmpty { pages.indexOf(page).toString() }
}

@Composable
private fun AppComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = nodeStyle(node).fillMaxWidth()) {
        val title = node.string("title")
        if (title.isNotEmpty()) {
            Text(
                text = title,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun ColumnComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing = node.number("spacing", 8.0).dp
    Column(
        modifier = nodeStyle(node).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalAlignment = Alignment.Start,
    ) {
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun RowComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing = node.number("spacing", 8.0).dp
    Row(
        modifier = nodeStyle(node),
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
        )
    }
}

@Composable
private fun ButtonComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val handlerId = node.handler("onClick")
    val enabled = node.bool("enabled", true)
    val label = node.string("text", "button")
    val haptics = rememberHostHaptics()
    val shape = RoundedCornerShape(node.dpProp("radius") ?: 16.dp)
    val modifier = nodeStyle(node)
    val onClick: () -> Unit = {
        haptics.tap()
        if (handlerId != null) onEvent(handlerId, null)
    }
    when (node.string("kind").lowercase()) {
        "outlined" -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = modifier,
        ) { Text(label) }
        "text" -> TextButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = modifier,
        ) { Text(label) }
        else -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = parseHexColor(node.string("background"))
                    ?: MaterialTheme.colorScheme.primary,
            ),
            modifier = modifier,
        ) { Text(label) }
    }
}

@Composable
private fun CardComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing = node.number("spacing", 8.0).dp
    val radius = node.dpProp("radius") ?: 16.dp
    val pad = node.dpProp("padding") ?: 16.dp
    Card(
        shape = RoundedCornerShape(radius),
        colors = CardDefaults.cardColors(
            containerColor = parseHexColor(node.string("background"))
                ?: MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = nodeStyle(node).fillMaxWidth(),
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
    val enabled = node.bool("enabled", true)
    val onSubmit = node.handler("onSubmit")
    val onChange = node.handler("onChange")
    val buffer = remember { EchoBuffer(incoming) }
    var text by remember { mutableStateOf(incoming) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(incoming) { buffer.receive(incoming)?.let { text = it } }
    val keyboard = when (node.string("keyboard").lowercase()) {
        "number", "numeric" -> KeyboardType.Number
        "email" -> KeyboardType.Email
        "phone" -> KeyboardType.Phone
        "password" -> KeyboardType.Password
        else -> KeyboardType.Text
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            buffer.edit(it, onChange != null)
            if (onChange != null) onEvent(onChange, it)
        },
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (onSubmit != null) onEvent(onSubmit, text)
            focusManager.clearFocus()
        }),
        visualTransformation = if (keyboard == KeyboardType.Password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(node.dpProp("radius") ?: 14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = parseHexColor(node.string("background")) ?: Color.Transparent,
            unfocusedContainerColor = parseHexColor(node.string("background")) ?: Color.Transparent,
        ),
        modifier = nodeStyle(node).fillMaxWidth(),
    )
}

@Composable
private fun ImageComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val cs = MaterialTheme.colorScheme
    val size = node.number("size", 96.0).dp
    val src = node.string("src")
    val resolver = LocalAssetResolver.current
    val bmp = remember(src) {
        if (src.isBlank()) null else resolver.decodeBitmap(src)
    }
    Box(
        modifier = nodeStyle(node)
            .size(size)
            .clip(RoundedCornerShape(node.dpProp("radius") ?: 12.dp))
            .background(parseHexColor(node.string("background")) ?: cs.surfaceContainerHighest),
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
            Icon(
                imageVector = if (src.isBlank()) HostIcons.imagePlaceholder else HostIcons.imageBroken,
                contentDescription = null,
                tint = cs.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(size * 0.38f),
            )
        }
    }
}

@Composable
private fun IconComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val size = node.number("size", 24.0).dp
    val name = node.string("name")
    val vector = HostIcons.resolve(name)
    val tint = parseHexColor(node.string("color")) ?: MaterialTheme.colorScheme.onSurface
    Icon(
        imageVector = vector ?: HostIcons.fallback,
        contentDescription = name.ifBlank { null },
        tint = if (vector == null) tint.copy(alpha = 0.38f) else tint,
        modifier = nodeStyle(node).size(size),
    )
}

@Composable
private fun SpacerComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    Spacer(modifier = nodeStyle(node).height(node.number("size", 8.0).dp))
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
    val spacing = node.number("spacing", 8.0).dp
    BoxWithConstraints(modifier = nodeStyle(node).fillMaxWidth(), propagateMinConstraints = true) {
        if (!constraints.hasBoundedHeight) {
            Text(
                "scrollview needs a bounded height. Set height on this nested scrollview or its parent.",
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                renderChildren(node.children) { childModifier(it) }
            }
        }
    }
}

@Composable
private fun ListComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing = node.number("spacing", 0.0).dp
    Column(
        modifier = nodeStyle(node).fillMaxWidth(),
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
    val enabled = node.bool("enabled", true)
    val iconName = node.string("icon")
    val leading = HostIcons.resolve(iconName)
    val haptics = rememberHostHaptics()
    val colors = ListItemDefaults.colors(
        containerColor = parseHexColor(node.string("background")) ?: Color.Transparent,
    )
    Column(modifier = nodeStyle(node).fillMaxWidth()) {
        ListItem(
            headlineContent = {
                if (title.isNotEmpty()) {
                    Text(title, color = cs.onSurface)
                }
            },
            supportingContent = if (subtitle.isNotEmpty()) {
                { Text(subtitle, color = cs.onSurfaceVariant) }
            } else {
                null
            },
            leadingContent = if (leading != null || iconName.isNotEmpty()) {
                {
                    Icon(
                        imageVector = leading ?: HostIcons.fallback,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                    )
                }
            } else {
                null
            },
            trailingContent = if (click != null) {
                {
                    Icon(
                        imageVector = HostIcons.chevron,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                    )
                }
            } else {
                null
            },
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(
                    if (click != null) {
                        Modifier.clickable(enabled = enabled) {
                            haptics.tap()
                            onEvent(click, null)
                        }
                    } else {
                        Modifier
                    },
                ),
        )
        if (node.children.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                renderChildren(node.children) { childModifier(it) }
            }
        }
    }
}

@Composable
private fun StackComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val pages = node.children.filter { it.type == "page" }
    if (pages.isEmpty()) {
        Column(modifier = nodeStyle(node).fillMaxWidth()) {
            renderChildren(node.children) { childModifier(it) }
        }
        return
    }
    val selected = pages.firstOrNull { it.string("key") == node.string("selected") } ?: pages.first()
    val selectedKey = pageKey(pages, selected)
    val selectedIndex = pages.indexOf(selected).coerceAtLeast(0)
    var previousIndex by remember { mutableIntStateOf(selectedIndex) }
    val forward = selectedIndex >= previousIndex
    SideEffect { previousIndex = selectedIndex }
    AnimatedContent(
        targetState = selectedKey,
        transitionSpec = {
            Motion.sharedAxisX(forward) using SizeTransform(clip = false)
        },
        contentAlignment = Alignment.TopStart,
        label = "stack-page",
        modifier = nodeStyle(node).fillMaxWidth().clipToBounds(),
    ) { key ->
        val page = pages.firstOrNull { pageKey(pages, it) == key } ?: selected
        RenderTree(node = page, onEvent = onEvent, path = "stack/$key")
    }
}

@Composable
private fun PageComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val spacing = node.number("spacing", 8.0).dp
    Column(
        modifier = nodeStyle(node).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        renderChildren(node.children) { childModifier(it) }
    }
}

@Composable
private fun SwitchComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val label = node.string("label", "switch")
    val checkedProp = node.bool("checked", false)
    var checked by remember { mutableStateOf(checkedProp) }
    LaunchedEffect(checkedProp) { checked = checkedProp }
    val onToggle = node.handler("onToggle")
    val enabled = node.bool("enabled", true)
    val haptics = rememberHostHaptics()
    fun apply(next: Boolean) {
        if (checked == next) return
        checked = next
        haptics.toggle()
        if (onToggle != null) onEvent(onToggle, next.toString())
    }
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = parseHexColor(node.string("background")) ?: Color.Transparent,
        ),
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { apply(it) },
            ),
    )
}

@Composable
private fun CheckboxComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val label = node.string("label", "checkbox")
    val checkedProp = node.bool("checked", false)
    var checked by remember { mutableStateOf(checkedProp) }
    LaunchedEffect(checkedProp) { checked = checkedProp }
    val onToggle = node.handler("onToggle")
    val enabled = node.bool("enabled", true)
    val haptics = rememberHostHaptics()
    fun apply(next: Boolean) {
        if (checked == next) return
        checked = next
        haptics.toggle()
        if (onToggle != null) onEvent(onToggle, next.toString())
    }
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = parseHexColor(node.string("background")) ?: Color.Transparent,
        ),
        modifier = nodeStyle(node)
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { apply(it) },
            ),
    )
}

@Composable
private fun BoxComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    Box(modifier = nodeStyle(node)) {
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
        Text(
            "slider requires finite value/from/to, to > from and step >= 0",
            modifier = nodeStyle(node),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    fun snap(value: Double): Double {
        val bounded = value.coerceIn(from, to)
        if (step == 0.0) return bounded
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
    val enabled = node.bool("enabled", true)
    val haptics = rememberHostHaptics()
    Slider(
        value = value.toFloat(),
        onValueChange = {
            val next = snap(it.toDouble())
            if (next != value) {
                value = next
                if (step > 0.0) haptics.tick()
                buffer.edit(next, onChange != null)
                onChange?.let { handler -> onEvent(handler, next.toString()) }
            }
        },
        enabled = enabled,
        valueRange = from.toFloat()..to.toFloat(),
        steps = 0,
        modifier = nodeStyle(node).fillMaxWidth(),
    )
}

@Composable
private fun ProgressComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val raw = node.number("value", Double.NaN)
    val color = parseHexColor(node.string("color")) ?: MaterialTheme.colorScheme.primary
    val circular = node.string("kind").equals("circular", ignoreCase = true)
    val modifier = nodeStyle(node).then(if (circular) Modifier else Modifier.fillMaxWidth())
    if (circular) {
        if (raw.isNaN()) {
            CircularProgressIndicator(color = color, modifier = modifier)
        } else {
            CircularProgressIndicator(
                progress = { raw.coerceIn(0.0, 1.0).toFloat() },
                color = color,
                modifier = modifier,
            )
        }
    } else if (raw.isNaN()) {
        LinearProgressIndicator(color = color, modifier = modifier)
    } else {
        LinearProgressIndicator(
            progress = { raw.coerceIn(0.0, 1.0).toFloat() },
            color = color,
            modifier = modifier,
        )
    }
}

@Composable
private fun DialogComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val open = node.bool("open", false)
    if (!open) return
    val title = node.string("title")
    val confirm = node.string("confirm", "OK")
    val cancel = node.string("cancel")
    val onConfirm = node.handler("onConfirm")
    val onDismiss = node.handler("onDismiss")
    val haptics = rememberHostHaptics()
    AlertDialog(
        onDismissRequest = { onDismiss?.let { onEvent(it, null) } },
        title = if (title.isNotEmpty()) {
            { Text(title) }
        } else {
            null
        },
        text = if (node.children.isNotEmpty()) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    renderChildren(node.children) { childModifier(it) }
                }
            }
        } else {
            null
        },
        confirmButton = {
            if (confirm.isNotEmpty()) {
                TextButton(onClick = {
                    haptics.tap()
                    onConfirm?.let { onEvent(it, null) }
                }) { Text(confirm) }
            }
        },
        dismissButton = if (cancel.isNotEmpty()) {
            {
                TextButton(onClick = {
                    haptics.tap()
                    onDismiss?.let { onEvent(it, null) }
                }) { Text(cancel) }
            }
        } else {
            null
        },
    )
}

@Composable
private fun TabsComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val tabs = node.children.filter { it.type == "tab" }
    if (tabs.isEmpty()) return
    val selectedKey = node.string("selected")
    val selectedIndex = tabs.indexOfFirst { tab ->
        val key = tab.string("key")
        key.isNotEmpty() && key == selectedKey
    }.let { if (it < 0) 0 else it }
    val onSelect = node.handler("onSelect")
    val haptics = rememberHostHaptics()
    val tabContent: @Composable () -> Unit = {
        tabs.forEachIndexed { index, tab ->
            val label = tab.string("label", tab.string("text"))
            val iconName = tab.string("icon")
            val icon = HostIcons.resolve(iconName)
            val key = tab.string("key").ifEmpty { index.toString() }
            Tab(
                selected = index == selectedIndex,
                onClick = {
                    if (onSelect != null && key != selectedKey) {
                        haptics.tap()
                        onEvent(onSelect, key)
                    }
                },
                text = if (label.isNotEmpty()) {
                    { Text(label) }
                } else {
                    null
                },
                icon = if (icon != null) {
                    { Icon(icon, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
    if (tabs.size > 4) {
        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            modifier = nodeStyle(node).fillMaxWidth(),
            edgePadding = 8.dp,
            divider = {},
            tabs = tabContent,
        )
    } else {
        TabRow(
            selectedTabIndex = selectedIndex,
            modifier = nodeStyle(node).fillMaxWidth(),
            divider = {},
            tabs = tabContent,
        )
    }
}

@Composable
private fun TabComponent(node: UiNode, onEvent: OnEvent, renderChildren: RenderChildren) {
    val label = node.string("label", node.string("text"))
    if (label.isNotEmpty()) {
        Text(label, modifier = nodeStyle(node), style = MaterialTheme.typography.labelLarge)
    }
}
