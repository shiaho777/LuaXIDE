package dev.luaxide.docs

data class ApiDoc(
    val id: String,
    val category: String,
    val name: String,
    val signature: String,
    val summary: String,
    val example: String,
    val props: List<Pair<String, String>> = emptyList(),
)

object ApiDocs {
    val all: List<ApiDoc> = listOf(
        ApiDoc(
            id = "ui.app",
            category = "ui",
            name = "ui.app",
            signature = "ui.app { title?, children... }",
            summary = "应用根节点。通常作为 return 值。",
            example = """return ui.app {
  title = "我的应用",
  ui.column {
    ui.text { text = "hello" },
  },
}""",
            props = listOf("title" to "标题字符串"),
        ),
        ApiDoc(
            id = "ui.column",
            category = "ui",
            name = "ui.column",
            signature = "ui.column { spacing?, children... }",
            summary = "纵向布局容器。",
            example = """ui.column {
  spacing = 8,
  ui.text { text = "A" },
  ui.text { text = "B" },
}""",
            props = listOf("spacing" to "子项间距 (dp)"),
        ),
        ApiDoc(
            id = "ui.row",
            category = "ui",
            name = "ui.row",
            signature = "ui.row { spacing?, children... }",
            summary = "横向布局容器。",
            example = """ui.row {
  spacing = 12,
  ui.text { text = "左" },
  ui.button { text = "右" },
}""",
            props = listOf("spacing" to "子项间距 (dp)"),
        ),
        ApiDoc(
            id = "ui.text",
            category = "ui",
            name = "ui.text",
            signature = "ui.text { text, size?, font? }",
            summary = "文本。font 指向工程内字体文件路径。",
            example = """ui.text {
  text = "你好",
  size = 18,
  font = "assets/fonts/demo.ttf",
}""",
            props = listOf(
                "text" to "显示文本",
                "size" to "字号",
                "font" to "字体相对路径 (ttf/otf)",
            ),
        ),
        ApiDoc(
            id = "ui.button",
            category = "ui",
            name = "ui.button",
            signature = "ui.button { text, onClick? }",
            summary = "按钮，onClick 为回调。",
            example = """ui.button {
  text = "点我",
  onClick = function()
    print("clicked")
  end,
}""",
            props = listOf("text" to "按钮文字", "onClick" to "点击回调"),
        ),
        ApiDoc(
            id = "ui.card",
            category = "ui",
            name = "ui.card",
            signature = "ui.card { radius?, padding?, spacing?, children... }",
            summary = "卡片容器。",
            example = """ui.card {
  ui.text { text = "内容" },
}""",
            props = listOf(
                "radius" to "圆角",
                "padding" to "内边距",
                "spacing" to "子项间距",
            ),
        ),
        ApiDoc(
            id = "ui.input",
            category = "ui",
            name = "ui.input",
            signature = "ui.input { label?, value? }",
            summary = "单行输入框。",
            example = """ui.input { label = "名字", value = "" }""",
            props = listOf("label" to "标签", "value" to "初始值"),
        ),
        ApiDoc(
            id = "ui.image",
            category = "ui",
            name = "ui.image",
            signature = "ui.image { src, size? }",
            summary = "图片。src 为工程 src/ 下相对路径，会随 APK 打包。",
            example = """ui.image {
  src = "assets/images/logo.png",
  size = 120,
}""",
            props = listOf("src" to "图片路径", "size" to "边长 (dp)"),
        ),
        ApiDoc(
            id = "ui.spacer",
            category = "ui",
            name = "ui.spacer",
            signature = "ui.spacer { size? }",
            summary = "空白间隔。",
            example = """ui.spacer { size = 16 }""",
            props = listOf("size" to "高度 (dp)"),
        ),
        ApiDoc(
            id = "ui.divider",
            category = "ui",
            name = "ui.divider",
            signature = "ui.divider {}",
            summary = "分隔线。",
            example = """ui.divider {}""",
        ),
        ApiDoc(
            id = "ui.scrollview",
            category = "ui",
            name = "ui.scrollview",
            signature = "ui.scrollview { spacing?, children... }",
            summary = "可滚动容器。",
            example = """ui.scrollview {
  ui.text { text = "很长内容…" },
}""",
            props = listOf("spacing" to "子项间距"),
        ),
        ApiDoc(
            id = "ui.list",
            category = "ui",
            name = "ui.list",
            signature = "ui.list { spacing?, children... }",
            summary = "列表容器，子项常用 ui.listitem。",
            example = """ui.list {
  ui.listitem { title = "A", subtitle = "详情" },
  ui.listitem { title = "B" },
}""",
        ),
        ApiDoc(
            id = "ui.listitem",
            category = "ui",
            name = "ui.listitem",
            signature = "ui.listitem { title?, subtitle?, onClick? }",
            summary = "列表行。",
            example = """ui.listitem {
  title = "标题",
  subtitle = "副标题",
  onClick = function() print("tap") end,
}""",
        ),
        ApiDoc(
            id = "ui.stack",
            category = "ui",
            name = "ui.stack",
            signature = "ui.stack { selected?, children page... }",
            summary = "多页面栈，用 selected 切换 page key。",
            example = """ui.stack {
  selected = "home",
  ui.page { key = "home", ui.text { text = "首页" } },
  ui.page { key = "detail", ui.text { text = "详情" } },
}""",
            props = listOf("selected" to "当前 page key"),
        ),
        ApiDoc(
            id = "ui.page",
            category = "ui",
            name = "ui.page",
            signature = "ui.page { key, children... }",
            summary = "stack 的子页面。",
            example = """ui.page {
  key = "home",
  ui.text { text = "Home" },
}""",
            props = listOf("key" to "页面标识"),
        ),
        ApiDoc(
            id = "ui.switch",
            category = "ui",
            name = "ui.switch",
            signature = "ui.switch { label?, checked?, onChange? }",
            summary = "开关。",
            example = """ui.switch {
  label = "通知",
  checked = true,
}""",
            props = listOf(
                "label" to "标签",
                "checked" to "是否开启",
                "onChange / onToggle" to "切换回调",
            ),
        ),
        ApiDoc(
            id = "print",
            category = "io",
            name = "print",
            signature = "print(...)",
            summary = "输出到终端/日志。纯程序模式会进入终端预览。",
            example = """print("hello", 1 + 2)""",
        ),
        ApiDoc(
            id = "io.read",
            category = "io",
            name = "io.read",
            signature = "io.read()",
            summary = "阻塞读取一行 stdin，需在终端回复。可取消。",
            example = """io.write("> ")
local name = io.read()
print("hi", name)""",
        ),
        ApiDoc(
            id = "io.write",
            category = "io",
            name = "io.write",
            signature = "io.write(...)",
            summary = "写入标准输出（不自动换行）。",
            example = """io.write("name: ")
local n = io.read()""",
        ),
        ApiDoc(
            id = "require",
            category = "io",
            name = "require",
            signature = "require(modname)",
            summary = "加载 src/ 下模块。点号映射为路径。",
            example = """local ui = require("ui")
local util = require("lib.util")""",
        ),
        ApiDoc(
            id = "input",
            category = "io",
            name = "input",
            signature = "input(prompt?)",
            summary = "若引擎提供，等价于提示后 io.read。",
            example = """local line = io.read()""",
        ),
        ApiDoc(
            id = "dot.run",
            category = "term",
            name = ".run",
            signature = ".run",
            summary = "终端命令：运行当前文件。",
            example = """.run""",
        ),
        ApiDoc(
            id = "dot.stop",
            category = "term",
            name = ".stop",
            signature = ".stop",
            summary = "终端命令：取消当前任务。",
            example = """.stop""",
        ),
        ApiDoc(
            id = "dot.proot",
            category = "term",
            name = ".proot",
            signature = ".proot",
            summary = "终端命令：检查/安装 proot 用户态 RootFS。",
            example = """.proot""",
        ),
        ApiDoc(
            id = "dot.clear",
            category = "term",
            name = ".clear",
            signature = ".clear",
            summary = "终端命令：清空会话。",
            example = """.clear""",
        ),
    )

    fun categories(): List<String> = all.map { it.category }.distinct()

    fun search(q: String): List<ApiDoc> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return all
        return all.filter {
            it.name.lowercase().contains(s) ||
                it.summary.lowercase().contains(s) ||
                it.signature.lowercase().contains(s) ||
                it.example.lowercase().contains(s)
        }
    }
}
