package dev.luaxide.build

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

data class IconTransform(
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val safeZone: Float = 0.66f,
    val corner: Float = 0.22f,
)

object IconGenerator {

    data class Layers(
        val foregroundPng: ByteArray,
        val backgroundPng: ByteArray,
        val previewPng: ByteArray,
    )

    fun generate(
        sourceImage: File?,
        bgColor: Long = 0xFF185FA5,
        appName: String = "L",
        transform: IconTransform = IconTransform(),
        size: Int = 432,
    ): Layers {
        val bg = solidPng(size, bgColor.toInt())
        val letter = appName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "L"
        val fg = if (sourceImage != null && sourceImage.isFile) {
            foregroundFromImage(sourceImage, size, transform)
        } else {
            monogramForeground(size, letter, transform)
        }
        val preview = compositePreview(fg, bgColor.toInt(), 192, transform.corner)
        return Layers(foregroundPng = fg, backgroundPng = bg, previewPng = preview)
    }

    private fun solidPng(size: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp.toPng()
    }

    private fun monogramForeground(size: Int, letter: String, t: IconTransform): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.42f * t.scale.coerceIn(0.4f, 1.8f)
            isFakeBoldText = true
        }
        val cx = size / 2f + size * t.offsetX.coerceIn(-0.3f, 0.3f)
        val cy = size / 2f + size * t.offsetY.coerceIn(-0.3f, 0.3f)
        val y = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter.take(1), cx, y, paint)
        return bmp.toPng()
    }

    private fun foregroundFromImage(file: File, size: Int, t: IconTransform): ByteArray {
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
            ?: return monogramForeground(size, "A", t)
        val zone = t.safeZone.coerceIn(0.4f, 0.9f)
        val base = (size * zone).roundToInt().coerceAtLeast(1)
        val draw = (base * t.scale.coerceIn(0.4f, 1.8f)).roundToInt().coerceAtLeast(1)
        val scaled = scaleCenterCrop(decoded, draw, draw)
        if (decoded !== scaled) decoded.recycle()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val left = (size - draw) / 2f + size * t.offsetX.coerceIn(-0.3f, 0.3f)
        val top = (size - draw) / 2f + size * t.offsetY.coerceIn(-0.3f, 0.3f)
        canvas.drawBitmap(scaled, left, top, null)
        scaled.recycle()
        return bmp.toPng()
    }

    private fun compositePreview(fgPng: ByteArray, bgColor: Int, size: Int, corner: Float): ByteArray {
        val fg = BitmapFactory.decodeByteArray(fgPng, 0, fgPng.size)
            ?: return solidPng(size, bgColor)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = bgColor
        val r = size * corner.coerceIn(0.05f, 0.5f)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        val scaled = Bitmap.createScaledBitmap(fg, size, size, true)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        if (scaled !== fg) scaled.recycle()
        fg.recycle()
        return bmp.toPng()
    }

    private fun scaleCenterCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val x = ((sw - w) / 2).coerceAtLeast(0)
        val y = ((sh - h) / 2).coerceAtLeast(0)
        val out = Bitmap.createBitmap(scaled, x, y, min(w, sw), min(h, sh))
        if (scaled !== src && scaled !== out) scaled.recycle()
        return out
    }

    private fun Bitmap.toPng(): ByteArray {
        val bos = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, bos)
        recycle()
        return bos.toByteArray()
    }
}
