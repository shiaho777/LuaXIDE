package dev.luaxide.ui.build

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.build.ArtifactKind
import dev.luaxide.build.BuildArtifact
import dev.luaxide.build.BuildConfig
import dev.luaxide.build.BuildStage
import dev.luaxide.build.KeystoreInfo
import dev.luaxide.build.Orientation
import dev.luaxide.build.SignMode
import dev.luaxide.build.StageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildSheet(
    vm: BuildViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val progress by vm.progress.collectAsState()

    ModalBottomSheet(
        onDismissRequest = {
            if (!progress.running) onDismiss()
        },
        sheetState = sheetState,
        dragHandle = { SheetHandle() },
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        tonalElevation = 2.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        BuildSheetContent(
            vm = vm,
            onClose = {
                if (progress.running) return@BuildSheetContent
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            },
        )
    }
}

@Composable
private fun SheetHandle() {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(cs.onSurfaceVariant.copy(alpha = 0.28f)),
        )
    }
}

@Composable
private fun BuildSheetContent(
    vm: BuildViewModel,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val config by vm.config.collectAsState()
    val progress by vm.progress.collectAsState()
    val artifacts by vm.artifacts.collectAsState()
    val toast by vm.toast.collectAsState()
    val keystores by vm.keystores.collectAsState()
    val iconPreview by vm.iconPreview.collectAsState()

    var pendingKeystoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var section by remember { mutableStateOf(BuildSection.APP) }

    val pickIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(vm::importIconFromUri) }

    val pickKeystore = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> pendingKeystoreUri = uri }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Build",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
                Text(
                    config.appName.ifBlank { "package app" },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onClose, enabled = !progress.running) {
                Text("Close")
            }
        }

        SectionTabs(
            selected = section,
            onSelect = { section = it },
            hasArtifacts = artifacts.isNotEmpty() || progress.stages.isNotEmpty(),
        )

        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                BuildSection.APP -> {
                    item {
                        AppSection(
                            config = config,
                            enabled = !progress.running,
                            onChange = vm::updateConfig,
                        )
                    }
                    item {
                        IconSection(
                            config = config,
                            preview = iconPreview,
                            enabled = !progress.running,
                            onPick = { pickIcon.launch("image/*") },
                            onClear = vm::clearIcon,
                            onBg = vm::setIconBgColor,
                        )
                    }
                }
                BuildSection.SIGN -> {
                    item {
                        SigningSection(
                            config = config,
                            keystores = keystores,
                            enabled = !progress.running,
                            onSignMode = vm::setSignMode,
                            onSelectKeystore = vm::selectKeystore,
                            onDeleteKeystore = vm::deleteKeystore,
                            onExportKeystore = vm::exportKeystore,
                            onCreate = { a, s, k, n -> vm.createReleaseKeystore(a, s, k, n) },
                            onImportClick = { pickKeystore.launch("*/*") },
                            pendingImportUri = pendingKeystoreUri,
                            onImport = { uri, alias, store, key, name ->
                                vm.importReleaseKeystore(uri, alias, store, key, name)
                                pendingKeystoreUri = null
                            },
                            onCancelImport = { pendingKeystoreUri = null },
                        )
                    }
                }
                BuildSection.OUTPUT -> {
                    if (progress.stages.isNotEmpty()) {
                        item {
                            ProgressCard(progress.stages, progress.fraction, progress.running)
                        }
                    }
                    item {
                        Text(
                            "Artifacts",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface,
                        )
                    }
                    if (artifacts.isEmpty()) {
                        item {
                            EmptyCard("No builds yet. Configure the app and tap Build.")
                        }
                    } else {
                        items(artifacts, key = { it.id }) { artifact ->
                            ArtifactCard(
                                artifact = artifact,
                                onInstall = { vm.installArtifact(artifact) },
                                onShare = { vm.shareArtifact(artifact) },
                                onDelete = { vm.deleteArtifact(artifact) },
                            )
                        }
                    }
                }
            }

            if (!toast.isNullOrEmpty()) {
                item {
                    val ok = toast!!.startsWith("apk ready") ||
                        toast!!.contains("keystore") ||
                        toast!!.startsWith("exported") ||
                        toast!!.contains("icon")
                    Text(
                        toast!!,
                        color = if (ok) cs.primary else cs.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LaunchedEffect(toast) {
                        kotlinx.coroutines.delay(2600)
                        vm.consumeToast()
                    }
                }
            }
        }

        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = cs.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (progress.running) {
                    val frac by animateFloatAsState(
                        targetValue = progress.fraction,
                        animationSpec = spring(),
                        label = "build-frac",
                    )
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(99.dp)),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (progress.running) {
                        OutlinedButton(
                            onClick = vm::cancelBuild,
                            modifier = Modifier.weight(1f),
                        ) { Text("Cancel") }
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1.4f),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = cs.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Building…")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { section = BuildSection.OUTPUT },
                            modifier = Modifier.weight(1f),
                        ) { Text("History") }
                        Button(
                            onClick = {
                                section = BuildSection.OUTPUT
                                vm.startBuild()
                            },
                            modifier = Modifier.weight(1.4f),
                        ) { Text("Build APK") }
                    }
                }
            }
        }
    }
}

private enum class BuildSection { APP, SIGN, OUTPUT }

@Composable
private fun SectionTabs(
    selected: BuildSection,
    onSelect: (BuildSection) -> Unit,
    hasArtifacts: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            BuildSection.APP to "App",
            BuildSection.SIGN to "Signing",
            BuildSection.OUTPUT to if (hasArtifacts) "Output" else "Output",
        ).forEach { (sec, label) ->
            val on = selected == sec
            Surface(
                onClick = { onSelect(sec) },
                shape = RoundedCornerShape(20.dp),
                color = if (on) cs.primary else cs.surfaceVariant.copy(alpha = 0.55f),
            ) {
                Text(
                    label,
                    color = if (on) cs.onPrimary else cs.onSurfaceVariant,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AppSection(
    config: BuildConfig,
    enabled: Boolean,
    onChange: (BuildConfig) -> Unit,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Identity")
        OutlinedTextField(
            value = config.appName,
            onValueChange = { onChange(config.copy(appName = it)) },
            label = { Text("App name") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors,
        )
        OutlinedTextField(
            value = config.packageName,
            onValueChange = { onChange(config.copy(packageName = it.trim())) },
            label = { Text("Package name") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = config.versionName,
                onValueChange = { onChange(config.copy(versionName = it)) },
                label = { Text("Version") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = config.versionCode.toString(),
                onValueChange = { s ->
                    s.filter { it.isDigit() }.toIntOrNull()?.let {
                        onChange(config.copy(versionCode = it.coerceAtLeast(1)))
                    }
                },
                label = { Text("Code") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.7f),
                shape = RoundedCornerShape(14.dp),
            )
        }
        OutlinedTextField(
            value = config.entryFile,
            onValueChange = { onChange(config.copy(entryFile = it.trim())) },
            label = { Text("Entry file") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )

        SectionTitle("Orientation")
        SegmentedThree(
            options = listOf(
                Orientation.AUTO to "Auto",
                Orientation.PORTRAIT to "Portrait",
                Orientation.LANDSCAPE to "Landscape",
            ),
            selected = config.orientation,
            enabled = enabled,
            onSelect = { onChange(config.copy(orientation = it)) },
        )

        SectionTitle("ABIs")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BuildConfig.DEFAULT_ABIS.forEach { abi ->
                val on = abi in config.abis
                FilterChip(
                    selected = on,
                    onClick = {
                        val next = if (on) config.abis - abi else config.abis + abi
                        if (next.isNotEmpty()) onChange(config.copy(abis = next))
                    },
                    enabled = enabled,
                    label = { Text(shortAbi(abi), fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
    }
}

@Composable
private fun IconSection(
    config: BuildConfig,
    preview: ByteArray?,
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onBg: (Long) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Icon")
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = cs.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val bmp = remember(preview) {
                    preview?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(config.iconBgColor))
                        .border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "icon preview",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(18.dp)),
                        )
                    } else {
                        Icon(Icons.Filled.Image, null, tint = cs.onSurfaceVariant)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (config.iconPath != null) "Custom image" else "Monogram",
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                    )
                    Text(
                        if (config.iconPath != null) "Adaptive icon from your image"
                        else "Generated from app name",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                            Text("Choose", fontSize = 13.sp)
                        }
                        if (config.iconPath != null) {
                            TextButton(onClick = onClear, enabled = enabled) {
                                Text("Reset", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        Text("Background", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ICON_BG_PRESETS.forEach { color ->
                val selected = config.iconBgColor == color
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) cs.onSurface else cs.outlineVariant.copy(alpha = 0.5f),
                            shape = CircleShape,
                        )
                        .clickable(enabled = enabled) { onBg(color) },
                )
            }
        }
    }
}

private val ICON_BG_PRESETS = listOf(
    0xFF185FA5L,
    0xFF0F6E56L,
    0xFF5B4B8A,
    0xFF1A1C1E,
    0xFFB45309,
    0xFF0E7490,
)

@Composable
private fun SigningSection(
    config: BuildConfig,
    keystores: List<KeystoreInfo>,
    enabled: Boolean,
    onSignMode: (SignMode) -> Unit,
    onSelectKeystore: (String?) -> Unit,
    onDeleteKeystore: (String) -> Unit,
    onExportKeystore: (String) -> Unit,
    onCreate: (alias: String, store: String, key: String, name: String) -> Unit,
    onImportClick: () -> Unit,
    pendingImportUri: android.net.Uri?,
    onImport: (android.net.Uri, String, String, String, String) -> Unit,
    onCancelImport: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var showCreate by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf("release") }
    var storePass by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("release") }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        SectionTitle("Mode")
        SegmentedTwo(
            left = "Debug" to (config.signMode == SignMode.DEBUG),
            right = "Release" to (config.signMode == SignMode.RELEASE),
            enabled = enabled,
            onLeft = { onSignMode(SignMode.DEBUG) },
            onRight = { onSignMode(SignMode.RELEASE) },
        )

        if (config.signMode == SignMode.DEBUG) {
            EmptyCard("Debug builds use the built-in LuaXIDE key. Fine for testing and install from this device.")
        } else {
            SectionTitle("Release keystore")
            if (keystores.isEmpty()) {
                EmptyCard("No keystore yet. Create one or import an existing .jks / .p12.")
            } else {
                keystores.forEach { ks ->
                    val selected = config.releaseKeystoreId == ks.id
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) cs.primary.copy(alpha = 0.10f) else cs.surfaceVariant.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) cs.primary.copy(alpha = 0.45f) else cs.outlineVariant.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onSelectKeystore(ks.id) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ks.displayName, fontWeight = FontWeight.Medium, color = cs.onSurface)
                                Text(
                                    "${ks.alias} · ${ks.sha256.take(17)}…",
                                    color = cs.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            if (selected) {
                                Icon(Icons.Filled.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onExportKeystore(ks.id) }, enabled = enabled) {
                                Icon(Icons.Filled.Share, contentDescription = "export", tint = cs.onSurfaceVariant)
                            }
                            IconButton(onClick = { onDeleteKeystore(ks.id) }, enabled = enabled) {
                                Icon(Icons.Filled.Delete, contentDescription = "delete", tint = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showCreate = !showCreate }, enabled = enabled) {
                    Text(if (showCreate) "Hide" else "Create")
                }
                OutlinedButton(onClick = onImportClick, enabled = enabled) {
                    Text("Import")
                }
            }

            AnimatedVisibility(
                visible = showCreate,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("Alias") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = storePass,
                        onValueChange = { storePass = it },
                        label = { Text("Store password") },
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = keyPass,
                        onValueChange = { keyPass = it },
                        label = { Text("Key password (optional)") },
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Button(
                        onClick = {
                            if (storePass.length < 6) return@Button
                            onCreate(alias, storePass, keyPass, displayName)
                            showCreate = false
                            storePass = ""
                            keyPass = ""
                        },
                        enabled = enabled && storePass.length >= 6 && alias.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create keystore") }
                }
            }

            if (pendingImportUri != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Import ${pendingImportUri.lastPathSegment ?: "keystore"}",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("Alias") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = storePass,
                        onValueChange = { storePass = it },
                        label = { Text("Store password") },
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = keyPass,
                        onValueChange = { keyPass = it },
                        label = { Text("Key password (optional)") },
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onImport(pendingImportUri, alias, storePass, keyPass, displayName)
                                storePass = ""
                                keyPass = ""
                            },
                            enabled = enabled && storePass.isNotEmpty() && alias.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Import") }
                        OutlinedButton(onClick = onCancelImport, enabled = enabled, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(stages: List<BuildStage>, fraction: Float, running: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (running) "Building" else "Last run",
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(fraction * 100).toInt()}%",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            stages.forEach { StageLine(it) }
        }
    }
}

@Composable
private fun StageLine(stage: BuildStage) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        StageDot(stage.status)
        Text(
            stage.name,
            color = when (stage.status) {
                StageStatus.FAILED -> cs.error
                StageStatus.RUNNING -> cs.primary
                StageStatus.DONE -> cs.onSurface
                StageStatus.PENDING -> cs.onSurfaceVariant
            },
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (stage.detail.isNotBlank()) {
            Text(stage.detail, color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StageDot(status: StageStatus) {
    val cs = MaterialTheme.colorScheme
    when (status) {
        StageStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        StageStatus.DONE -> Icon(Icons.Filled.Check, null, tint = cs.primary, modifier = Modifier.size(14.dp))
        StageStatus.FAILED -> Icon(Icons.Filled.Close, null, tint = cs.error, modifier = Modifier.size(14.dp))
        StageStatus.PENDING -> Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(cs.outlineVariant),
        )
    }
}

@Composable
private fun ArtifactCard(
    artifact: BuildArtifact,
    onInstall: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceVariant.copy(alpha = 0.32f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                artifact.file.name,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${kindLabel(artifact.kind)} · ${humanSize(artifact.sizeBytes)} · ${fmtTime(artifact.createdAt)}",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val canInstall = artifact.kind == ArtifactKind.APK_DEBUG || artifact.kind == ArtifactKind.APK_RELEASE
                if (canInstall) {
                    TextButton(onClick = onInstall) {
                        Icon(Icons.Filled.GetApp, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Install")
                    }
                }
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "delete", tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun EmptyCard(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceVariant.copy(alpha = 0.28f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            color = cs.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun <T> SegmentedThree(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
        ) {
            options.forEach { (value, label) ->
                val on = value == selected
                Surface(
                    onClick = { if (enabled) onSelect(value) },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    color = if (on) cs.surface else Color.Transparent,
                    shadowElevation = if (on) 1.dp else 0.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        label,
                        color = if (on) cs.onSurface else cs.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedTwo(
    left: Pair<String, Boolean>,
    right: Pair<String, Boolean>,
    enabled: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    SegmentedThree(
        options = listOf(0 to left.first, 1 to right.first),
        selected = if (left.second) 0 else 1,
        enabled = enabled,
        onSelect = { if (it == 0) onLeft() else onRight() },
    )
}

private fun shortAbi(abi: String) = when (abi) {
    "arm64-v8a" -> "arm64"
    "armeabi-v7a" -> "armv7"
    "x86_64" -> "x86_64"
    else -> abi
}

private fun kindLabel(kind: ArtifactKind) = when (kind) {
    ArtifactKind.LUAXPKG -> "bundle"
    ArtifactKind.APK_UNSIGNED -> "apk · unsigned"
    ArtifactKind.APK_DEBUG -> "apk · debug"
    ArtifactKind.APK_RELEASE -> "apk · release"
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val tsFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
private fun fmtTime(t: Long) = tsFmt.format(Date(t))
