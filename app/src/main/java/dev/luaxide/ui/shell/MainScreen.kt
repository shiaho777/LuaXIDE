package dev.luaxide.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import dev.luaxide.ui.runtime.Motion
import dev.luaxide.ui.runtime.pressableClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.luaxide.ui.S
import dev.luaxide.ui.bifold.BifoldMode
import dev.luaxide.ui.bifold.BifoldScaffold
import dev.luaxide.ui.bifold.rememberBifoldState
import dev.luaxide.ui.editor.CodeFace
import dev.luaxide.ui.editor.DebugBar
import dev.luaxide.engine.DebugState
import dev.luaxide.ui.logs.LogSheet
import dev.luaxide.ui.build.BuildSheet
import dev.luaxide.ui.docs.ApiDocsSheet
import dev.luaxide.ui.checklist.DeviceChecklistSheet
import dev.luaxide.ui.assets.AssetPreview
import dev.luaxide.assets.FileAssetResolver
import dev.luaxide.assets.LocalAssetResolver
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Verified
import dev.luaxide.ui.build.BuildViewModel
import dev.luaxide.project.FileNode
import dev.luaxide.project.Project
import dev.luaxide.ui.preview.PreviewFace
import dev.luaxide.ui.project.ProjectDrawer
import dev.luaxide.ui.project.WorkspaceActions
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: EditorViewModel = viewModel()) {
    val bifold = rememberBifoldState()
    val code by vm.code.collectAsState()
    val result by vm.result.collectAsState()
    val waitingStdin by vm.waitingStdin.collectAsState()
    val project by vm.project.collectAsState()
    val projects by vm.projects.collectAsState()
    val tree by vm.tree.collectAsState()
    val openPath by vm.openPath.collectAsState()
    val debugEnabled by vm.debugEnabled.collectAsState()
    val debugState by vm.debugState.collectAsState()
    val breakpoints by vm.breakpoints.collectAsState()
    val watches by vm.watches.collectAsState()
    val showDebugPanel by vm.showDebugPanel.collectAsState()
    val breakOnError by vm.breakOnError.collectAsState()
    val instantEval by vm.instantEval.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val checklist by vm.checklist.collectAsState()
    val assetPreviewPath by vm.assetPreviewPath.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(toast) {
        val msg = toast
        if (!msg.isNullOrEmpty()) {
            snackbarHostState.showSnackbar(msg)
            vm.consumeToast()
        }
    }

    var showNewFile by remember { mutableStateOf(false) }
    var showNewProject by remember { mutableStateOf(false) }
    var showRenameProject by remember { mutableStateOf(false) }
    var showBuild by remember { mutableStateOf(false) }
    var showApiDocs by remember { mutableStateOf(false) }
    var showChecklist by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showOnboarding by remember {
        mutableStateOf(!OnboardingPrefs.isDone(ctx))
    }
    var newFileInDir by remember { mutableStateOf<String?>(null) }
    var newFolderInDir by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<FileNode?>(null) }
    var deleteTarget by remember { mutableStateOf<FileNode?>(null) }
    var deleteProjectTarget by remember { mutableStateOf<Project?>(null) }
    var condEditLine by remember { mutableStateOf<Int?>(null) }


    if (showOnboarding) {
        OnboardingDialog(
            onNewProject = { showNewProject = true },
            onOpenExampleAndRun = {
                bifold.mode = BifoldMode.PREVIEW
                vm.openExamplesStdinAndRun()
            },
            onOpenBuild = { showBuild = true },
            onDismiss = { showOnboarding = false },
        )
    }


    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importAsset(uri)
    }


    if (showApiDocs) {
        ApiDocsSheet(
            onInsert = { snippet ->
                vm.insertAtCursor(snippet)
                showApiDocs = false
            },
            onDismiss = { showApiDocs = false },
        )
    }
    if (showChecklist) {
        DeviceChecklistSheet(
            report = checklist,
            onRunAll = { vm.runDeviceChecklist() },
            onOpenBuild = {
                showChecklist = false
                showBuild = true
            },
            onDismiss = { showChecklist = false },
        )
    }

    if (showBuild && project != null) {
        val buildVm: BuildViewModel = viewModel()
        androidx.compose.runtime.LaunchedEffect(project?.id) {
            project?.let { buildVm.bind(it.id, it.name, openPath ?: it.entryFile, vm.logs) }
        }
        BuildSheet(vm = buildVm, onDismiss = { showBuild = false })
    }

    condEditLine?.let { line ->
        val existing = breakpoints[line]
        var cond by remember(line) { mutableStateOf(existing?.condition.orEmpty()) }
        var logMsg by remember(line) { mutableStateOf(existing?.logMessage.orEmpty()) }
        var logOnly by remember(line) { mutableStateOf(existing?.logOnly == true) }
        AlertDialog(
            onDismissRequest = { condEditLine = null },
            title = { Text("${S.BREAKPOINT} L$line") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cond,
                        onValueChange = { cond = it },
                        singleLine = true,
                        placeholder = { Text("例如 i == 3") },
                        label = { Text(S.CONDITION) },
                    )
                    OutlinedTextField(
                        value = logMsg,
                        onValueChange = { logMsg = it },
                        singleLine = true,
                        placeholder = { Text("例如 i={i} sum={sum}") },
                        label = { Text(S.LOG_MESSAGE) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = logOnly,
                            onCheckedChange = { logOnly = it },
                            enabled = logMsg.isNotBlank(),
                        )
                        Text(S.LOGPOINT)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (line !in breakpoints) vm.toggleBreakpoint(line)
                    vm.setBreakpointCondition(line, cond.trim())
                    vm.setBreakpointLog(line, logMsg.trim(), logOnly)
                    condEditLine = null
                }) { Text(S.SET) }
            },
            dismissButton = {
                TextButton(onClick = { condEditLine = null }) { Text(S.CANCEL) }
            },
        )
    }

    if (showNewFile) {
        NameDialog(
            title = S.NEW_FILE,
            hint = S.NAME_LUA,
            onConfirm = { vm.newFile(it); showNewFile = false },
            onDismiss = { showNewFile = false },
        )
    }
    if (showNewProject) {
        NewProjectDialog(
            onCreateUi = { name, lang -> vm.createProject(name, lang.id); showNewProject = false },
            onCreateProgram = { name, lang -> vm.createProgramProject(name, lang.id); showNewProject = false },
            onDismiss = { showNewProject = false },
        )
    }
    newFileInDir?.let { dir ->
        NameDialog(
            title = "${S.NEW_FILE} · ${dir.ifEmpty { S.ROOT_DIR }}",
            hint = S.NAME_LUA,
            onConfirm = { vm.newFileIn(dir, it); newFileInDir = null },
            onDismiss = { newFileInDir = null },
        )
    }
    newFolderInDir?.let { dir ->
        NameDialog(
            title = S.NEW_FOLDER,
            hint = S.FOLDER_NAME,
            onConfirm = { vm.newFolder(dir, it); newFolderInDir = null },
            onDismiss = { newFolderInDir = null },
        )
    }
    renameTarget?.let { node ->
        NameDialog(
            title = S.RENAME,
            hint = node.name,
            initial = node.name,
            confirmLabel = S.RENAME,
            onConfirm = { vm.renameNode(node.relPath, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { node ->
        ConfirmDialog(
            title = S.deleteTitle(node.name),
            message = if (node.isDirectory) S.DELETE_CONFIRM_DIR else S.DELETE_CONFIRM_FILE,
            confirmLabel = S.DELETE,
            onConfirm = { vm.deleteNode(node.relPath); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
    if (showRenameProject) {
        NameDialog(
            title = S.RENAME_PROJECT,
            hint = S.PROJECT_NAME,
            initial = project?.name.orEmpty(),
            confirmLabel = S.RENAME,
            onConfirm = { vm.renameProject(it); showRenameProject = false },
            onDismiss = { showRenameProject = false },
        )
    }
    deleteProjectTarget?.let { target ->
        ConfirmDialog(
            title = S.deleteTitle(target.name),
            message = S.DELETE_PROJECT_MSG,
            confirmLabel = S.DELETE,
            onConfirm = {
                vm.deleteProject(target)
                deleteProjectTarget = null
            },
            onDismiss = { deleteProjectTarget = null },
        )
    }
    val treeActions = remember(vm) {
        dev.luaxide.ui.project.TreeActions(
            onOpenFile = { vm.openFile(it); scope.launch { drawerState.close() } },
            onRename = { renameTarget = it },
            onDelete = { deleteTarget = it },
            onNewFileIn = { newFileInDir = it },
            onNewFolderIn = { newFolderInDir = it },
            onSetEntry = { vm.setEntryFile(it) },
        )
    }
    val workspaceActions = remember(vm) {
        WorkspaceActions(
            onSwitchProject = { vm.openProject(it); scope.launch { drawerState.close() } },
            onNewProject = { showNewProject = true },
            onRenameProject = { showRenameProject = true },
            onDuplicateProject = { vm.duplicateProject(it) },
            onDeleteProject = { deleteProjectTarget = it },
            onRefresh = { vm.refreshTree() },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.88f)) {
                ProjectDrawer(
                    project = project,
                    tree = tree,
                    projects = projects,
                    openPath = openPath,
                    actions = treeActions,
                    workspace = workspaceActions,
                    onNewFile = { showNewFile = true },
                    onNewFolder = { newFolderInDir = "" },
                    onImportAsset = {
                        importLauncher.launch(arrayOf("image/*", "font/*", "application/octet-stream", "*/*"))
                    },
                    onEnsureAssets = { vm.ensureAssetFolders() },
                )
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            ProjectChip(
                                name = project?.name ?: "LuaXIDE",
                                onClick = { scope.launch { drawerState.open() } },
                            )
                            Text(
                                text = openPath ?: "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.setDebugEnabled(!debugEnabled) }) {
                            Icon(
                                Icons.Filled.BugReport,
                                contentDescription = S.DEBUG,
                                tint = if (debugEnabled) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RunStopButton(
                            busy = busy,
                            onRun = vm::run,
                            onStop = vm::cancelProgram,
                        )
                        TopBarOverflow(
                            onApiDocs = { showApiDocs = true },
                            onChecklist = { showChecklist = true },
                            onBuild = { showBuild = true },
                        )
                    },
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    AnimatedVisibility(
                        visible = debugEnabled || debugState !is DebugState.Idle,
                        enter = Motion.listEnter(),
                        exit = Motion.listExit(),
                    ) {
                        DebugBar(
                            enabled = debugEnabled,
                            state = debugState,
                            breakpoints = breakpoints,
                            watches = watches,
                            panelOpen = showDebugPanel,
                            breakOnError = breakOnError,
                            instantEval = instantEval,
                            onToggleEnabled = vm::setDebugEnabled,
                            onToggleBreakOnError = vm::setBreakOnError,
                            onContinue = vm::debugContinue,
                            onStep = vm::debugStep,
                            onStepOut = vm::debugStepOut,
                            onStop = vm::debugStop,
                            onClearBreakpoints = vm::clearBreakpoints,
                            onJumpToLine = { line ->
                                bifold.mode = BifoldMode.CODE
                                vm.jumpToLine(line)
                            },
                            onEditBreakpoint = { line -> condEditLine = line },
                            onRemoveBreakpoint = { line ->
                                if (line in breakpoints) vm.toggleBreakpoint(line)
                            },
                            onAddWatch = vm::addWatch,
                            onRemoveWatch = vm::removeWatch,
                            onEvalNow = vm::evalNow,
                            onClearInstantEval = vm::clearInstantEval,
                            onTogglePanel = vm::toggleDebugPanel,
                        )
                    }
                    LogSheet(
                        store = vm.logs,
                        waitingStdin = waitingStdin,
                        onConsoleSubmit = vm::submitConsoleLine,
                        onJumpToLine = { line ->
                            bifold.mode = BifoldMode.CODE
                            vm.jumpToLine(line)
                        },
                        onExport = { format -> vm.exportLogs(format) },
                    )
                    BifoldRail(bifold)
                }
            },
        ) { padding ->
            val jumpRequest by vm.jumpRequest.collectAsState()
            BifoldScaffold(
                state = bifold,
                modifier = Modifier.padding(padding),
                codeFace = {
                    val pausedLine = (debugState as? DebugState.Paused)?.pause?.line
                    val previewPath = assetPreviewPath
                    if (previewPath != null) {
                        AssetPreview(
                            path = previewPath,
                            absoluteFile = vm.assetAbsoluteFile(previewPath),
                        )
                    } else {
                        CodeFace(
                            text = code,
                            onTextChange = vm::onCodeChange,
                            jumpRequest = jumpRequest,
                            breakpoints = breakpoints,
                            pausedLine = pausedLine,
                            errorLine = result?.takeIf { !it.ok }?.errorLine,
                            onToggleBreakpoint = vm::toggleBreakpoint,
                            onEditBreakpoint = { line -> condEditLine = line },
                            onToast = { msg -> snackbarHostState.let { host ->
                                scope.launch { host.showSnackbar(msg) }
                            } },
                        )
                    }
                },
                previewFace = {
                    val resolver = remember(project?.id) {
                        val root = vm.projectSrcRoot()
                        if (root != null) FileAssetResolver(root)
                        else dev.luaxide.assets.EmptyAssetResolver
                    }
                    CompositionLocalProvider(LocalAssetResolver provides resolver) {
                        PreviewFace(
                            result = result,
                            onEvent = vm::onEvent,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ProjectChip(name: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = name, color = cs.onSurface, style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "projects", tint = cs.onSurfaceVariant)
    }
}

/**
 * The shell's one primary action. Runs the open file (or entry fallback) and
 * morphs into Stop while the engine is busy, so the top bar always offers the
 * inverse of whatever the program is doing — never two contradictory buttons.
 */
@Composable
private fun RunStopButton(
    busy: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (busy) cs.errorContainer else cs.primary,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "runContainer",
    )
    val content by animateColorAsState(
        targetValue = if (busy) cs.onErrorContainer else cs.onPrimary,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "runContent",
    )
    Button(
        onClick = { if (busy) onStop() else onRun() },
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier
            .padding(start = 2.dp)
            .height(38.dp),
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = busy,
            transitionSpec = { Motion.textSwap() },
            label = "runStop",
        ) { running ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (running) S.STOP else S.RUN,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Secondary actions, tucked behind ⋮ so the primary action stays dominant. */
@Composable
private fun TopBarOverflow(
    onApiDocs: () -> Unit,
    onChecklist: () -> Unit,
    onBuild: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = S.MORE_OPTIONS)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(S.API_DOCS) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                onClick = { open = false; onApiDocs() },
            )
            DropdownMenuItem(
                text = { Text(S.SELF_CHECK) },
                leadingIcon = { Icon(Icons.Filled.Verified, contentDescription = null) },
                onClick = { open = false; onChecklist() },
            )
            DropdownMenuItem(
                text = { Text(S.BUILD_APK) },
                leadingIcon = { Icon(Icons.Filled.Build, contentDescription = null) },
                onClick = { open = false; onBuild() },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewProjectDialog(
    onCreateUi: (String, dev.luaxide.lang.Language) -> Unit,
    onCreateProgram: (String, dev.luaxide.lang.Language) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("ui") }
    var language by remember { mutableStateOf(dev.luaxide.lang.Language.LUA) }
    var langHint by remember { mutableStateOf(false) }
    val valid = text.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.NEW_PROJECT) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(S.PROJECT_NAME) },
                )
                Text(
                    text = S.LANGUAGE,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    dev.luaxide.lang.Language.entries.forEach { lang ->
                        val selected = language == lang
                        LanguageChip(
                            language = lang,
                            selected = selected,
                            onClick = {
                                if (lang.supported) {
                                    language = lang
                                } else {
                                    langHint = true
                                }
                            },
                        )
                    }
                }
                if (langHint) {
                    Text(
                        text = S.LANG_COMING_HINT,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ui" to S.UI_APP, "program" to S.PROGRAM).forEach { (id, label) ->
                        val selected = kind == id
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .pressableClickable { kind = id },
                        ) {
                            Text(
                                text = label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Text(
                    text = if (kind == "program") {
                        S.PROGRAM_HINT
                    } else {
                        S.UI_HINT
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!valid) return@TextButton
                    val name = text.trim()
                    if (kind == "program") onCreateProgram(name, language) else onCreateUi(name, language)
                },
                enabled = valid,
            ) { Text(S.CREATE) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.CANCEL) }
        },
    )
}

/** Language pill in the new-project dialog; unsupported languages show greyed. */
@Composable
private fun LanguageChip(
    language: dev.luaxide.lang.Language,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val enabledLook = language.supported
    val label = if (enabledLook) language.displayName else "${language.displayName} · ${S.COMING_SOON}"
    Surface(
        color = when {
            selected -> cs.primary
            enabledLook -> cs.surfaceVariant
            else -> cs.surfaceVariant.copy(alpha = 0.45f)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.pressableClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Color(language.accent)
                            .copy(alpha = if (enabledLook) 1f else 0.35f),
                    ),
            )
            Text(
                text = label,
                color = when {
                    selected -> cs.onPrimary
                    enabledLook -> cs.onSurfaceVariant
                    else -> cs.onSurfaceVariant.copy(alpha = 0.55f)
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    hint: String,
    initial: String = "",
    confirmLabel: String = S.CREATE,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val valid = text.trim().isNotEmpty() && !text.contains('/') && text.trim() != "." && text.trim() != ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(hint) },
                    isError = text.isNotBlank() && !valid,
                )
                if (text.isNotBlank() && !valid) {
                    Text(
                        S.INVALID_NAME,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(text.trim()) },
                enabled = valid,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.CANCEL) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.CANCEL) }
        },
    )
}

@Composable
private fun BifoldRail(state: dev.luaxide.ui.bifold.BifoldState) {
    val cs = MaterialTheme.colorScheme
    val options = listOf(
        S.CODE to BifoldMode.CODE,
        S.BOTH to BifoldMode.BOTH,
        S.PREVIEW to BifoldMode.PREVIEW,
    )
    val selectedIndex = options.indexOfFirst { it.second == state.mode }.coerceAtLeast(0)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val slotWidth = if (rowWidthPx > 0) rowWidthPx / options.size.toFloat() else 0f
    val indicator = remember { androidx.compose.animation.core.Animatable(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var dragStart by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

    androidx.compose.runtime.LaunchedEffect(selectedIndex, dragging) {
        if (!dragging) {
            indicator.animateTo(
                selectedIndex.toFloat(),
                animationSpec = Motion.indicator,
            )
        }
    }

    fun settleTo(index: Int) {
        val clamped = index.coerceIn(0, options.lastIndex)
        state.mode = options[clamped].second
        scope.launch {
            indicator.animateTo(
                clamped.toFloat(),
                animationSpec = Motion.indicator,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = cs.surfaceVariant,
            modifier = Modifier.pointerInput(slotWidth, options.size) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragStart = indicator.value
                    },
                    onDragEnd = {
                        dragging = false
                        val nearest = indicator.value.roundToInt().coerceIn(0, options.lastIndex)
                        val velocityBias = (indicator.value - dragStart)
                        val target = when {
                            kotlin.math.abs(velocityBias) > 0.28f -> {
                                if (velocityBias > 0f) {
                                    (dragStart.toInt() + 1).coerceIn(0, options.lastIndex)
                                } else {
                                    (dragStart.toInt() - 1).coerceIn(0, options.lastIndex)
                                }
                            }
                            else -> nearest
                        }
                        settleTo(target)
                    },
                    onDragCancel = {
                        dragging = false
                        settleTo(selectedIndex)
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (slotWidth <= 0f) return@detectHorizontalDragGestures
                        val next = (indicator.value + dragAmount / slotWidth)
                            .coerceIn(0f, options.lastIndex.toFloat())
                        scope.launch { indicator.snapTo(next) }
                    },
                )
            },
        ) {
            Box(modifier = Modifier.padding(4.dp)) {
                if (rowWidthPx > 0 && rowHeightPx > 0 && slotWidth > 0f) {
                    Surface(
                        color = cs.primary,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(with(density) { slotWidth.toDp() })
                            .height(with(density) { rowHeightPx.toDp() })
                            .graphicsLayer {
                                translationX = slotWidth * indicator.value
                            },
                    ) {}
                }
                Row(
                    modifier = Modifier.onSizeChanged {
                        rowWidthPx = it.width
                        rowHeightPx = it.height
                    },
                ) {
                    options.forEachIndexed { index, (label, mode) ->
                        val highlight = kotlin.math.abs(indicator.value - index) < 0.5f
                        Box(
                            modifier = Modifier
                                .clickable { settleTo(index) }
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = if (highlight) cs.onPrimary else cs.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

