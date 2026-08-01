package dev.luaxide.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

interface AssetResolver {
    fun resolveFile(rel: String): File?
    fun decodeBitmap(rel: String): Bitmap?
    fun typeface(rel: String): Typeface?
}

object EmptyAssetResolver : AssetResolver {
    override fun resolveFile(rel: String): File? = null
    override fun decodeBitmap(rel: String): Bitmap? = null
    override fun typeface(rel: String): Typeface? = null
}

val LocalAssetResolver = staticCompositionLocalOf<AssetResolver> { EmptyAssetResolver }

class FileAssetResolver(private val root: File) : AssetResolver {
    override fun resolveFile(rel: String): File? {
        val clean = cleanPath(rel) ?: return null
        val f = File(root, clean)
        return f.takeIf { it.isFile }
    }

    override fun decodeBitmap(rel: String): Bitmap? {
        val f = resolveFile(rel) ?: return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    override fun typeface(rel: String): Typeface? {
        val f = resolveFile(rel) ?: return null
        return runCatching { Typeface.createFromFile(f) }.getOrNull()
    }
}

class AssetManagerResolver(
    private val context: Context,
    private val assetPrefix: String = "lua/",
    private val cacheDir: File,
) : AssetResolver {
    override fun resolveFile(rel: String): File? {
        val clean = cleanPath(rel) ?: return null
        val cached = File(cacheDir, clean)
        if (cached.isFile) return cached
        return runCatching {
            context.assets.open(assetPrefix + clean).use { input ->
                cached.parentFile?.mkdirs()
                val tmp = File(cached.parentFile, ".${cached.name}.tmp")
                tmp.outputStream().use { input.copyTo(it) }
                if (!tmp.renameTo(cached)) {
                    tmp.copyTo(cached, overwrite = true)
                    tmp.delete()
                }
            }
            cached.takeIf { it.isFile }
        }.getOrNull()
    }

    override fun decodeBitmap(rel: String): Bitmap? {
        val clean = cleanPath(rel) ?: return null
        runCatching {
            context.assets.open(assetPrefix + clean).use { input ->
                return BitmapFactory.decodeStream(input)
            }
        }
        return resolveFile(rel)?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    override fun typeface(rel: String): Typeface? {
        val f = resolveFile(rel) ?: return null
        return runCatching { Typeface.createFromFile(f) }.getOrNull()
    }
}

private fun cleanPath(rel: String): String? {
    val clean = rel.trim().removePrefix("./").trimStart('/')
    if (clean.isEmpty()) return null
    if (clean.split('/').any { it == ".." || it.isEmpty() }) return null
    return clean
}
