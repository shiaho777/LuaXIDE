package dev.webviewide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.webviewide.ui.IdeScreen
import dev.webviewide.ui.theme.WebViewIDETheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WebViewIDETheme {
                IdeScreen()
            }
        }
    }
}
