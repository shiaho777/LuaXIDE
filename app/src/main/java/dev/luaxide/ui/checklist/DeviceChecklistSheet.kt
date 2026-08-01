package dev.luaxide.ui.checklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.luaxide.checklist.CheckItem
import dev.luaxide.checklist.CheckStatus
import dev.luaxide.checklist.ChecklistReport
import dev.luaxide.ui.S

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceChecklistSheet(
    report: ChecklistReport,
    onRunAll: () -> Unit,
    onOpenBuild: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "设备自检",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = S.CANCEL)
                }
            }
            Text(
                "固定检查：Proot · stdin 阻塞/取消 · APK 构建前置与安装能力",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Button(onClick = onRunAll, enabled = !report.running) {
                    if (report.running) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 0.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    }
                    Text(" 全部检查", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = onOpenBuild, enabled = !report.running) {
                    Text("打开构建")
                }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(report.items, key = { it.id }) { item ->
                    CheckRow(item)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint) = when (item.status) {
        CheckStatus.Pass -> Icons.Filled.CheckCircle to cs.primary
        CheckStatus.Fail -> Icons.Filled.Error to cs.error
        CheckStatus.Running -> Icons.Filled.HourglassEmpty to cs.tertiary
        CheckStatus.Skip -> Icons.Filled.RadioButtonUnchecked to cs.outline
        CheckStatus.Idle -> Icons.Filled.RadioButtonUnchecked to cs.onSurfaceVariant
    }
    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.status == CheckStatus.Running) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (item.message.isNotBlank()) {
                    Text(
                        item.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            CheckStatus.Fail -> cs.error
                            CheckStatus.Pass -> cs.primary
                            else -> cs.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
