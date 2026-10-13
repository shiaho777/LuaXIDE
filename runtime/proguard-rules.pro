-keep class dev.luaxide.engine.LuaxNative { *; }
-keepclassmembers class dev.luaxide.engine.LuaxNative {
    native <methods>;
}
-keep class dev.luaxide.runtime.** { *; }
-dontwarn **
