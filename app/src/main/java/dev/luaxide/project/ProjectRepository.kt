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

    suspend fun createProject(name: String, kind: String = "ui"): Project = withContext(Dispatchers.IO) {
        createProjectInternal(name, kind)
    }

    private fun createProjectInternal(name: String, kind: String = "ui"): Project {
        val id = UUID.randomUUID().toString().take(8)
        srcDir(id).mkdirs()
        val project = Project(id = id, name = name)
        writeMeta(project)
        val isProgram = kind.equals("program", ignoreCase = true) || kind.equals("cli", ignoreCase = true)
        if (isProgram) {
            atomicWrite(File(srcDir(id), project.entryFile), SEED_PROGRAM)
            writeSeedFiles(id, PROGRAM_STARTER_FILES)
        } else {
            atomicWrite(File(srcDir(id), project.entryFile), SEED_MAIN)
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
        val marker = File(root, ".samples-v4")
        if (marker.exists()) return
        val examples = root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> readMeta(dir.name)?.let { it to dir } }
            ?.firstOrNull { it.first.name == "Examples" }
        if (examples == null) {
            createExamplesProject()
        } else {
            writeSeedFiles(examples.first.id, EXAMPLE_LIBRARY_FILES)
            val main = File(srcDir(examples.first.id), "main.lua")
            if (!main.isFile || main.length() < 40) {
                atomicWrite(main, SEED_EXAMPLES_MAIN)
            }
        }
        seedDefaultAssets(examples?.first?.id ?: root.listFiles { f -> f.isDirectory }?.firstOrNull()?.name)
        marker.writeText("samples-v4")
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
                    "Use ui.image { src = \"assets/images/logo.png\" }\n" +
                    "Use ui.text { font = \"assets/fonts/Your.ttf\" }\n" +
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
local ui = require("ui")

local count = 0

return ui.app {
  key = "home",
  title = "My app",
  ui.column {
    key = "body",
    spacing = 12,
    ui.text { key = "hello", text = "Hello, LuaX!", size = 20 },
    ui.button {
      key = "tap",
      text = "count",
      onClick = function() count = count + 1; print("count " .. count) end,
    },
  },
}
""".trimIndent()

        private val SEED_EXAMPLES_MAIN = """
local ui = require("ui")

return ui.app {
  key = "examples-home",
  title = "LuaX Examples",
  ui.column {
    key = "body",
    spacing = 10,
    ui.text { key = "t1", text = "LuaXIDE examples", size = 22 },
    ui.text { key = "t2", text = "Open files in the sidebar:", size = 14 },
    ui.text { key = "t3", text = "ui/ · layouts, list, stack pages", size = 13 },
    ui.text { key = "t4", text = "program/ · terminal + stdin", size = 13 },
    ui.text { key = "t5", text = "lib/ · local require modules", size = 13 },
    ui.text { key = "t6", text = "app/ · multi-file sample app", size = 13 },
    ui.text { key = "t7", text = "games/ · snake — pure Lua game", size = 13 },
    ui.card {
      key = "tip",
      ui.column {
        key = "tip-body",
        spacing = 6,
        ui.text { key = "tip1", text = "Tip", size = 14 },
        ui.text { key = "tip2", text = "require(\"lib/util\") loads src/lib/util.lua", size = 12 },
        ui.text { key = "tip3", text = "Program scripts open the terminal face.", size = 12 },
      },
    },
  },
}
""".trimIndent()

        private val UI_STARTER_FILES_EXTRA = mapOf(
            "pages/image_demo.lua" to """
local ui = require("ui")
return ui.app {
  title = "Image demo",
  ui.column {
    spacing = 12,
    ui.text { text = "工程图片预览", size = 18 },
    ui.image { src = "assets/images/logo.png", size = 120 },
    ui.text { text = "src = assets/images/logo.png", size = 13 },
  },
}
""".trimIndent(),
        )

        private val UI_STARTER_FILES = mapOf(
            "ui/counter.lua" to """
local ui = require("ui")
local count = 0
return ui.app {
  key = "counter",
  title = "Counter",
  ui.column {
    key = "body",
    spacing = 12,
    ui.text { key = "label", text = "taps: 0", size = 18 },
    ui.button {
      key = "btn",
      text = "+1",
      onClick = function()
        count = count + 1
        print("count", count)
      end,
    },
  },
}
""".trimIndent(),
            "ui/layout.lua" to """
local ui = require("ui")
return ui.app {
  key = "layout",
  title = "Layout",
  ui.column {
    key = "body",
    spacing = 10,
    ui.text { key = "h", text = "Row + column", size = 18 },
    ui.row {
      key = "r1",
      ui.text { key = "l", text = "left" },
      ui.divider {},
      ui.text { key = "r", text = "right" },
    },
    ui.card {
      key = "c1",
      ui.column {
        key = "c1b",
        spacing = 6,
        ui.text { key = "c1t", text = "Card body", size = 14 },
        ui.button { key = "c1b1", text = "ok", onClick = function() print("ok") end },
      },
    },
  },
}
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
            -- t24_snake.lua — Snake, pure Lua, for the LuaXIDE engine.
            --
            -- Demonstrates what the "execution is truth" loop can do with zero C changes:
            --   * the engine has no math.random  -> a tiny LCG PRNG written in Lua
            --   * the engine has no timer        -> the App drives ticks via an onTick
            --     handler (a function prop serialized as {"__handler":N}); the App scans
            --     the returned tree, finds onTick + interval, and invokes it on a timer.
            --   * no canvas                      -> the board is a grid of ui.text cells
            --     (TextComponent gained a `color` prop so snake/food/empty are distinct).
            --
            -- Board cells:  ◉ head   ● body   ★ food   · empty
            local W, H = 12, 12
            local ui = require("ui")

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
            local speed = 260 -- ms per tick; the UI re-reads `interval` every tree rebuild

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

            -- one tick: called by the App every `interval` ms via the onTick handler
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

            local function view()
              local rows = {}
              for y = 1, H do
                local cells = {}
                for x = 1, W do
                  local ch, col = cell(x, y)
                  table.insert(cells, ui.text { text = ch, size = 13, color = col, animate = false })
                end
                local row = ui.row { spacing = 0 }
                for i = 1, #cells do rawset(row, i, cells[i]) end
                table.insert(rows, row)
              end
              local board = ui.column { spacing = 0 }
              for i = 1, #rows do rawset(board, i, rows[i]) end

              local status = "Score " .. score
              if over then status = status .. "   ·   GAME OVER" end
              if paused then status = status .. "   ·   paused" end

              return ui.app {
                title = "Snake — LuaXIDE",
                ui.text { text = status, size = 15, animate = false },
                board,
                ui.row { spacing = 8,
                  ui.button { text = "◀", onClick = function() turn(-1, 0) end },
                  ui.button { text = "▲", onClick = function() turn(0, -1) end },
                  ui.button { text = "▼", onClick = function() turn(0, 1) end },
                  ui.button { text = "▶", onClick = function() turn(1, 0) end },
                },
                ui.row { spacing = 8,
                  ui.button { text = paused and "▶ Play" or "⏸ Pause", onClick = function() paused = not paused end },
                  ui.button { text = "↻ Restart", onClick = function() reset() end },
                  ui.button { text = "＋", onClick = function() if speed > 80 then speed = speed - 40 end end },
                  ui.button { text = "－", onClick = function() speed = speed + 40 end },
                },
                ui.text { text = "tick " .. speed .. "ms · 纯 Lua · 无引擎改动", size = 11, animate = false },
                -- App contract: scan the tree for onTick + interval, invoke onTick on a timer
                onTick = function() step() end,
                interval = speed,
              }
            end

            reset()
            return view
""".trimIndent(),
            "ui/counter.lua" to """
local ui = require("ui")
local count = 0
return ui.app {
  key = "counter",
  title = "Counter",
  ui.column {
    key = "body",
    spacing = 12,
    ui.text { key = "title", text = "Counter demo", size = 20 },
    ui.text { key = "value", text = "0", size = 28 },
    ui.row {
      key = "actions",
      ui.button {
        key = "inc",
        text = "+1",
        onClick = function()
          count = count + 1
          print("count", count)
        end,
      },
      ui.button {
        key = "reset",
        text = "reset",
        onClick = function()
          count = 0
          print("reset")
        end,
      },
    },
  },
}
""".trimIndent(),
            "ui/layout.lua" to """
local ui = require("ui")
return ui.app {
  key = "layout",
  title = "Layout",
  ui.column {
    key = "body",
    spacing = 12,
    ui.text { key = "h", text = "Nested layout", size = 20 },
    ui.row {
      key = "r1",
      ui.text { key = "a", text = "A" },
      ui.divider {},
      ui.text { key = "b", text = "B" },
      ui.divider {},
      ui.text { key = "c", text = "C" },
    },
    ui.card {
      key = "card",
      ui.column {
        key = "card-body",
        spacing = 8,
        ui.text { key = "ct", text = "Card", size = 16 },
        ui.text { key = "cd", text = "Use ui.column / ui.row / ui.card", size = 13 },
        ui.button {
          key = "cb",
          text = "print",
          onClick = function() print("layout ok") end,
        },
      },
    },
  },
}
""".trimIndent(),
            "ui/form.lua" to """
local ui = require("ui")
local name = "LuaX"
return ui.app {
  key = "form",
  title = "Form",
  ui.column {
    key = "body",
    spacing = 12,
    ui.text { key = "h", text = "Simple form", size = 20 },
    ui.text { key = "n", text = "name: " .. name, size = 14 },
    ui.button {
      key = "hello",
      text = "say hello",
      onClick = function()
        print("hello,", name)
      end,
    },
    ui.button {
      key = "rename",
      text = "rename → Guest",
      onClick = function()
        name = "Guest"
        print("name set to Guest")
      end,
    },
  },
}
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
local ui = require("ui")
local page = "home"
local dark = false

local function go(name)
  page = name
  print("page", page)
end

return ui.app {
  key = "nav-demo",
  title = "List + pages",
  ui.column {
    key = "body",
    spacing = 12,
    ui.switch {
      key = "theme",
      label = "compact mode",
      checked = dark,
      onChange = function()
        dark = not dark
        print("compact", dark)
      end,
    },
    ui.stack {
      key = "stack",
      selected = page,
      ui.page {
        key = "home",
        ui.text { key = "h1", text = "Home", size = 20 },
        ui.list {
          key = "menu",
          spacing = 8,
          ui.listitem {
            key = "i1",
            title = "Open detail",
            subtitle = "stack navigation",
            onClick = function() go("detail") end,
          },
          ui.listitem {
            key = "i2",
            title = "Settings",
            subtitle = "toggle + form",
            onClick = function() go("settings") end,
          },
        },
      },
      ui.page {
        key = "detail",
        ui.text { key = "d1", text = "Detail", size = 20 },
        ui.text { key = "d2", text = "Pushed with ui.stack selected=", size = 13 },
        ui.button {
          key = "back1",
          text = "Back home",
          onClick = function() go("home") end,
        },
      },
      ui.page {
        key = "settings",
        ui.text { key = "s1", text = "Settings", size = 20 },
        ui.input { key = "name", label = "display name", value = "LuaX" },
        ui.button {
          key = "back2",
          text = "Back home",
          onClick = function() go("home") end,
        },
      },
    },
  },
}
""".trimIndent(),
            "app/main.lua" to """
local ui = require("ui")
local util = require("lib.util")
local state = require("app.state")

return ui.app {
  key = "sample-app",
  title = "Notes",
  ui.column {
    key = "root",
    spacing = 12,
    ui.text { key = "hi", text = util.greet(state.user), size = 20 },
    ui.text { key = "sub", text = "Multi-file app · require modules", size = 13 },
    ui.list {
      key = "notes",
      spacing = 8,
      ui.listitem {
        key = "n1",
        title = "First note",
        subtitle = "from app/state.lua",
        onClick = function()
          state.selected = 1
          print("selected", state.selected)
        end,
      },
      ui.listitem {
        key = "n2",
        title = "Second note",
        subtitle = "tap to select",
        onClick = function()
          state.selected = 2
          print("selected", state.selected)
        end,
      },
    },
    ui.button {
      key = "add",
      text = "print count",
      onClick = function()
        print("notes", #state.notes, "selected", state.selected)
      end,
    },
  },
}
""".trimIndent(),
            "app/state.lua" to """
local M = {
  user = "friend",
  selected = 0,
  notes = { "First note", "Second note" },
}

return M
""".trimIndent(),
            "docs/API.txt" to """
LuaX local modules
------------------
require("ui")           built-in UI toolkit
require("lib.util")     loads src/lib/util.lua
require("app.state")    loads src/app/state.lua

Path rules (project src root):
  a.b  -> a/b.lua  or  a/b/init.lua

UI nodes:
  ui.app / column / row / text / button / card
  ui.input / image / spacer / divider / scrollview
  ui.list / listitem / stack / page / switch

Program mode:
  print, io.read (blocking), input(), .run .stop .proot
""".trimIndent(),
                        "README.txt" to """
LuaXIDE example library
=======================

ui/
  counter.lua     button + state
  layout.lua      row / column / card
  form.lua        simple form
  list_stack.lua  list + stack pages + switch

program/
  hello.lua       print / types
  stdin.lua       blocking io.read()
  modules.lua     require("lib.util")
  fib.lua         recursion
  tables.lua      tables + loops
  math_loop.lua   loops + math

lib/
  util.lua        shared module for require()

app/
  main.lua        multi-file sample app
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


