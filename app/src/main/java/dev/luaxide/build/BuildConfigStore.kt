package dev.luaxide.build

import dev.luaxide.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BuildConfigStore(private val repo: ProjectRepository) {

    private fun cfgFile(projectId: String) = File(repo.projectDirOf(projectId), "build.luaxcfg")

    suspend fun load(projectId: String, fallback: BuildConfig): BuildConfig =
        withContext(Dispatchers.IO) {
            val f = cfgFile(projectId)
            if (!f.isFile) return@withContext fallback
            runCatching {
                val o = JSONObject(f.readText())
                BuildConfig(
                    appName = o.optString("appName", fallback.appName),
                    packageName = o.optString("packageName", fallback.packageName),
                    versionName = o.optString("versionName", fallback.versionName),
                    versionCode = o.optInt("versionCode", fallback.versionCode),
                    entryFile = o.optString("entryFile", fallback.entryFile),
                    orientation = runCatching {
                        Orientation.valueOf(o.optString("orientation", fallback.orientation.name))
                    }.getOrDefault(fallback.orientation),
                    abis = o.optJSONArray("abis")?.let { arr ->
                        buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
                    } ?: fallback.abis,
                    aot = o.optBoolean("aot", fallback.aot),
                    signMode = runCatching {
                        SignMode.valueOf(o.optString("signMode", fallback.signMode.name))
                    }.getOrDefault(fallback.signMode),
                    releaseKeystoreId = o.optString("releaseKeystoreId", "")
                        .takeIf { it.isNotBlank() },
                    iconPath = o.optString("iconPath", "").takeIf { it.isNotBlank() },
                    iconBgColor = o.optLong("iconBgColor", fallback.iconBgColor),
                    iconScale = o.optDouble("iconScale", fallback.iconScale.toDouble()).toFloat(),
                    iconOffsetX = o.optDouble("iconOffsetX", fallback.iconOffsetX.toDouble()).toFloat(),
                    iconOffsetY = o.optDouble("iconOffsetY", fallback.iconOffsetY.toDouble()).toFloat(),
                    iconSafeZone = o.optDouble("iconSafeZone", fallback.iconSafeZone.toDouble()).toFloat(),
                    iconCorner = o.optDouble("iconCorner", fallback.iconCorner.toDouble()).toFloat(),
                    schema = o.optInt("schema", BuildConfig.SCHEMA_VERSION),
                )
            }.getOrDefault(fallback)
        }

    suspend fun save(projectId: String, config: BuildConfig) = withContext(Dispatchers.IO) {
        val o = JSONObject()
            .put("appName", config.appName)
            .put("packageName", config.packageName)
            .put("versionName", config.versionName)
            .put("versionCode", config.versionCode)
            .put("entryFile", config.entryFile)
            .put("orientation", config.orientation.name)
            .put("abis", JSONArray(config.abis.toList()))
            .put("aot", config.aot)
            .put("signMode", config.signMode.name)
            .put("releaseKeystoreId", config.releaseKeystoreId)
            .put("iconPath", config.iconPath)
            .put("iconBgColor", config.iconBgColor)
            .put("iconScale", config.iconScale.toDouble())
            .put("iconOffsetX", config.iconOffsetX.toDouble())
            .put("iconOffsetY", config.iconOffsetY.toDouble())
            .put("iconSafeZone", config.iconSafeZone.toDouble())
            .put("iconCorner", config.iconCorner.toDouble())
            .put("schema", config.schema)
        val target = cfgFile(projectId)
        val tmp = File(target.parentFile, ".build.luaxcfg.tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(target)) {
            target.writeText(o.toString(2))
            tmp.delete()
        }
    }
}
