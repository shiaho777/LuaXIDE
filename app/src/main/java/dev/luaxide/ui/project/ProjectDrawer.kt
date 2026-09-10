package dev.luaxide.ui.project

import androidx.compose.animation.AnimatedVisibility
import dev.luaxide.ui.runtime.Motion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.project.FileKind
import dev.luaxide.project.FileNode
import dev.luaxide.project.Project
import dev.luaxide.ui.S

data class TreeActions(
    val onOpenFile: (String) -> Unit,
    val onRename: (FileNode) -> Unit,
    val onDelete: (FileNode) -> Unit,
    val onNewFileIn: (dirRelPath: String) -> Unit,
    val onNewFolderIn: (dirRelPath: String) -> Unit,
    val onSetEntry: (String) -> Unit = {},
)

data class WorkspaceActions(
    val onSwitchProject: (Project) -> Unit,
    val onNewProject: () -> Unit,
    val onRenameProject: () -> Unit,
    val onDuplicateProject: (Project) -> Unit,
    val onDeleteProject: (Project) -> Unit,
    val onRefresh: () -> Unit,
)

@Composable
fun ProjectDrawer(
    project: Project?,
    tree: FileNode?,
    projects: List<Project>,
    openPath: String?,
    actions: TreeActions,
    workspace: WorkspaceActions,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onImportAsset: () -> Unit = {},
    onEnsureAssets: () -> Unit = {},
    rootModifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var workspaceOpen by rememberSaveable { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val flat = remember(tree, query) {
        val q = query.trim()
        flattenTree(tree?.children.orEmpty(), q)
    }

    val fileCount = remember(tree) { countFiles(tree) }

    Column(
        modifier = rootModifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(cs.surface)
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        WorkspaceHeader(
            project = project,
            projects = projects,
            open = workspaceOpen,
            onToggle = { workspaceOpen = !workspaceOpen },
            fileCount = fileCount,
            openPath = openPath,
            onRefresh = workspace.onRefresh,
            onNewProject = workspace.onNewProject,
            onRenameProject = workspace.onRenameProject,
            onDuplicateProject = {
                project?.let(workspace.onDuplicateProject)
            },
            onDeleteProject = {
                project?.let(workspace.onDeleteProject)
            },
        )

        AnimatedVisibility(
            visible = workspaceOpen,
            enter = Motion.listEnter(),
            exit = Motion.listExit(),
        ) {
            WorkspaceList(
                projects = projects,
                currentId = project?.id,
                onSwitch = {
                    workspace.onSwitchProject(it)
                    workspaceOpen = false
                },
                onDuplicate = workspace.onDuplicateProject,
                onDelete = workspace.onDeleteProject,
            )
        }

        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.55f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = S.FILES,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewFile, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Add, contentDescription = S.NEW_FILE, tint = cs.primary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onNewFolder, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = S.NEW_FOLDER, tint = cs.primary, modifier = Modifier.size(18.dp))
            }
            FilesOverflowMenu(
                onRefresh = workspace.onRefresh,
                onImportAsset = onImportAsset,
                onEnsureAssets = onEnsureAssets,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(S.FILTER_FILES, fontSize = 13.sp) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = S.CLEAR, modifier = Modifier.size(16.dp))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.primary.copy(alpha = 0.55f),
                unfocusedBorderColor = cs.outlineVariant,
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (query.isNotBlank()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                if (flat.isEmpty()) {
                    item {
                        EmptyHint("${S.NO_MATCHES} \"$query\"")
                    }
                } else {
                    items(flat, key = { it.node.relPath }) { row ->
                        SearchRow(
                            row = row,
                            openPath = openPath,
                            entryFile = project?.entryFile,
                            actions = actions,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                val roots = tree?.children.orEmpty()
                if (roots.isEmpty()) {
                    item { EmptyHint(S.NO_FILES) }
                } else {
                    treeItems(
                        nodes = roots,
                        depth = 0,
                        openPath = openPath,
                        entryFile = project?.entryFile,
                        expanded = expanded,
                        actions = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    project: Project?,
    projects: List<Project>,
    open: Boolean,
    onToggle: () -> Unit,
    fileCount: Int,
    openPath: String?,
    onRefresh: () -> Unit,
    onNewProject: () -> Unit,
    onRenameProject: () -> Unit,
    onDuplicateProject: () -> Unit,
    onDeleteProject: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.45f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.FolderOpen, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = project?.name ?: S.NO_PROJECT,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    project?.let { LanguageBadge(it.language) }
                }
                Text(
                    text = buildString {
                        append(projects.size)
                        append(S.PROJECT_N)
                        append(" · ")
                        append(fileCount)
                        append(S.FILE_N)
                        if (!openPath.isNullOrBlank()) {
                            append(" · ")
                            append(openPath.substringAfterLast('/'))
                        }
                    },
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "收起工作区" else "展开工作区",
                tint = cs.onSurfaceVariant,
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "项目菜单", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(S.NEW_PROJECT) },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { menu = false; onNewProject() },
                    )
                    DropdownMenuItem(
                        text = { Text(S.RENAME_PROJECT) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; onRenameProject() },
                        enabled = project != null,
                    )
                    DropdownMenuItem(
                        text = { Text(S.DUPLICATE_PROJECT) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { menu = false; onDuplicateProject() },
                        enabled = project != null,
                    )
                    DropdownMenuItem(
                        text = { Text(S.REFRESH) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { menu = false; onRefresh() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(S.DELETE_PROJECT, color = cs.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = cs.error) },
                        onClick = { menu = false; onDeleteProject() },
                        enabled = project != null && projects.size > 1,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun WorkspaceList(
    projects: List<Project>,
    currentId: String?,
    onSwitch: (Project) -> Unit,
    onDuplicate: (Project) -> Unit,
    onDelete: (Project) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.28f))
            .padding(vertical = 4.dp),
    ) {
        if (projects.isEmpty()) {
            Text(
                S.NO_PROJECTS,
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            projects.forEach { p ->
                WorkspaceRow(
                    project = p,
                    active = p.id == currentId,
                    canDelete = projects.size > 1,
                    onSwitch = { onSwitch(p) },
                    onDuplicate = { onDuplicate(p) },
                    onDelete = { onDelete(p) },
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceRow(
    project: Project,
    active: Boolean,
    canDelete: Boolean,
    onSwitch: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember(project.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) cs.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onSwitch, onLongClick = { menu = true })
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) cs.primary else cs.outlineVariant),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.name,
                    color = if (active) cs.primary else cs.onSurface,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(6.dp))
                LanguageBadge(project.language)
            }
            Text(
                project.entryFile,
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) {
            Icon(Icons.Filled.Check, null, tint = cs.primary, modifier = Modifier.size(16.dp))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.MoreVert, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(S.OPEN) }, onClick = { menu = false; onSwitch() })
                DropdownMenuItem(text = { Text(S.DUPLICATE) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(
                    text = { Text(S.DELETE, color = cs.error) },
                    onClick = { menu = false; onDelete() },
                    enabled = canDelete,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.treeItems(
    nodes: List<FileNode>,
    depth: Int,
    openPath: String?,
    entryFile: String?,
    expanded: MutableMap<String, Boolean>,
    actions: TreeActions,
) {
    nodes.forEach { node ->
        item(key = node.relPath) {
            TreeRow(
                node = node,
                depth = depth,
                openPath = openPath,
                entryFile = entryFile,
                expanded = expanded,
                actions = actions,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    node: FileNode,
    depth: Int,
    openPath: String?,
    entryFile: String?,
    expanded: MutableMap<String, Boolean>,
    actions: TreeActions,
) {
    val cs = MaterialTheme.colorScheme
    val isOpen = expanded[node.relPath] ?: (depth < 1)
    var menuOpen by remember(node.relPath) { mutableStateOf(false) }
    val selected = node.relPath == openPath
    val isEntry = !node.isDirectory && node.relPath == entryFile

    Column {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (node.isDirectory) {
                                expanded[node.relPath] = !isOpen
                            } else {
                                actions.onOpenFile(node.relPath)
                            }
                        },
                        onLongClick = { menuOpen = true },
                    )
                    .background(if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(start = (12 + depth * 14).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (node.isDirectory) {
                    val rot by animateFloatAsState(
                        targetValue = if (isOpen) 90f else 0f,
                        animationSpec = spring(),
                        label = "arrow",
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(rot),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                } else {
                    Spacer(modifier = Modifier.width(20.dp))
                }
                FileIcon(node.kind, openFolder = node.isDirectory && isOpen)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = node.name,
                    color = when {
                        selected -> cs.primary
                        isEntry -> cs.tertiary
                        else -> cs.onSurface
                    },
                    fontWeight = if (selected || isEntry) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isEntry) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "入口",
                        tint = cs.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "选项", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }

            NodeMenu(
                node = node,
                expanded = menuOpen,
                isEntry = isEntry,
                onDismiss = { menuOpen = false },
                actions = actions,
            )
        }

        if (node.isDirectory) {
            AnimatedVisibility(
                visible = isOpen,
                enter = Motion.listEnter(),
                exit = Motion.listExit(),
            ) {
                Column {
                    node.children.forEach { child ->
                        TreeRow(
                            node = child,
                            depth = depth + 1,
                            openPath = openPath,
                            entryFile = entryFile,
                            expanded = expanded,
                            actions = actions,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchRow(
    row: FlatRow,
    openPath: String?,
    entryFile: String?,
    actions: TreeActions,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val node = row.node
    val selected = node.relPath == openPath
    val isEntry = node.relPath == entryFile
    var menuOpen by remember(node.relPath) { mutableStateOf(false) }

    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (node.isDirectory) actions.onNewFileIn(node.relPath)
                        else actions.onOpenFile(node.relPath)
                    },
                    onLongClick = { menuOpen = true },
                )
                .background(if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileIcon(node.kind, openFolder = false)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.name,
                    color = if (selected) cs.primary else cs.onSurface,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.pathHint.isNotEmpty()) {
                    Text(
                        row.pathHint,
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isEntry) {
                Icon(Icons.Filled.Star, null, tint = cs.tertiary, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.MoreVert, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        NodeMenu(node = node, expanded = menuOpen, isEntry = isEntry, onDismiss = { menuOpen = false }, actions = actions)
    }
}

@Composable
private fun NodeMenu(
    node: FileNode,
    expanded: Boolean,
    isEntry: Boolean,
    onDismiss: () -> Unit,
    actions: TreeActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (node.isDirectory) {
            DropdownMenuItem(
                text = { Text(S.NEW_FILE) },
                leadingIcon = { Icon(Icons.Filled.Add, null) },
                onClick = { onDismiss(); actions.onNewFileIn(node.relPath) },
            )
            DropdownMenuItem(
                text = { Text(S.NEW_FOLDER) },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                onClick = { onDismiss(); actions.onNewFolderIn(node.relPath) },
            )
        } else {
            DropdownMenuItem(
                text = { Text(S.OPEN) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                onClick = { onDismiss(); actions.onOpenFile(node.relPath) },
            )
            if (node.kind == FileKind.CODE) {
                DropdownMenuItem(
                    text = { Text(if (isEntry) S.ENTRY_FILE else S.SET_ENTRY) },
                    leadingIcon = {
                        Icon(
                            if (isEntry) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            null,
                        )
                    },
                    onClick = { onDismiss(); actions.onSetEntry(node.relPath) },
                    enabled = !isEntry,
                )
            }
        }
        DropdownMenuItem(
            text = { Text(S.RENAME) },
            leadingIcon = { Icon(Icons.Filled.Edit, null) },
            onClick = { onDismiss(); actions.onRename(node) },
        )
        DropdownMenuItem(
            text = { Text(S.DELETE) },
            leadingIcon = { Icon(Icons.Filled.Delete, null) },
            onClick = { onDismiss(); actions.onDelete(node) },
        )
    }
}

@Composable
private fun FileIcon(kind: FileKind, openFolder: Boolean) {
    val cs = MaterialTheme.colorScheme
    val icon = when (kind) {
        FileKind.FOLDER -> if (openFolder) Icons.Filled.FolderOpen else Icons.Filled.Folder
        FileKind.CODE -> Icons.AutoMirrored.Filled.InsertDriveFile
        FileKind.IMAGE -> Icons.Filled.Image
        FileKind.FONT -> Icons.Filled.TextFields
        FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val tint = when (kind) {
        FileKind.FOLDER -> cs.tertiary
        FileKind.CODE -> cs.primary
        FileKind.IMAGE -> cs.secondary
        FileKind.FONT -> cs.tertiary
        FileKind.OTHER -> cs.onSurfaceVariant
    }
    Icon(icon, contentDescription = kind.name, tint = tint, modifier = Modifier.size(18.dp))
}

@Composable
private fun FilesOverflowMenu(
    onRefresh: () -> Unit,
    onImportAsset: () -> Unit,
    onEnsureAssets: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = S.MORE_OPTIONS, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(S.REFRESH) },
                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                onClick = { open = false; onRefresh() },
            )
            DropdownMenuItem(
                text = { Text(S.IMPORT_ASSET) },
                leadingIcon = { Icon(Icons.Filled.Image, null) },
                onClick = { open = false; onImportAsset() },
            )
            DropdownMenuItem(
                text = { Text(S.ASSET_FOLDERS) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                onClick = { open = false; onEnsureAssets() },
            )
        }
    }
}

/** Small language tag shown beside project names — the drawer-level hint of the multi-language roadmap. */
@Composable
private fun LanguageBadge(languageId: String) {
    val lang = dev.luaxide.lang.Language.byId(languageId)
    Surface(
        color = Color(lang.accent).copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = lang.displayName,
            color = Color(lang.accent),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        color = cs.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

private data class FlatRow(val node: FileNode, val pathHint: String)

private fun flattenTree(nodes: List<FileNode>, query: String): List<FlatRow> {
    if (query.isEmpty()) return emptyList()
    val q = query.lowercase()
    val out = mutableListOf<FlatRow>()
    fun walk(list: List<FileNode>) {
        list.forEach { n ->
            if (n.name.lowercase().contains(q) || n.relPath.lowercase().contains(q)) {
                val hint = n.relPath.substringBeforeLast('/', missingDelimiterValue = "")
                out += FlatRow(n, hint)
            }
            if (n.isDirectory) walk(n.children)
        }
    }
    walk(nodes)
    return out.sortedWith(compareBy({ it.node.isDirectory.not() }, { it.node.relPath.lowercase() }))
}

private fun countFiles(root: FileNode?): Int {
    if (root == null) return 0
    var n = 0
    fun walk(node: FileNode) {
        if (!node.isDirectory) n++ else node.children.forEach(::walk)
    }
    root.children.forEach(::walk)
    if (!root.isDirectory && root.relPath.isNotEmpty()) n++
    return n
}
