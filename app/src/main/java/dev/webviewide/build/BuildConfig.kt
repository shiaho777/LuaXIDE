package dev.webviewide.build

enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

enum class SignMode { DEBUG, RELEASE }

data class BuildConfig(
    val appName: String = "My app",
    val packageName: String = "dev.webviewide.app",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val entryFile: String = "index.html",
    val orientation: Orientation = Orientation.AUTO,
    val abis: Set<String> = DEFAULT_ABIS,
    val signMode: SignMode = SignMode.DEBUG,
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

        fun forProject(name: String, entryFile: String, id: String): BuildConfig {
            val slug = id.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.ifEmpty { "app" }
            return BuildConfig(
                appName = name,
                packageName = "dev.webviewide.$slug",
                entryFile = entryFile,
            )
        }
    }
}
