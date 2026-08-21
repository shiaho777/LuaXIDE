package dev.luaxide.build

enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

enum class SignMode { DEBUG, RELEASE }

data class BuildConfig(
    val appName: String = "My app",
    val packageName: String = "dev.luaxide.app",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val entryFile: String = "main.lua",
    val orientation: Orientation = Orientation.AUTO,
    val abis: Set<String> = DEFAULT_ABIS,
    val aot: Boolean = false,
    val signMode: SignMode = SignMode.DEBUG,
    val releaseKeystoreId: String? = null,
    val iconPath: String? = null,
    val iconBgColor: Long = 0xFF185FA5,
    val iconScale: Float = 1.0f,
    val iconOffsetX: Float = 0f,
    val iconOffsetY: Float = 0f,
    val iconSafeZone: Float = 0.66f,
    val iconCorner: Float = 0.22f,
    val schema: Int = SCHEMA_VERSION,
) {
    fun iconTransform() = IconTransform(
        scale = iconScale,
        offsetX = iconOffsetX,
        offsetY = iconOffsetY,
        safeZone = iconSafeZone,
        corner = iconCorner,
    )

    companion object {
        const val SCHEMA_VERSION = 3
        val DEFAULT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

        fun forProject(name: String, entryFile: String): BuildConfig {
            val slug = name.lowercase()
                .map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
                .trim('_')
                .ifEmpty { "app" }
            return BuildConfig(
                appName = name,
                packageName = "dev.luaxide.$slug",
                entryFile = entryFile,
            )
        }
    }
}
