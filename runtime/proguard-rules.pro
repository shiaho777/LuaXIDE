-keep class dev.luaxide.engine.LuaxNative { *; }
-keepclassmembers class dev.luaxide.engine.LuaxNative {
    native <methods>;
}
-keep class dev.luaxide.runtime.** { *; }
-keepclassmembers class dev.luaxide.html.LuaxJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn **
