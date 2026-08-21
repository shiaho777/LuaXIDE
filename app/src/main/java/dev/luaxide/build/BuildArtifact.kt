package dev.luaxide.build

import java.io.File

/** How far a build got / whether it is signed. */
enum class ArtifactKind { LUAXPKG, APK_UNSIGNED, APK_DEBUG, APK_RELEASE }

/**
 * A produced build output on disk. [file] is the artifact itself; the sidecar
 * metadata (name/version/kind/time) is persisted so the list survives restart.
 */
data class BuildArtifact(
    val id: String,
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val kind: ArtifactKind,
    val sizeBytes: Long,
    val createdAt: Long,
    val file: File,
)

/** Status of a single pipeline stage. */
enum class StageStatus { PENDING, RUNNING, DONE, FAILED }

/** A stage in the build pipeline, as surfaced to the timeline UI. */
data class BuildStage(
    val name: String,
    val status: StageStatus = StageStatus.PENDING,
    val detail: String = "",
)

/** Overall observable build state. */
data class BuildProgress(
    val stages: List<BuildStage> = emptyList(),
    val running: Boolean = false,
    val artifact: BuildArtifact? = null,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (stages.isEmpty()) 0f
        else stages.count { it.status == StageStatus.DONE }.toFloat() / stages.size
}
