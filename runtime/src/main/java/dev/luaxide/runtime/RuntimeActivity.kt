package dev.luaxide.runtime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.luaxide.engine.EngineAdapter
import dev.luaxide.engine.EngineHost
import dev.luaxide.engine.JsEngineHost
import dev.luaxide.engine.PyEngineHost
import dev.luaxide.engine.RunResult
import dev.luaxide.ui.runtime.Motion
import dev.luaxide.ui.runtime.RenderTree
import dev.luaxide.ui.runtime.TreeViewport
import dev.luaxide.assets.FileAssetResolver
import dev.luaxide.assets.LocalAssetResolver
import androidx.compose.runtime.CompositionLocalProvider
import dev.luaxide.ui.runtime.nodeIdentity
import dev.luaxide.ui.theme.LuaXIDETheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class RuntimeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LuaXIDETheme {
                RuntimeApp(loadBundle(this@RuntimeActivity))
            }
        }
    }
}

private data class AppBundle(
    val entryFile: String,
    val source: String,
    val appName: String,
    val modRoot: String = "",
)

private fun loadBundle(activity: ComponentActivity): AppBundle {
    val am = activity.assets
    val cfg = runCatching {
        am.open("luaxcfg.json").bufferedReader().use { it.readText() }
    }.getOrDefault("{}")
    val o = runCatching { JSONObject(cfg) }.getOrDefault(JSONObject())
    val entry = o.optString("entryFile", "main.lua").ifBlank { "main.lua" }
    val appName = o.optString("appName", "LuaX App")
    val modRoot = extractLuaTree(activity)
    val path = "lua/$entry"
    val source = runCatching {
        File(modRoot, entry).takeIf { it.isFile }?.readText()
            ?: am.open(path).bufferedReader().use { it.readText() }
    }.getOrElse {
        runCatching {
            File(modRoot, "main.lua").takeIf { it.isFile }?.readText()
                ?: am.open("lua/main.lua").bufferedReader().use { it.readText() }
        }.getOrDefault("local ui=require(\"ui\")\nreturn ui.app{ui.text{text=\"missing main.lua\"}}")
    }
    return AppBundle(entryFile = entry, source = source, appName = appName, modRoot = modRoot)
}

private fun extractLuaTree(activity: ComponentActivity): String {
    val dest = File(activity.filesDir, "lua-src").apply { mkdirs() }
    val am = activity.assets
    fun walk(assetPath: String, outDir: File) {
        val children = am.list(assetPath) ?: return
        if (children.isEmpty()) {
            runCatching {
                am.open(assetPath).use { input ->
                    outDir.parentFile?.mkdirs()
                    FileOutputStream(outDir).use { input.copyTo(it) }
                }
            }
            return
        }
        outDir.mkdirs()
        for (child in children) {
            walk("$assetPath/$child", File(outDir, child))
        }
    }
    walk("lua", dest)
    return dest.absolutePath
}

@Composable
private fun RuntimeApp(bundle: AppBundle) {
    // Language routing per PLATFORM_ABI: the entry file extension picks the engine.
    val engine = remember {
        when {
            bundle.entryFile.endsWith(".js") -> JsEngineHost()
            bundle.entryFile.endsWith(".py") -> PyEngineHost()
            else -> EngineHost()
        }
    }
    var result by remember { mutableStateOf<RunResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    DisposableEffect(Unit) {
        onDispose { engine.close() }
    }

    LaunchedEffect(bundle.source, bundle.modRoot) {
        loading = true
        if (bundle.modRoot.isNotEmpty()) when (engine) {
            is EngineHost -> engine.setModuleRoot(bundle.modRoot)
            is PyEngineHost -> engine.setModuleRoot(bundle.modRoot)
            else -> {}
        }
        result = engine.run(bundle.source)
        loading = false
    }

    val phase = when {
        loading || result == null -> "loading"
        result?.ok != true -> "error"
        result?.tree == null -> "empty"
        else -> "content"
    }
    val tree = result?.takeIf { it.ok }?.tree
    val contentAlpha by animateFloatAsState(
        targetValue = if (phase == "content") 1f else 0f,
        animationSpec = Motion.softFloat,
        label = "rt-alpha",
    )
    val contentScale by animateFloatAsState(
        targetValue = if (phase == "content") 1f else 0.985f,
        animationSpec = Motion.softFloat,
        label = "rt-scale",
    )
    val contentY by animateFloatAsState(
        targetValue = if (phase == "content") 0f else 8f,
        animationSpec = Motion.softFloat,
        label = "rt-y",
    )
    val scroll = rememberScrollState()

    Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = cs.background) {
        Box(Modifier.fillMaxSize()) {
            if (tree != null) {
                val rootKey = nodeIdentity(tree, "root")
                androidx.compose.runtime.key(rootKey) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentAlpha.coerceIn(0f, 1f)
                            scaleX = contentScale
                            scaleY = contentScale
                            translationY = contentY
                        },
                ) {
                    CompositionLocalProvider(
                        LocalAssetResolver provides FileAssetResolver(File(bundle.modRoot.ifBlank { "." })),
                    ) {
                        // Shared scroll policy with the IDE preview: trees that declare
                        // their own scrollers/weights own the finite viewport; plain
                        // trees keep host scrolling (NodeProps.TreeViewport).
                        TreeViewport(node = tree) {
                            RenderTree(
                                node = tree,
                                onEvent = { handlerId, payload ->
                                    scope.launch { result = engine.invoke(handlerId, payload) }
                                },
                            )
                        }
                    }
                }
                }
            }
            if (phase != "content") {
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { Motion.fadeThrough() },
                    modifier = Modifier.fillMaxSize(),
                    label = "rt-phase",
                ) { p ->
                    when (p) {
                        "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        "error" -> ErrorView(result?.error ?: "unknown error")
                        "empty" -> ProgramTerminal(result?.output.orEmpty(), bundle.entryFile)
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("starting…", color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorView(message: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("runtime error", color = cs.error, fontSize = 16.sp)
            Text(message, color = cs.onSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}


@Composable
private fun ProgramTerminal(output: String, entryFile: String) {
    val bg = androidx.compose.ui.graphics.Color(0xFF0B1020)
    val fg = androidx.compose.ui.graphics.Color(0xFFD7E0F2)
    val dim = androidx.compose.ui.graphics.Color(0xFF7F8BA3)
    val cyan = androidx.compose.ui.graphics.Color(0xFF6BCBFF)
    Surface(Modifier.fillMaxSize(), color = bg) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("luax · terminal · no-root", color = dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("› ${when { entryFile.endsWith(".js") -> "node"; entryFile.endsWith(".py") -> "python"; else -> "lua" }} $entryFile", color = cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            if (output.isBlank()) {
                Text("(no output)", color = dim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            } else {
                output.lineSequence().forEach { line ->
                    Text(line.ifEmpty { " " }, color = fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
            Text("[process finished · app sandbox]", color = dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
