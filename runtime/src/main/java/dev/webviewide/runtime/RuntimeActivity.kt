package dev.webviewide.runtime

import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class RuntimeActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = readEntry()
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/www/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/www/$entry")
        }
        web = view
        setContentView(view)
    }

    @Deprecated("Use the system back callback")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    private fun readEntry(): String {
        val raw = runCatching {
            assets.open("webcfg.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return "index.html"
        val name = runCatching { JSONObject(raw).optString("entryFile", "index.html") }
            .getOrDefault("index.html")
        return sanitize(name)
    }

    private fun sanitize(name: String): String {
        val clean = name.trim().removePrefix("/").replace('\\', '/')
        if (clean.isEmpty() || clean.contains("..") || clean.startsWith("/")) return "index.html"
        return clean
    }
}
