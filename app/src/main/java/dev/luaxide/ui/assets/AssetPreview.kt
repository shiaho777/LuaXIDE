package dev.luaxide.ui.assets

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun AssetPreview(
    path: String,
    absoluteFile: File?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val ext = path.substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("png", "jpg", "jpeg", "webp", "gif")
    val isFont = ext in setOf("ttf", "otf", "ttc")
    val bmp = remember(absoluteFile?.absolutePath, absoluteFile?.length()) {
        if (isImage && absoluteFile != null && absoluteFile.isFile) {
            runCatching { BitmapFactory.decodeFile(absoluteFile.absolutePath) }.getOrNull()
        } else null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cs.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("资源预览", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Text(path, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
        if (absoluteFile != null && absoluteFile.isFile) {
            Text(
                humanSize(absoluteFile.length()),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
        Surface(
            color = cs.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = path,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    isFont -> Text(
                        "字体文件\n可在 ui.text { font = \"$path\" } 使用",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(
                        "二进制资源\n会随 APK 打入 assets/lua/",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Surface(
            color = cs.primaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                if (isImage) {
                    "示例：ui.image { src = \"$path\", size = 120 }"
                } else if (isFont) {
                    "示例：ui.text { text = \"Hi\", font = \"$path\", size = 20 }"
                } else {
                    "资源路径相对 src/，打包后位于 assets/lua/"
                },
                fontFamily = FontFamily.Monospace,
                color = cs.onPrimaryContainer,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
