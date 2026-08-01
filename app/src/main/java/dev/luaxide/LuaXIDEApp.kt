package dev.luaxide

import android.app.Application
import dev.luaxide.ui.runtime.ComponentCatalog

class LuaXIDEApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Drift guard: warns (never crashes) if the renderer knows a component
        // type the low-code palette can't yet author.
        ComponentCatalog.validateAgainstRegistry()
    }
}
