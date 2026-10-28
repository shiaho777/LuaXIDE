package dev.webviewide.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import dev.webviewide.build.ApkInstaller
import dev.webviewide.build.ApkPackager
import dev.webviewide.build.BuildConfig
import dev.webviewide.build.DebugKeystore
import dev.webviewide.project.ProjectStore
import dev.webviewide.project.WebProject
import dev.webviewide.ui.bifold.BifoldMode
import dev.webviewide.ui.bifold.BifoldScaffold
import dev.webviewide.ui.bifold.rememberBifoldState
import dev.webviewide.ui.theme.CodeTextStyle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bifold = rememberBifoldState()
    var project by remember { mutableStateOf<WebProject?>(null) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var current by remember { mutableStateOf<File?>(null) }
    var text by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val opened = withContext(Dispatchers.IO) { ProjectStore.open(context) }
        val sources = ProjectStore.listSources(opened)
        val entry = sources.firstOrNull { ProjectStore.relative(opened, it) == opened.entryFile }
            ?: sources.firstOrNull()
        project = opened
        files = sources
        current = entry
        text = entry?.readText().orEmpty()
    }

    fun select(file: File) {
        if (file == current) return
        current = file
        text = runCatching { file.readText() }.getOrElse {
            status = it.message ?: "打不开这个文件"
            ""
        }
    }

    fun runPreview() {
        val file = current
        if (file != null) {
            runCatching { ProjectStore.write(file, text) }
                .onFailure { status = it.message ?: "保存失败" }
        }
        reloadKey += 1
    }

    fun pack() {
        val proj = project ?: return
        if (busy) return
        val file = current
        if (file != null) {
            val saved = runCatching { ProjectStore.write(file, text) }
            if (saved.isFailure) {
                status = saved.exceptionOrNull()?.message ?: "保存失败"
                return
            }
        }
        busy = true
        status = "正在打包"
        val appContext = context.applicationContext
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val outDir = File(proj.dir, "builds").apply { mkdirs() }
                    val out = File(outDir, "${proj.id}.apk")
                    ApkPackager(appContext).packageApk(
                        config = BuildConfig.forProject(proj.name, proj.entryFile, proj.id),
                        srcDir = proj.srcDir,
                        files = ProjectStore.listSources(proj),
                        outFile = out,
                        identity = DebugKeystore.getOrCreate(appContext),
                        projectDir = proj.dir,
                    )
                    out
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { apk ->
                    if (ApkInstaller.canRequestInstall(appContext)) {
                        status = "已签名，正在打开安装"
                        ApkInstaller.install(appContext, apk)
                    } else {
                        status = "请允许安装未知应用，然后再打包一次"
                        ApkInstaller.openInstallPermissionSettings(appContext)
                    }
                },
                onFailure = { error -> status = error.message ?: "打包失败" },
            )
        }
    }

    val ready = project != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "WebViewIDE", maxLines = 1) },
                actions = {
                    Button(onClick = { runPreview() }, enabled = ready && !busy) { Text("运行") }
                    TextButton(onClick = { pack() }, enabled = ready && !busy) { Text("打包") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                ModeItem(bifold.mode == BifoldMode.CODE, "代码", Icons.Outlined.Code) {
                    bifold.mode = BifoldMode.CODE
                }
                ModeItem(bifold.mode == BifoldMode.BOTH, "分屏", Icons.Outlined.VerticalSplit) {
                    bifold.mode = BifoldMode.BOTH
                }
                ModeItem(bifold.mode == BifoldMode.PREVIEW, "预览", Icons.Outlined.Visibility) {
                    bifold.mode = BifoldMode.PREVIEW
                }
            }
        },
    ) { inner ->
        val proj = project
        if (proj == null) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(Modifier.padding(inner).fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    files.forEach { file ->
                        FilterChip(
                            selected = file == current,
                            onClick = { select(file) },
                            label = { Text(ProjectStore.relative(proj, file)) },
                        )
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BifoldScaffold(
                    state = bifold,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    codeFace = {
                        BasicTextField(
                            value = text,
                            onValueChange = { updated ->
                                text = updated
                                val file = current ?: return@BasicTextField
                                runCatching { ProjectStore.write(file, updated) }
                                    .onFailure { status = it.message ?: "保存失败" }
                            },
                            textStyle = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    },
                    previewFace = {
                        WebPreview(proj.srcDir, proj.entryFile, reloadKey)
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.ModeItem(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        alwaysShowLabel = true,
    )
}

@Composable
private fun WebPreview(srcDir: File, entryFile: String, reloadKey: Int) {
    val context = LocalContext.current
    val entry = entryFile.trim().removePrefix("/").ifEmpty { "index.html" }
    val url = "https://appassets.androidplatform.net/site/$entry"
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler(
                    "/site/",
                    WebViewAssetLoader.InternalStoragePathHandler(context, srcDir),
                )
                .build()
            WebView(it).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                }
            }
        },
        update = { web ->
            val stamped = "$url?t=$reloadKey"
            if (web.tag != stamped) {
                web.tag = stamped
                web.clearCache(true)
                web.loadUrl(stamped)
            }
        },
        onRelease = { it.destroy() },
    )
}
