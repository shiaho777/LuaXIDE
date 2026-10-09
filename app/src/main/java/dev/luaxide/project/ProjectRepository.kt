package dev.luaxide.project

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Filesystem-backed project store. Layout:
 *
 *   filesDir/luax-projects/<id>/project.json   (metadata)
 *   filesDir/luax-projects/<id>/src/...         (user files)
 *
 * All writes are atomic: content goes to a sibling ".<name>.tmp" that is then
 * renamed over the target, so a crash mid-write can never corrupt a file.
 * Every method is main-safe (runs on [Dispatchers.IO]).
 */
class ProjectRepository(context: Context) {

    private val root: File = File(context.filesDir, "luax-projects").apply { mkdirs() }

    /** The src/ subtree is where user code lives; project.json sits beside it. */
    private fun projectDir(id: String) = File(root, id)
    private fun srcDir(id: String) = File(projectDir(id), "src")
    private fun metaFile(id: String) = File(projectDir(id), "project.json")
    private fun debugFile(id: String) = File(projectDir(id), "debug.json")

    /** Exposed for the build layer: the project's own directory (metadata + src + builds live here). */
    fun projectDirOf(id: String): File = projectDir(id)
    fun srcDirOf(id: String): File = srcDir(id)

    suspend fun listProjects(): List<Project> = withContext(Dispatchers.IO) {
        ensureSampleLibrary()
        val ids = root.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        if (ids.isEmpty()) {
            val seeded = createExamplesProject()
            return@withContext listOf(seeded)
        }
        ids.mapNotNull { readMeta(it) }.sortedByDescending { it.updatedAt }
    }

    suspend fun createProject(name: String, kind: String = "ui", language: String = "lua"): Project = withContext(Dispatchers.IO) {
        createProjectInternal(name, kind, language)
    }

    private fun createProjectInternal(name: String, kind: String = "ui", language: String = "lua"): Project {
        val id = UUID.randomUUID().toString().take(8)
        srcDir(id).mkdirs()
        val lang = dev.luaxide.lang.Language.byId(language)
        val project = Project(id = id, name = name, entryFile = lang.defaultEntry, language = lang.id)
        writeMeta(project)
        val isProgram = kind.equals("program", ignoreCase = true) || kind.equals("cli", ignoreCase = true)
        if (isProgram) {
            val pseed = when (lang) {
                dev.luaxide.lang.Language.JAVASCRIPT -> SEED_PROGRAM_JS
                dev.luaxide.lang.Language.PYTHON -> SEED_PROGRAM_PY
                else -> SEED_PROGRAM
            }
            atomicWrite(File(srcDir(id), project.entryFile), pseed)
            writeSeedFiles(id, PROGRAM_STARTER_FILES)
        } else {
            val seed = when (lang) {
                dev.luaxide.lang.Language.JAVASCRIPT -> SEED_MAIN_JS
                dev.luaxide.lang.Language.PYTHON -> SEED_MAIN_PY
                else -> SEED_MAIN
            }
            atomicWrite(File(srcDir(id), project.entryFile), seed)
            writeSeedFiles(id, UI_STARTER_FILES + UI_STARTER_FILES_EXTRA)
        }
        seedDefaultAssets(id)
        return project
    }

    private fun createExamplesProject(): Project {
        val id = UUID.randomUUID().toString().take(8)
        srcDir(id).mkdirs()
        val project = Project(id = id, name = "Examples", entryFile = "main.lua")
        writeMeta(project)
        atomicWrite(File(srcDir(id), "main.lua"), SEED_EXAMPLES_MAIN)
        writeSeedFiles(id, EXAMPLE_LIBRARY_FILES)
        seedDefaultAssets(id)
        return project
    }

    private fun ensureSampleLibrary() {
        val marker = File(root, ".samples-v6")
        if (marker.exists()) return
        val examples = root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> readMeta(dir.name)?.let { it to dir } }
            ?.firstOrNull { it.first.name == "Examples" }
        if (examples == null) {
            createExamplesProject()
        } else {
            // v6: Lua samples are HTML pages. Overwrite the library so old
            // installs drop require("ui"), which the engine now rejects.
            overwriteSeedFiles(examples.first.id, EXAMPLE_LIBRARY_FILES)
            atomicWrite(File(srcDir(examples.first.id), "main.lua"), SEED_EXAMPLES_MAIN)
        }
        seedDefaultAssets(examples?.first?.id ?: root.listFiles { f -> f.isDirectory }?.firstOrNull()?.name)
        marker.writeText("samples-v6")
        File(root, ".samples-v5").delete()
    }

    /** Overwrite seeded files even when they exist (versioned sample fixes). */
    private fun overwriteSeedFiles(id: String, files: Map<String, String>) {
        val base = srcDir(id)
        for ((rel, content) in files) {
            atomicWrite(File(base, rel), content)
        }
    }


    private fun seedDefaultAssets(id: String?) {
        if (id.isNullOrBlank()) return
        val logo = File(srcDir(id), "assets/images/logo.png")
        if (!logo.exists()) {
            logo.parentFile?.mkdirs()
            logo.writeBytes(tinyPng(0xFF, 0x5B, 0x8D, 0xEF))
        }
        val note = File(srcDir(id), "assets/README.txt")
        if (!note.exists()) {
            atomicWrite(
                note,
                "Put images under assets/images/ and fonts under assets/fonts/.\n" +
                    "Lua pages: <img src=\"assets/images/logo.png\"> and CSS @font-face.\n" +
                    "JavaScript / Python: ui.image { src = \"assets/images/logo.png\" }\n" +
                    "and ui.text { font = \"assets/fonts/Your.ttf\" }.\n" +
                    "All files under src/ ship inside the APK as assets/lua/.\n",
            )
        }
    }

    private fun tinyPng(r: Int, g: Int, b: Int, a: Int = 255): ByteArray {
        val raw = byteArrayOf(0, r.toByte(), g.toByte(), b.toByte(), a.toByte())
        fun crc(data: ByteArray): Int {
            var c = -1
            for (x in data) {
                c = c xor (x.toInt() and 0xff)
                repeat(8) {
                    c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
                }
            }
            return c.inv()
        }
        fun chunk(type: String, data: ByteArray): ByteArray {
            val typeB = type.toByteArray(Charsets.US_ASCII)
            val len = data.size
            val body = typeB + data
            val c = crc(body)
            return byteArrayOf(
                (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
            ) + body + byteArrayOf(
                (c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte(),
            )
        }
        val sig = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val ihdr = byteArrayOf(
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
        )
        // compress raw with no compression zlib
        val deflated = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION).run {
            setInput(raw)
            finish()
            val out = ByteArray(64)
            val n = deflate(out)
            end()
            out.copyOf(n)
        }
        return sig + chunk("IHDR", ihdr) + chunk("IDAT", deflated) + chunk("IEND", byteArrayOf())
    }

    private fun writeSeedFiles(id: String, files: Map<String, String>) {
        val base = srcDir(id)
        for ((rel, content) in files) {
            val target = File(base, rel)
            if (!target.exists()) {
                atomicWrite(target, content)
            }
        }
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        projectDir(id).deleteRecursively()
    }

    suspend fun renameProject(id: String, newName: String): Project = withContext(Dispatchers.IO) {
        val name = newName.trim()
        require(name.isNotEmpty()) { "project name is empty" }
        val meta = readMeta(id) ?: error("project not found")
        val updated = meta.copy(name = name, updatedAt = System.currentTimeMillis())
        writeMeta(updated)
        updated
    }

    suspend fun setEntryFile(id: String, entryFile: String): Project = withContext(Dispatchers.IO) {
        val entry = entryFile.trim().trim('/').replace('\\', '/')
        require(entry.isNotEmpty()) { "entry is empty" }
        require(".." !in entry.split('/')) { "invalid entry" }
        val meta = readMeta(id) ?: error("project not found")
        require(File(srcDir(id), entry).isFile) { "file not found: $entry" }
        val updated = meta.copy(entryFile = entry, updatedAt = System.currentTimeMillis())
        writeMeta(updated)
        updated
    }

    suspend fun duplicateProject(id: String, newName: String? = null): Project = withContext(Dispatchers.IO) {
        val src = readMeta(id) ?: error("project not found")
        val name = (newName?.trim()?.takeIf { it.isNotEmpty() } ?: "${src.name} copy")
        val copy = createProjectInternal(name)
        val from = srcDir(id)
        val to = srcDir(copy.id)
        to.deleteRecursively()
        from.copyRecursively(to, overwrite = true)
        val entry = if (File(to, src.entryFile).isFile) src.entryFile else copy.entryFile
        val updated = copy.copy(entryFile = entry, updatedAt = System.currentTimeMillis())
        writeMeta(updated)
        updated
    }

    suspend fun countFiles(id: String): Int = withContext(Dispatchers.IO) {
        val base = srcDir(id)
        if (!base.isDirectory) return@withContext 0
        base.walkTopDown().count { it.isFile }
    }

    /** Build the file tree for a project's src/ directory. */
    suspend fun loadTree(id: String): FileNode = withContext(Dispatchers.IO) {
        val base = srcDir(id)
        buildNode(base, base)
    }

    private fun buildNode(file: File, base: File): FileNode {
        val rel = file.toRelativeString(base).replace(File.separatorChar, '/')
        val kind = FileKind.of(file)
        val children = if (file.isDirectory) {
            file.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.map { buildNode(it, base) }
                .orEmpty()
        } else {
            emptyList()
        }
        return FileNode(
            name = if (rel.isEmpty()) "src" else file.name,
            relPath = rel,
            kind = kind,
            children = children,
        )
    }

    suspend fun readFile(id: String, relPath: String): String = withContext(Dispatchers.IO) {
        val f = File(srcDir(id), relPath)
        if (f.isFile) f.readText() else ""
    }

    
    suspend fun writeBinary(id: String, relPath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val clean = relPath.trim().trimStart('/')
        require(clean.isNotEmpty()) { "path is empty" }
        require(".." !in clean.split('/')) { "invalid path" }
        val f = File(srcDir(id), clean)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
        touch(id)
    }

    suspend fun absoluteSrcFile(id: String, relPath: String): File = withContext(Dispatchers.IO) {
        File(srcDir(id), relPath.trim().trimStart('/'))
    }

    fun absoluteSrcFileSync(id: String, relPath: String): File =
        File(srcDir(id), relPath.trim().trimStart('/'))

    suspend fun writeFile(id: String, relPath: String, content: String) = withContext(Dispatchers.IO) {
        val f = File(srcDir(id), relPath)
        f.parentFile?.mkdirs()
        atomicWrite(f, content)
        touch(id)
    }

    /** Create a new empty file (or with [content]); returns its relPath. Fails if it exists. */
    suspend fun newFile(id: String, relPath: String, content: String = ""): String =
        withContext(Dispatchers.IO) {
            val clean = relPath.trim().trim('/').replace('\\', '/')
            require(clean.isNotEmpty()) { "path is empty" }
            require(".." !in clean.split('/')) { "invalid path" }
            val f = File(srcDir(id), clean)
            f.parentFile?.mkdirs()
            require(!f.exists()) { "already exists: $clean" }
            atomicWrite(f, content)
            touch(id)
            clean
        }

    /** Create a new directory; returns its relPath. No-op if it already exists. */
    suspend fun newFolder(id: String, relPath: String): String = withContext(Dispatchers.IO) {
        val clean = relPath.trim().trim('/').replace('\\', '/')
        require(clean.isNotEmpty()) { "path is empty" }
        require(".." !in clean.split('/')) { "invalid path" }
        val f = File(srcDir(id), clean)
        require(!f.exists()) { "already exists: $clean" }
        require(f.mkdirs()) { "cannot create folder" }
        touch(id)
        clean
    }

    /** True if a file or directory exists at [relPath]. */
    suspend fun exists(id: String, relPath: String): Boolean = withContext(Dispatchers.IO) {
        File(srcDir(id), relPath).exists()
    }

    suspend fun rename(id: String, relPath: String, newName: String): String =
        withContext(Dispatchers.IO) {
            val leaf = newName.trim().trim('/').substringAfterLast('/')
            require(leaf.isNotEmpty()) { "name is empty" }
            require(!leaf.contains('/') && !leaf.contains('\\')) { "invalid name" }
            require(leaf != "." && leaf != "..") { "invalid name" }
            val f = File(srcDir(id), relPath)
            require(f.exists()) { "not found: $relPath" }
            val target = File(f.parentFile, leaf)
            if (target.canonicalPath == f.canonicalPath) {
                return@withContext relPath
            }
            require(!target.exists()) { "already exists: $leaf" }
            require(f.renameTo(target)) { "rename failed" }
            touch(id)
            target.toRelativeString(srcDir(id)).replace(File.separatorChar, '/')
        }

    suspend fun delete(id: String, relPath: String) = withContext(Dispatchers.IO) {
        File(srcDir(id), relPath).deleteRecursively()
        touch(id)
    }


    data class StoredBreakpoint(
        val condition: String = "",
        val logMessage: String = "",
        val logOnly: Boolean = false,
    )

    data class DebugSettings(
        val breakOnError: Boolean = true,
        val panelOpen: Boolean = false,
    )

    suspend fun loadBreakpoints(id: String): Map<String, Map<Int, StoredBreakpoint>> = withContext(Dispatchers.IO) {
        val f = debugFile(id)
        if (!f.isFile) return@withContext emptyMap()
        runCatching {
            val root = JSONObject(f.readText())
            val files = root.optJSONObject("breakpoints") ?: return@withContext emptyMap()
            buildMap {
                val keys = files.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    val raw = files.opt(path) ?: continue
                    val entry = linkedMapOf<Int, StoredBreakpoint>()
                    when (raw) {
                        is JSONArray -> {
                            for (i in 0 until raw.length()) {
                                val line = raw.optInt(i, -1)
                                if (line > 0) entry[line] = StoredBreakpoint()
                            }
                        }
                        is JSONObject -> {
                            val lk = raw.keys()
                            while (lk.hasNext()) {
                                val key = lk.next()
                                val line = key.toIntOrNull() ?: continue
                                if (line <= 0) continue
                                val v = raw.opt(key)
                                entry[line] = when (v) {
                                    is JSONObject -> StoredBreakpoint(
                                        condition = v.optString("condition", v.optString("cond", "")),
                                        logMessage = v.optString("log", ""),
                                        logOnly = v.optBoolean("logOnly", false),
                                    )
                                    is String -> StoredBreakpoint(condition = v)
                                    else -> StoredBreakpoint()
                                }
                            }
                        }
                    }
                    if (entry.isNotEmpty()) put(path, entry)
                }
            }
        }.getOrDefault(emptyMap())
    }

    suspend fun saveBreakpoints(id: String, map: Map<String, Map<Int, StoredBreakpoint>>) = withContext(Dispatchers.IO) {
        val f = debugFile(id)
        val root = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
        val files = JSONObject()
        map.forEach { (path, lines) ->
            if (lines.isEmpty()) return@forEach
            val o = JSONObject()
            lines.toSortedMap().forEach { (line, bp) ->
                val item = JSONObject()
                    .put("condition", bp.condition)
                    .put("log", bp.logMessage)
                    .put("logOnly", bp.logOnly)
                o.put(line.toString(), item)
            }
            files.put(path, o)
        }
        if (files.length() == 0) root.remove("breakpoints") else root.put("breakpoints", files)
        writeDebugRoot(f, root)
    }

    suspend fun loadDebugSettings(id: String): DebugSettings = withContext(Dispatchers.IO) {
        val f = debugFile(id)
        if (!f.isFile) return@withContext DebugSettings()
        runCatching {
            val root = JSONObject(f.readText())
            val s = root.optJSONObject("settings") ?: return@withContext DebugSettings()
            DebugSettings(
                breakOnError = s.optBoolean("breakOnError", true),
                panelOpen = s.optBoolean("panelOpen", false),
            )
        }.getOrDefault(DebugSettings())
    }

    suspend fun saveDebugSettings(id: String, settings: DebugSettings) = withContext(Dispatchers.IO) {
        val f = debugFile(id)
        val root = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
        root.put(
            "settings",
            JSONObject()
                .put("breakOnError", settings.breakOnError)
                .put("panelOpen", settings.panelOpen),
        )
        writeDebugRoot(f, root)
    }

    private fun writeDebugRoot(f: File, root: JSONObject) {
        val hasBp = root.optJSONObject("breakpoints")?.let { it.length() > 0 } == true
        val hasWatches = root.optJSONArray("watches")?.length()?.let { it > 0 } == true
        val hasSettings = root.optJSONObject("settings") != null
        if (!hasBp && !hasWatches && !hasSettings) {
            f.delete()
        } else {
            atomicWrite(f, root.toString())
        }
    }


    suspend fun loadWatches(id: String): List<String> = withContext(Dispatchers.IO) {
        val f = debugFile(id)
        if (!f.isFile) return@withContext emptyList()
        runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("watches") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveWatches(id: String, watches: List<String>) = withContext(Dispatchers.IO) {
        val f = debugFile(id)
        val root = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
        val arr = JSONArray()
        watches.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { arr.put(it) }
        if (arr.length() == 0) root.remove("watches") else root.put("watches", arr)
        writeDebugRoot(f, root)
    }

    // ---- metadata ----

    private fun readMeta(id: String): Project? {
        val f = metaFile(id)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            Project(
                id = o.optString("id", id),
                name = o.optString("name", "untitled"),
                entryFile = o.optString("entryFile", "main.lua"),
                language = o.optString("language", "lua"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                schema = o.optInt("schema", Project.SCHEMA_VERSION),
            )
        }.getOrNull()
    }

    private fun writeMeta(project: Project) {
        val o = JSONObject()
            .put("id", project.id)
            .put("name", project.name)
            .put("entryFile", project.entryFile)
            .put("language", project.language)
            .put("createdAt", project.createdAt)
            .put("updatedAt", project.updatedAt)
            .put("schema", project.schema)
        atomicWrite(metaFile(project.id), o.toString(2))
    }

    private fun touch(id: String) {
        readMeta(id)?.let { writeMeta(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    // ---- atomic write ----

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail across some FS states; fall back to copy+delete.
            target.writeText(content)
            tmp.delete()
        }
    }

    companion object {
        private val SEED_PROGRAM = """
print("hello from luax terminal")
print("type into the prompt; io.read() blocks until you send")
print("use the stop button to cancel a running task")

io.write("your name: ")
local name = io.read()
print("hi,", name)

local sum = 0
for i = 1, 5 do
  sum = sum + i
  print("step", i, "sum", sum)
end
print("done")
""".trimIndent()

        private val SEED_MAIN = """
-- The page is index.html. This file is the logic.
local html = require("html")
local count = 0

html.on("plus", "click", function()
  count = count + 1
  html.setText("count", "taps: " .. count)
end)

html.setText("count", "taps: 0")
""".trimIndent()

        private val PAGE_CSS = """
:root { color-scheme: light dark; }
body { font-family: system-ui, sans-serif; margin: 0; padding: 20px; }
h1 { font-size: 22px; margin: 0 0 8px; }
p { margin: 8px 0; }
button, input { font: inherit; }
button { padding: 8px 14px; margin: 4px 8px 4px 0; }
input { padding: 8px; width: min(100%, 320px); }
.card { border: 1px solid rgba(128,128,128,.45); border-radius: 12px; padding: 12px; margin-top: 12px; }
.muted { opacity: .72; font-size: 13px; }
.hidden { display: none; }
#board { font-family: ui-monospace, monospace; font-size: 16px; line-height: 1.25; }
""".trimIndent()

        private val SEED_MAIN_JS = """
var count = 0;

function page() {
  return ui.app({ key: "home", title: "My app" },
    ui.column({ key: "body", spacing: 12 },
      ui.text({ key: "hello", text: "Hello, QuickJS!", size: 20 }),
      ui.text({ key: "count", text: "count = " + count, size: 16 }),
      ui.button({
        key: "tap",
        text: "+1",
        onClick: function () {
          count++;
          print("count " + count);
          return page();
        }
      })
    )
  );
}

return page();
""".trimIndent()

        private val SEED_MAIN_PY = """
count = 0

# Canonical dynamic-UI pattern (same as the Lua/JS seeds): return a view()
# function so the engine re-renders after every event.
def bump():
    global count
    count += 1

def view():
    return ui.app({"title": "My app"},
        ui.column({"spacing": 12},
            ui.text({"text": "Hello, LuaX!", "size": 20}),
            ui.text({"text": "taps: " + str(count), "size": 16}),
            ui.button({"text": "+1", "onClick": bump})))

return view()
""".trimIndent()

        private val SEED_PROGRAM_PY = """
print("hello from micropython")

total = 0
for i in range(1, 6):
    total += i
    print("step", i, "sum", total)
print("done")
""".trimIndent()

        private val SEED_PROGRAM_JS = """
print("hello from quickjs terminal");

var sum = 0;
for (var i = 1; i <= 5; i++) {
  sum += i;
  print("step", i, "sum", sum);
}
print("done");
""".trimIndent()

        private val SEED_EXAMPLES_MAIN = """
local html = require("html")

html.setText("title", "LuaXIDE examples")
html.setText("lead", "Open a file in the sidebar, then Run.")
html.setText("tip", "require(\"lib.util\") loads src/lib/util.lua. Program scripts print to the console. Pages live in HTML.")
""".trimIndent()

        private val UI_STARTER_FILES_EXTRA = mapOf(
            "pages/image_demo.lua" to """
-- Page: pages/image_demo.html. The picture is a file in this project.
print("image demo")
""".trimIndent(),
            "pages/image_demo.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Image demo</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1>工程图片预览</h1>
  <img src="../assets/images/logo.png" alt="logo" width="120">
  <p class="muted">src = assets/images/logo.png</p>
</body>
</html>
""".trimIndent(),
        )

        private val UI_STARTER_FILES = mapOf(
            "index.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>My app</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <h1>Hello, LuaX!</h1>
  <p id="count">taps: 0</p>
  <button id="plus" type="button">+1</button>
</body>
</html>
""".trimIndent(),
            "style.css" to PAGE_CSS,
            "ui/counter.lua" to """
local html = require("html")
local count = 0

local function show()
  html.setText("value", tostring(count))
end

html.on("inc", "click", function()
  count = count + 1
  show()
end)
html.on("reset", "click", function()
  count = 0
  show()
end)
show()
""".trimIndent(),
            "ui/counter.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Counter</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1>Counter demo</h1>
  <p id="value">0</p>
  <button id="inc" type="button">+1</button>
  <button id="reset" type="button">reset</button>
</body>
</html>
""".trimIndent(),
            "ui/layout.lua" to """
local html = require("html")
html.on("ok", "click", function() print("ok") end)
""".trimIndent(),
            "ui/layout.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Layout</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1>Row + column</h1>
  <p>left <span class="muted">|</span> right</p>
  <div class="card">
    <p>Card body</p>
    <button id="ok" type="button">ok</button>
  </div>
</body>
</html>
""".trimIndent(),
        )

        private val PROGRAM_STARTER_FILES = mapOf(
            "program/hello.lua" to """
print("hello, luax!")
print(1 + 2)
print("a" .. "b")
print(type(1), type("x"), type({}), type(nil))
""".trimIndent(),
            "program/stdin.lua" to """
io.write("your name: ")
local name = io.read()
print("hi,", name)
io.write("favorite number: ")
local n = tonumber(io.read()) or 0
print("n * 2 =", n * 2)
""".trimIndent(),
        )

        private val EXAMPLE_LIBRARY_FILES = mapOf(
"games/snake.lua" to """
            -- Snake, pure Lua. The page is games/snake.html.
            -- No math.random: a tiny LCG. No engine timer: tap Step to advance.
            -- Board cells:  ◉ head   ● body   ★ food   · empty
            local W, H = 12, 12
            local html = require("html")

            -- ---- minimal PRNG (MINSTD; 48271 * 2^31-1 < 2^53 so doubles stay exact) ----
            local seed = (os.clock() * 1000000) % 2147483647
            local function rnd(n)
              seed = (seed * 48271) % 2147483647
              return (seed % n) + 1
            end

            -- ---- game state ----
            local snake = {}   -- array of {x=,y=}, head at [1]
            local dir = { x = 1, y = 0 }
            local food = nil   -- {x=,y=} or nil when the board is full (you win)
            local score = 0
            local over = false
            local paused = false

            local function occupied(x, y)
              for i = 1, #snake do
                local s = snake[i]
                if s.x == x and s.y == y then return true end
              end
              return false
            end

            local function spawnFood()
              local tries = 0
              while tries < 300 do
                tries = tries + 1
                local x, y = rnd(W), rnd(H)
                if not occupied(x, y) then food = { x = x, y = y } return end
              end
              food = nil -- board full -> win state
            end

            local function reset()
              snake = { { x = 3, y = 6 }, { x = 2, y = 6 }, { x = 1, y = 6 } }
              dir = { x = 1, y = 0 }
              score = 0
              over = false
              paused = false
              spawnFood()
            end

            -- one tick: the Step button calls this. There is no engine timer.
            local function step()
              if over or paused then return end
              local h = snake[1]
              local nx, ny = h.x + dir.x, h.y + dir.y
              if nx < 1 or nx > W or ny < 1 or ny > H then over = true return end -- wall
              local grows = food ~= nil and nx == food.x and ny == food.y
              for i = 1, #snake do
                if snake[i].x == nx and snake[i].y == ny then
                  -- the tail cell frees up this tick unless we grow into it
                  if not (not grows and i == #snake) then over = true return end
                end
              end
              table.insert(snake, 1, { x = nx, y = ny })
              if grows then
                score = score + 1
                spawnFood()
              else
                table.remove(snake)
              end
            end

            local function turn(dx, dy)
              if over or paused then return end
              local h, n = snake[1], snake[2]
              if n and n.x == h.x + dx and n.y == h.y + dy then return end -- no reversing
              dir = { x = dx, y = dy }
            end

            local function cell(x, y)
              if food and x == food.x and y == food.y then return "★", "#EF5350" end
              for i = 1, #snake do
                local s = snake[i]
                if s.x == x and s.y == y then
                  if i == 1 then return "◉", "#66BB6A" end
                  return "●", "#26A69A"
                end
              end
              return "·", "#78909C"
            end

            local function draw()
              local lines = {}
              for y = 1, H do
                local cells = {}
                for x = 1, W do
                  local ch, col = cell(x, y)
                  cells[#cells + 1] = '<span style="color:' .. col .. '">' .. ch .. '</span>'
                end
                lines[#lines + 1] = table.concat(cells)
              end
              html.setHtml("board", table.concat(lines, "<br>"))
              local status = "Score " .. score
              if food == nil and not over then status = status .. "   ·   you win" end
              if over then status = status .. "   ·   GAME OVER" end
              if paused then status = status .. "   ·   paused" end
              html.setText("status", status)
              html.setText("pause", paused and "Play" or "Pause")
            end

            html.on("left", "click", function() turn(-1, 0) end)
            html.on("up", "click", function() turn(0, -1) end)
            html.on("down", "click", function() turn(0, 1) end)
            html.on("right", "click", function() turn(1, 0) end)
            html.on("tick", "click", function() step(); draw() end)
            html.on("pause", "click", function() paused = not paused; draw() end)
            html.on("restart", "click", function() reset(); draw() end)

            reset()
            draw()
""".trimIndent(),
            "ui/counter.lua" to """
local html = require("html")
local count = 0

local function show()
  html.setText("value", tostring(count))
end

html.on("inc", "click", function()
  count = count + 1
  show()
end)
html.on("reset", "click", function()
  count = 0
  show()
end)
show()
""".trimIndent(),
            "ui/counter.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Counter</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1>Counter demo</h1>
  <p id="value">0</p>
  <button id="inc" type="button">+1</button>
  <button id="reset" type="button">reset</button>
</body>
</html>
""".trimIndent(),
            "ui/layout.lua" to """
local html = require("html")
html.on("ok", "click", function() print("layout ok") end)
""".trimIndent(),
            "ui/layout.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Layout</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1>Nested layout</h1>
  <p>A <span class="muted">|</span> B <span class="muted">|</span> C</p>
  <div class="card">
    <p>Card</p>
    <p class="muted">HTML and CSS, in the project.</p>
    <button id="ok" type="button">print</button>
  </div>
</body>
</html>
""".trimIndent(),
            "ui/form.lua" to """
local html = require("html")
local name = ""

-- input delivers the field text. change/submit do too; this sample uses input.
html.on("name", "input", function(text)
  name = text
  html.setText("echo", "hello, " .. (name == "" and "?" or name))
end)

html.setText("echo", "hello, ?")
""".trimIndent(),
            "ui/form.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Form</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1>Simple form</h1>
  <p><label>your name <input id="name" type="text"></label></p>
  <p id="echo">hello, ?</p>
</body>
</html>
""".trimIndent(),
            "program/hello.lua" to """
print("hello, luax!")
print(1 + 2 * 3)
print("a" .. "b", #"luax")
print(nil, true, false)
print(type(1), type("x"), type({}), type(print))
""".trimIndent(),
            "program/stdin.lua" to """
print("blocking stdin demo")
print("send a line from the terminal input")
io.write("your name: ")
local name = io.read()
print("hi,", name)
io.write("number: ")
local n = tonumber(io.read()) or 0
print("square =", n * n)
print("done")
""".trimIndent(),
            "program/fib.lua" to """
local function fib(n)
  if n < 2 then return n end
  return fib(n - 1) + fib(n - 2)
end

for i = 0, 12 do
  print("fib", i, "=", fib(i))
end
""".trimIndent(),
            "program/tables.lua" to """
local t = { name = "luax", version = 1, tags = { "lua", "android", "ide" } }
print("name", t.name)
print("version", t.version)
for i, v in ipairs(t.tags) do
  print("tag", i, v)
end

local sum = 0
for i = 1, 10 do sum = sum + i end
print("sum 1..10 =", sum)
""".trimIndent(),
            "program/math_loop.lua" to """
local n = 1
for i = 1, 8 do
  n = n * 2
  print("2^" .. i, "=", n)
end

local acc = 0
for i = 1, 5 do
  acc = acc + i * i
  print("i", i, "acc", acc)
end
print("finished")
""".trimIndent(),
            "lib/util.lua" to """
local M = {}

function M.greet(name)
  name = name or "world"
  return "hello, " .. name
end

function M.sum(a, b)
  return (a or 0) + (b or 0)
end

function M.map(list, fn)
  local out = {}
  for i, v in ipairs(list) do
    out[i] = fn(v)
  end
  return out
end

return M
""".trimIndent(),
            "program/modules.lua" to """
local util = require("lib.util")
print(util.greet("luax"))
print("1+2 =", util.sum(1, 2))
local tags = util.map({ "a", "b", "c" }, function(x) return x .. "!" end)
for i, v in ipairs(tags) do
  print(i, v)
end
print("require ok")
""".trimIndent(),
            "ui/list_stack.lua" to """
local html = require("html")
local page = "home"
local compact = false

local function show()
  for _, id in ipairs({ "home", "detail", "settings" }) do
    if id == page then html.removeClass(id, "hidden") else html.addClass(id, "hidden") end
  end
  html.setText("compact-state", "compact mode is " .. (compact and "on" or "off"))
end

html.on("compact", "change", function(v)
  compact = v == "true"
  show()
end)
html.on("open-detail", "click", function() page = "detail"; show() end)
html.on("open-settings", "click", function() page = "settings"; show() end)
html.on("back-home", "click", function() page = "home"; show() end)
html.on("back-settings", "click", function() page = "home"; show() end)
show()
""".trimIndent(),
            "ui/list_stack.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>List + pages</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1>List + pages</h1>
  <p><label><input id="compact" type="checkbox"> compact mode</label></p>
  <section id="home">
    <h2>Home</h2>
    <p><button id="open-detail" type="button">Open detail</button></p>
    <p><button id="open-settings" type="button">Settings</button></p>
  </section>
  <section id="detail" class="hidden">
    <h2>Detail</h2>
    <p id="compact-state"></p>
    <button id="back-home" type="button">Back home</button>
  </section>
  <section id="settings" class="hidden">
    <h2>Settings</h2>
    <p><label>display name <input id="display" type="text" value="LuaX"></label></p>
    <button id="back-settings" type="button">Back home</button>
  </section>
</body>
</html>
""".trimIndent(),
            "app/main.lua" to """
local html = require("html")
local util = require("lib.util")
local state = require("app.state")

local function show()
  html.setText("hi", util.greet(state.user))
  html.setText("n1s", state.selected == 1 and "selected" or "tap to select")
  html.setText("n2s", state.selected == 2 and "selected" or "tap to select")
  html.setText("cnt", "notes: " .. #state.notes)
end

html.on("n1", "click", function() state.selected = 1; show() end)
html.on("n2", "click", function() state.selected = 2; show() end)
show()
""".trimIndent(),
            "app/main.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Notes</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1 id="hi">hello</h1>
  <p class="muted">Multi-file app · require modules</p>
  <p><button id="n1" type="button">First note</button></p>
  <p id="n1s" class="muted"></p>
  <p><button id="n2" type="button">Second note</button></p>
  <p id="n2s" class="muted"></p>
  <p id="cnt"></p>
</body>
</html>
""".trimIndent(),
            "app/state.lua" to """
local M = {
  user = "friend",
  selected = 0,
  notes = { "First note", "Second note" },
}

return M
""".trimIndent(),
            "index.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>LuaX Examples</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <h1 id="title">LuaXIDE examples</h1>
  <p id="lead"></p>
  <ul>
    <li>ui/ · counter, layout, form, pages</li>
    <li>program/ · terminal and stdin</li>
    <li>lib/ · local require modules</li>
    <li>app/ · multi-file sample</li>
    <li>games/ · snake</li>
  </ul>
  <div class="card"><p id="tip"></p></div>
</body>
</html>
""".trimIndent(),
            "style.css" to PAGE_CSS,
            "games/snake.html" to """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Snake</title>
  <link rel="stylesheet" href="../style.css">
</head>
<body>
  <h1>Snake</h1>
  <p id="status"></p>
  <p id="board"></p>
  <p>
    <button id="left" type="button">◀</button>
    <button id="up" type="button">▲</button>
    <button id="down" type="button">▼</button>
    <button id="right" type="button">▶</button>
  </p>
  <p>
    <button id="tick" type="button">Step</button>
    <button id="pause" type="button">Pause</button>
    <button id="restart" type="button">Restart</button>
  </p>
  <p class="muted">No engine timer. Step advances one tick.</p>
</body>
</html>
""".trimIndent(),
            "docs/API.txt" to """
LuaX local modules
------------------
require("html")         page operations (LuaX)
require("lib.util")     loads src/lib/util.lua
require("app.state")    loads src/app/state.lua

Path rules (project src root):
  a.b  -> a/b.lua  or  a/b/init.lua

HTML host:
  html.on(id, event, fn)
  html.setText / setHtml / setAttr / setValue
  html.addClass / removeClass
  events: click, input, change, submit

The page is index.html, or <script>.html beside the Lua file.
require("ui") is an error.

Program mode:
  print, io.read (blocking), input(), .run .stop .proot
""".trimIndent(),
                        "README.txt" to """
LuaXIDE example library
=======================

ui/
  counter.lua     button + state (counter.html)
  layout.lua      HTML layout (layout.html)
  form.lua        text field (form.html)
  list_stack.lua  pages + checkbox (list_stack.html)

program/
  hello.lua       print / types
  stdin.lua       blocking io.read()
  modules.lua     require("lib.util")
  fib.lua         recursion
  tables.lua      tables + loops
  math_loop.lua   loops + math

lib/
  util.lua        shared module for require()

games/
  snake.lua       step-driven snake (snake.html)

app/
  main.lua        multi-file sample app (main.html)
  state.lua       shared app state module

docs/
  API.txt         quick API map

Open a file, then Run.
Program scripts open the terminal face.
No device root required.
""".trimIndent(),
        )
    }
}


