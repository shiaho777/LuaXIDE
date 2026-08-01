package dev.luaxide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.luaxide.ui.shell.MainScreen
import dev.luaxide.ui.theme.LuaXIDETheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LuaXIDETheme {
                MainScreen()
            }
        }
    }
}
