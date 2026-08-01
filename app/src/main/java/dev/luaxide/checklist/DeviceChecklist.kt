package dev.luaxide.checklist

enum class CheckStatus { Idle, Running, Pass, Fail, Skip }

data class CheckItem(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: CheckStatus = CheckStatus.Idle,
    val message: String = "",
)

object DeviceChecklistCatalog {
    val items: List<CheckItem> = listOf(
        CheckItem("sandbox", "沙箱布局", "无 root 应用沙箱目录"),
        CheckItem("proot", "Proot 用户态 RootFS", ".proot 自检"),
        CheckItem("stdin", "stdin 阻塞", "io.read() 等待输入"),
        CheckItem("cancel", "任务可取消", "阻塞中取消恢复"),
        CheckItem("template", "APK 运行时模板", "构建前置资源"),
        CheckItem("install", "APK 安装能力", "未知来源安装权限"),
    )
}

data class ChecklistReport(
    val items: List<CheckItem> = DeviceChecklistCatalog.items,
    val running: Boolean = false,
    val finishedAt: Long? = null,
) {
    fun update(id: String, status: CheckStatus, message: String = ""): ChecklistReport {
        val next = items.map {
            if (it.id == id) it.copy(status = status, message = message) else it
        }
        return copy(items = next)
    }
}
