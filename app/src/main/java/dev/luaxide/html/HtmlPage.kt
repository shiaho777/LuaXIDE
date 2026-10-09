package dev.luaxide.html

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * LuaX HTML host. The page is real HTML/CSS drawn by the system WebView.
 * Lua queues DOM ops; this object applies them and posts element events back.
 * JavaScript and Python keep the JSON tree renderer.
 */
object HtmlPage {
    const val HOST = "luax.local"

    private val SCRIPT = Regex("(?is)<script\\b[^>]*>.*?</script>")

    /** Bridge injected after user scripts are stripped. No `</script>` in here. */
    private val BRIDGE = """
        (function(){
          function send(id, event, payload){
            if(!id) return;
            Luax.post(String(id), String(event), payload==null ? "" : String(payload));
          }
          document.addEventListener("click", function(e){
            var t = e.target;
            var el = t && t.closest ? t.closest("[id]") : null;
            if(el && el.id) send(el.id, "click", "");
          }, true);
          document.addEventListener("input", function(e){
            var el = e.target;
            if(el && el.id) send(el.id, "input", el.value==null ? "" : String(el.value));
          }, true);
          document.addEventListener("change", function(e){
            var el = e.target;
            if(!el || !el.id) return;
            var v = (el.type==="checkbox" || el.type==="radio") ? (el.checked ? "true" : "false") : (el.value==null ? "" : String(el.value));
            send(el.id, "change", v);
          }, true);
          document.addEventListener("submit", function(e){
            e.preventDefault();
            var el = e.target;
            if(el && el.id) send(el.id, "submit", "");
          }, true);
          window.__luaxApply = function(ops){
            var list;
            try { list = JSON.parse(ops); } catch (err) { return; }
            for(var i=0;i<list.length;i++){
              var op = list[i];
              var el = document.getElementById(op.id);
              if(!el) continue;
              if(op.op==="setText") el.textContent = op.text;
              else if(op.op==="setHtml") el.innerHTML = op.html;
              else if(op.op==="setAttr") el.setAttribute(op.name, op.value);
              else if(op.op==="setValue") el.value = op.value;
              else if(op.op==="addClass") el.classList.add(op["class"]);
              else if(op.op==="removeClass") el.classList.remove(op["class"]);
            }
          };
        })();
    """.trimIndent()

    fun siblingHtml(sourceRel: String?): String? {
        if (sourceRel.isNullOrBlank()) return null
        if (sourceRel.endsWith(".html", ignoreCase = true) || sourceRel.endsWith(".htm", ignoreCase = true)) {
            return sourceRel
        }
        val slash = sourceRel.lastIndexOf('/')
        val dir = if (slash >= 0) sourceRel.substring(0, slash + 1) else ""
        val base = sourceRel.substring(slash + 1).substringBeforeLast('.')
        if (base.isEmpty()) return null
        return "$dir$base.html"
    }

    /** Prefer `<script>.html` beside the entry, then `index.html`. */
    fun resolve(root: File?, sourceRel: String?): String {
        val sibling = siblingHtml(sourceRel)
        if (root != null && sibling != null && File(root, sibling).isFile) return sibling
        return "index.html"
    }

    fun prepare(raw: String): String {
        val stripped = SCRIPT.replace(raw, "")
        val inject = "<script>$BRIDGE</script>"
        val close = Regex("(?i)</body>")
        return if (close.containsMatchIn(stripped)) {
            close.replace(stripped, "$inject</body>")
        } else {
            stripped + inject
        }
    }

    fun applyCall(ops: String): String = "window.__luaxApply(${JSONObject.quote(ops)})"

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "json" -> "application/json"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

class LuaxJsBridge(
    private val main: Handler,
    private val onEvent: (String, String, String) -> Unit,
) {
    @JavascriptInterface
    fun post(id: String, event: String, payload: String) {
        main.post { onEvent(id, event, payload) }
    }
}

class HtmlAssetClient(
    private val root: File,
    private val onReady: () -> Unit,
) : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        onReady()
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return deny()
        if (uri.host != HtmlPage.HOST) return deny()
        var path = uri.path ?: return deny()
        if (path.startsWith("/")) path = path.drop(1)
        if (path.isEmpty() || path.contains("..")) return deny()
        val rootCanon = root.canonicalFile
        val canon = File(rootCanon, path).canonicalFile
        val prefix = rootCanon.path + File.separator
        if (canon != rootCanon && !canon.path.startsWith(prefix)) return deny()
        if (!canon.isFile) return null
        val ext = canon.name.substringAfterLast('.', "").lowercase()
        val bytes = if (ext == "html" || ext == "htm") {
            HtmlPage.prepare(canon.readText()).toByteArray(Charsets.UTF_8)
        } else {
            canon.readBytes()
        }
        val mime = HtmlPage.mime(canon.name)
        val charset = if (mime.startsWith("text") || mime == "application/javascript" || mime == "application/json") "utf-8" else null
        return WebResourceResponse(mime, charset, ByteArrayInputStream(bytes))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return request.url?.host != HtmlPage.HOST
    }

    private fun deny(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlScreen(
    root: File?,
    pageRel: String,
    generation: Int,
    ops: String,
    onEvent: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = remember { HtmlSession() }
    var web by remember { mutableStateOf<WebView?>(null) }
    val pageFile = root?.let { File(it, pageRel) }
    val missing = pageFile == null || !pageFile.isFile

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                if (root != null) webViewClient = HtmlAssetClient(root) { flushPending(session) }
                addJavascriptInterface(
                    LuaxJsBridge(Handler(Looper.getMainLooper()), onEvent),
                    "Luax",
                )
                web = this
                session.web = this
            }
        },
        update = { view ->
            if (root != null && view.webViewClient !is HtmlAssetClient) {
                view.webViewClient = HtmlAssetClient(root) { flushPending(session) }
            }
            session.web = view
        },
    )

    if (missing) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "这个 Lua 工程还没有页面。在旁边放 index.html（或与脚本同名的 .html）。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    LaunchedEffect(web, generation, pageRel, root) {
        val view = web ?: return@LaunchedEffect
        val dir = root ?: return@LaunchedEffect
        val file = File(dir, pageRel)
        session.ready = false
        if (!file.isFile) return@LaunchedEffect
        session.pending.clear()
        if (ops.isNotEmpty() && ops != "[]") session.pending.add(ops)
        val base = "https://${HtmlPage.HOST}/$pageRel"
        view.loadDataWithBaseURL(base, HtmlPage.prepare(file.readText()), "text/html", "utf-8", null)
    }

    LaunchedEffect(ops) {
        if (ops.isEmpty() || ops == "[]") return@LaunchedEffect
        val view = session.web
        if (session.ready && view != null) {
            view.evaluateJavascript(HtmlPage.applyCall(ops), null)
        } else if (session.pending.lastOrNull() != ops) {
            session.pending.add(ops)
        }
    }
}

private class HtmlSession {
    val pending = mutableListOf<String>()
    var ready: Boolean = false
    var web: WebView? = null
}

/** Call from the asset client once the document is up. Wired in [HtmlScreen] via a client hook. */
private fun flushPending(session: HtmlSession) {
    val view = session.web ?: return
    val batch = session.pending.toList()
    session.pending.clear()
    session.ready = true
    batch.forEach { view.evaluateJavascript(HtmlPage.applyCall(it), null) }
}
