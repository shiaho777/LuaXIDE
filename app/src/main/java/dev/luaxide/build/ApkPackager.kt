package dev.luaxide.build

import android.content.Context
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ApkPackager(private val context: Context) {

    fun packageApk(
        config: BuildConfig,
        srcDir: File,
        files: List<File>,
        outFile: File,
        identity: SigningIdentity,
        projectDir: File,
        onLog: (String) -> Unit = {},
        onPhase: (String, String) -> Unit = { _, _ -> },
    ): ArtifactKind {
        val work = File(outFile.parentFile, ".apk-work").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val template = File(work, "template.apk")
            extractTemplate(template)
            onLog("template ready (${humanSize(template.length())})")

            val module = ApkModule.loadApkFile(template)
            try {
                val oldPkg = module.androidManifest?.packageName ?: "dev.luaxide.runtime"
                onPhase("package", "manifest")
                applyManifest(module, config, oldPkg)
                onPhase("package", "sources")
                injectSources(module, srcDir, files, config)
                onPhase("package", "icon")
                injectIcon(module, config, projectDir, onLog)
                filterAbis(module, config.abis)
                stripMeta(module)
                module.refreshTable()
                module.refreshManifest()

                val unsigned = File(work, "unsigned.apk")
                onPhase("package", "write")
                module.writeApk(unsigned)
                if (!unsigned.isFile || unsigned.length() < 1024L) {
                    throw IllegalStateException("unsigned apk write failed")
                }
                onLog("assembled unsigned apk (${humanSize(unsigned.length())})")
                onPhase("package", "done")

                val signedTmp = File(work, "signed.apk")
                onPhase("sign", "signing")
                sign(unsigned, signedTmp, identity)
                if (!signedTmp.isFile || signedTmp.length() < 1024L) {
                    throw IllegalStateException("signing produced empty apk")
                }
                val kind = if (config.signMode == SignMode.RELEASE) {
                    ArtifactKind.APK_RELEASE
                } else {
                    ArtifactKind.APK_DEBUG
                }
                onLog("signed (${kind.name.lowercase()}, ${humanSize(signedTmp.length())})")
                onPhase("sign", "done")

                val tmpOut = File(outFile.parentFile, ".${outFile.name}.tmp")
                signedTmp.copyTo(tmpOut, overwrite = true)
                if (!tmpOut.renameTo(outFile)) {
                    tmpOut.copyTo(outFile, overwrite = true)
                    tmpOut.delete()
                }
                if (!outFile.isFile || outFile.length() < 1024L) {
                    throw IllegalStateException("final apk missing after write")
                }
                return kind
            } finally {
                runCatching { module.close() }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun extractTemplate(target: File) {
        val am = context.assets
        val names = am.list("runtime")?.toList().orEmpty()
        val assetName = names.firstOrNull { it.endsWith(".apk") }
            ?: throw IllegalStateException("runtime template missing (assets/runtime/*.apk)")
        am.open("runtime/$assetName").use { input ->
            val tmp = File(target.parentFile, ".template.apk.tmp")
            FileOutputStream(tmp).use { out -> input.copyTo(out) }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        if (!target.isFile || target.length() < 1024L) {
            throw IllegalStateException("runtime template extract failed")
        }
    }

    private fun applyManifest(module: ApkModule, config: BuildConfig, oldPkg: String) {
        val manifest = module.androidManifest
            ?: throw IllegalStateException("template has no AndroidManifest")

        manifest.packageName = config.packageName
        module.packageName = config.packageName
        manifest.versionName = config.versionName
        manifest.versionCode = config.versionCode
        manifest.setApplicationLabel(config.appName)

        val table = module.tableBlock
        if (table != null) {
            for (pkg in table) {
                val n = pkg.name
                if (n == oldPkg || n.isNullOrBlank() || n == "dev.luaxide.runtime") {
                    pkg.name = config.packageName
                }
            }
        }

        val main = manifest.mainActivity
        if (main != null && config.orientation != Orientation.AUTO) {
            val orient = when (config.orientation) {
                Orientation.PORTRAIT -> 1
                Orientation.LANDSCAPE -> 0
                Orientation.AUTO -> -1
            }
            val attr = main.getOrCreateAndroidAttribute(
                AndroidManifest.NAME_screenOrientation,
                AndroidManifest.ID_screenOrientation,
            )
            attr.setValueType(ValueType.DEC)
            attr.setData(orient)
        }

        rewritePackageScopedStrings(manifest.manifestElement, oldPkg, config.packageName)
    }

    private fun rewritePackageScopedStrings(
        root: ResXmlElement?,
        oldPkg: String,
        newPkg: String,
    ) {
        if (root == null || oldPkg == newPkg) return
        val walk = ArrayDeque<ResXmlElement>()
        walk.add(root)
        while (walk.isNotEmpty()) {
            val el = walk.removeFirst()
            val tag = el.name ?: ""
            val attrs = el.attributes
            while (attrs.hasNext()) {
                val a: ResXmlAttribute = attrs.next()
                val attrName = a.name ?: ""
                val isAuth = a.equalsName(AndroidManifest.NAME_authorities)
                val isPermDef = a.equalsName(AndroidManifest.NAME_name) &&
                    (tag == "permission" || tag == "uses-permission" || tag == "permission-tree" || tag == "permission-group")
                val isPermAttr = attrName == "permission" ||
                    attrName == "readPermission" ||
                    attrName == "writePermission"
                if (!isAuth && !isPermDef && !isPermAttr) continue
                val v = a.valueAsString ?: continue
                if (v == oldPkg || v.startsWith("$oldPkg.")) {
                    a.setValueAsString(newPkg + v.removePrefix(oldPkg))
                }
            }
            val kids = el.elements
            while (kids.hasNext()) walk.add(kids.next())
        }
    }

    private fun injectIcon(
        module: ApkModule,
        config: BuildConfig,
        projectDir: File,
        onLog: (String) -> Unit,
    ) {
        val table = module.tableBlock ?: return
        val iconFile = config.iconPath?.let { rel ->
            val f = File(projectDir, rel)
            if (f.isFile) f else null
        }
        val transform = config.iconTransform()
        val layers = if (iconFile != null) {
            onLog("icon from ${iconFile.name}")
            IconGenerator.generate(iconFile, config.iconBgColor, config.appName, transform)
        } else {
            onLog("icon monogram for '${config.appName}'")
            IconGenerator.generate(null, config.iconBgColor, config.appName, transform)
        }
        replaceDrawablePng(
            module,
            table,
            type = "drawable",
            name = "ic_launcher_background",
            png = layers.backgroundPng,
            newPath = "res/drawable-xxxhdpi-v4/ic_launcher_background.png",
        )
        replaceDrawablePng(
            module,
            table,
            type = "drawable",
            name = "ic_launcher_foreground",
            png = layers.foregroundPng,
            newPath = "res/drawable-xxxhdpi-v4/ic_launcher_foreground.png",
        )
    }

    private fun replaceDrawablePng(
        module: ApkModule,
        table: com.reandroid.arsc.chunk.TableBlock,
        type: String,
        name: String,
        png: ByteArray,
        newPath: String,
    ) {
        val entry = table.getEntry(null, type, name) ?: return
        val oldPath = entry.valueAsString
        if (!oldPath.isNullOrBlank()) {
            module.removeInputSource(oldPath)
        }
        entry.setValueAsString(newPath)
        val source = ByteInputSource(png, newPath)
        source.method = java.util.zip.ZipEntry.STORED
        module.add(source)
    }

    private fun injectSources(
        module: ApkModule,
        srcDir: File,
        files: List<File>,
        config: BuildConfig,
    ) {
        module.listInputSources()
            .map { it.alias }
            .filter { it.startsWith("assets/lua/") || it == "assets/luaxcfg.json" }
            .forEach { module.removeInputSource(it) }

        val cfg = org.json.JSONObject()
            .put("appName", config.appName)
            .put("packageName", config.packageName)
            .put("versionName", config.versionName)
            .put("versionCode", config.versionCode)
            .put("entryFile", config.entryFile)
            .put("orientation", config.orientation.name)
            .toString(2)
            .toByteArray(Charsets.UTF_8)
        module.add(ByteInputSource(cfg, "assets/luaxcfg.json"))

        files.forEach { f ->
            val rel = f.toRelativeString(srcDir).replace(File.separatorChar, '/')
            module.add(ByteInputSource(f.readBytes(), "assets/lua/$rel"))
        }
    }

    private fun filterAbis(module: ApkModule, abis: Set<String>) {
        if (abis.isEmpty()) return
        val keep = abis.map { it.lowercase(Locale.US) }.toSet()
        module.listNativeLibraryFiles()
            .map { it.alias }
            .filter { path ->
                val parts = path.split('/')
                parts.size >= 3 && parts[0] == "lib" && parts[1].lowercase(Locale.US) !in keep
            }
            .forEach { module.removeInputSource(it) }
    }

    private fun stripMeta(module: ApkModule) {
        module.listInputSources()
            .map { it.alias }
            .filter { it.startsWith("META-INF/") }
            .forEach { module.removeInputSource(it) }
        module.apkSignatureBlock = null
    }

    private fun sign(unsigned: File, signed: File, identity: SigningIdentity) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            identity.alias,
            identity.privateKey,
            identity.certificates,
        ).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
