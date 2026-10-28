package dev.webviewide.project

import android.content.Context
import java.io.File
import org.json.JSONObject

data class WebProject(
    val id: String,
    val name: String,
    val entryFile: String,
    val dir: File,
) {
    val srcDir: File get() = File(dir, "src")
}

object ProjectStore {
    private const val ROOT = "web-projects"

    fun open(context: Context): WebProject {
        val root = File(context.filesDir, ROOT).apply { mkdirs() }
        val existing = root.listFiles()
            ?.filter { it.isDirectory && File(it, "project.json").isFile }
            .orEmpty()
        if (existing.isNotEmpty()) return read(existing.minBy { it.name })
        return createSeed(root)
    }

    fun listSources(project: WebProject): List<File> {
        if (!project.srcDir.isDirectory) return emptyList()
        return project.srcDir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedBy { it.path }
            .toList()
    }

    fun relative(project: WebProject, file: File): String =
        file.toRelativeString(project.srcDir).replace(File.separatorChar, '/')

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun read(dir: File): WebProject {
        val json = JSONObject(File(dir, "project.json").readText())
        return WebProject(
            id = json.getString("id"),
            name = json.getString("name"),
            entryFile = json.optString("entryFile", "index.html"),
            dir = dir,
        )
    }

    private fun createSeed(root: File): WebProject {
        val id = "home"
        val dir = File(root, id)
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "index.html").writeText(SEED_HTML)
        File(src, "styles.css").writeText(SEED_CSS)
        File(src, "main.js").writeText(SEED_JS)
        val project = WebProject(id, "首页", "index.html", dir)
        File(dir, "project.json").writeText(
            JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("entryFile", project.entryFile)
                .toString(2),
        )
        return project
    }
}

private val SEED_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>首页</title>
  <link rel="stylesheet" href="styles.css" />
</head>
<body>
  <main>
    <p class="mark">预览</p>
    <h1>写在手机上的网页</h1>
    <p>改 index.html、styles.css 或 main.js，然后点运行。</p>
    <button id="tap" type="button">点过 0 次</button>
  </main>
  <script src="main.js"></script>
</body>
</html>
""".trimIndent() + "\n"

private val SEED_CSS = """
:root {
  color-scheme: light dark;
  font-family: system-ui, sans-serif;
}
body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: #f4f7fb;
  color: #1a1c1e;
}
main { width: min(100% - 32px, 420px); }
.mark {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #185fa5;
}
h1 { font-size: 32px; line-height: 1.2; margin: 8px 0; }
p { line-height: 1.5; }
button {
  margin-top: 8px;
  border: 0;
  border-radius: 999px;
  padding: 12px 20px;
  background: #185fa5;
  color: white;
  font: inherit;
}
@media (prefers-color-scheme: dark) {
  body { background: #1a1c1e; color: #e3e2e6; }
  .mark { color: #a2c9ff; }
  button { background: #a2c9ff; color: #003258; }
}
""".trimIndent() + "\n"

private val SEED_JS = """
const button = document.querySelector("#tap");
let count = 0;
button.addEventListener("click", () => {
  count += 1;
  button.textContent = "点过 " + count + " 次";
});
""".trimIndent() + "\n"
