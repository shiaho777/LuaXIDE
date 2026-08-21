package dev.luaxide.build

import dev.luaxide.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Lists and manages produced build artifacts for a project. Artifacts live in
 * <project>/builds/<buildId>/ with an artifact.json sidecar, scanned back on
 * demand so the list survives restart.
 */
class BuildRepository(private val repo: ProjectRepository) {

    private fun buildsDir(projectId: String) = File(repo.projectDirOf(projectId), "builds")

    suspend fun list(projectId: String): List<BuildArtifact> = withContext(Dispatchers.IO) {
        val dir = buildsDir(projectId)
        dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readArtifact(it) }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()
    }

    private fun readArtifact(dir: File): BuildArtifact? {
        val meta = File(dir, "artifact.json")
        if (!meta.isFile) return null
        return runCatching {
            val o = JSONObject(meta.readText())
            val fileName = o.getString("fileName")
            val file = File(dir, fileName)
            BuildArtifact(
                id = o.optString("id", dir.name),
                appName = o.optString("appName", "app"),
                versionName = o.optString("versionName", "1.0.0"),
                versionCode = o.optInt("versionCode", 1),
                kind = runCatching { ArtifactKind.valueOf(o.optString("kind")) }
                    .getOrDefault(ArtifactKind.LUAXPKG),
                sizeBytes = o.optLong("sizeBytes", file.length()),
                createdAt = o.optLong("createdAt", dir.lastModified()),
                file = file,
            )
        }.getOrNull()
    }

    suspend fun delete(projectId: String, artifactId: String) = withContext(Dispatchers.IO) {
        File(buildsDir(projectId), artifactId).deleteRecursively()
    }
}
