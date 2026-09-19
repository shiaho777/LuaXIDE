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
            signature = "ui.text { text, size?, font?, bold? } / ui.text(\"hi\")",
            summary = "文本。仅 text 支持字符串构造糖;字符串子项也转为 text。font 为工程字体路径。",
            example = """ui.text {
  text = "你好",
  size = 18,
  font = "assets/fonts/demo.ttf",
}""",
            props = listOf(
                "text" to "显示文本",
                "size" to "字号 (sp，默认 16)",
                "bold" to "粗体，默认 false",
                "font" to "字体相对路径 (ttf/otf)",
                "color" to "文字颜色 (#RRGGBB / #AARRGGBB)",
                "animate" to "false 关闭内容动画(棋盘/时钟等高频更新)",
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
            signature = "ui.input { label?, value?, onChange?, onSubmit? }",
            summary = "单行输入。onChange 每次编辑、onSubmit 键盘确认均传文本字符串。返回 view 函数以更新状态。",
            example = """local name = ""
local function view()
  return ui.app {
    ui.input { label = "名字", value = name,
      onChange = function(text) name = text end,
      onSubmit = function(text) print(text) end,
    },
    ui.text(name),
  }
end
return view""",
            props = listOf("label" to "标签", "value" to "当前文本", "onChange" to "编辑回调(文本字符串)", "onSubmit" to "确认回调(文本字符串)"),
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
            summary = "纵向滚动，必须有有限高度。嵌套时设置 height 或约束父容器；否则显示诊断，不保证任意嵌套。",
            example = """ui.scrollview {
  height = 240,
  ui.text { text = "很长内容…" },
}""",
            props = listOf("spacing" to "子项间距"),
        ),
        ApiDoc(
            id = "ui.list",
            category = "ui",
            name = "ui.list",
            signature = "ui.list { spacing?, children... }",
            summary = "普通 Column 列表，无懒加载/回收或独立滚动，子项常用 ui.listitem。",
            example = """ui.list {
  ui.listitem { title = "A", subtitle = "详情" },
  ui.listitem { title = "B" },
}""",
            props = listOf("spacing" to "子项间距"),
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
            props = listOf("title" to "标题", "subtitle" to "副标题", "onClick" to "点击回调"),
        ),
        ApiDoc(
            id = "ui.stack",
            category = "ui",
            name = "ui.stack",
            signature = "ui.stack { selected?, children page... }",
            summary = "selected 仅匹配 page.key（不匹配 id），未匹配取首个 page。key/id 仍支持节点身份。",
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
            signature = "ui.switch { label?, checked?, onToggle? }",
            summary = "开关。onToggle 在切换时触发,收到新状态字符串 \"true\"/\"false\" 作为参数。",
            example = """ui.switch {
  label = "通知",
  checked = false,
  onToggle = function(v) print("now " .. v) end,
}""",
            props = listOf(
                "label" to "标签",
                "checked" to "是否开启",
                "onToggle" to "切换回调(参数为新状态字符串)",
            ),
        ),
        ApiDoc(
            id = "ui.box", category = "ui", name = "ui.box",
            signature = "ui.box { width?, height?, children... }",
            summary = "叠放容器，子项默认左上；align 选择九宫格位置，Box 不使用 weight。",
            example = """ui.box { width = 240, height = 80, background = "#202020", radius = 8,
  ui.text { text = "Overlay", bold = true, color = "#FFFFFF", align = "bottomright" },
}""",
            props = listOf("align" to "子项: topleft/top/topright/left/center/right/bottomleft/bottom/bottomright"),
        ),
        ApiDoc(
            id = "ui.slider", category = "ui", name = "ui.slider",
            signature = "ui.slider { value?, from?, to?, step?, onChange? }",
            summary = "数值滑杆；from=0，to=1，value=from，step=0 连续。正 step 为从 from 起算的增量，不是刻度数。",
            example = """local amount = 0.4
local function view()
  return ui.app {
    ui.slider { value = amount, from = 0, to = 1, step = 0.1,
      onChange = function(value) amount = tonumber(value) end,
    },
    ui.progress { value = amount },
    ui.text { text = tostring(amount) },
  }
end
return view""",
            props = listOf("value" to "有限数值，截断并吸附到范围", "from" to "起点，默认 0", "to" to "终点，默认 1，必须大于 from", "step" to "有限非负增量，0 为连续；终点仍可到达", "onChange" to "数值字符串回调，用 tonumber 转数值"),
        ),
        ApiDoc(
            id = "ui.progress", category = "ui", name = "ui.progress",
            signature = "ui.progress { value?, color? }",
            summary = "线性进度条；value 截断到 0..1，不传 value 为不定态。联动示例见 ui.slider。",
            example = """ui.column {
  ui.progress { value = 0.4 },
  ui.progress {},
}""",
            props = listOf("value" to "0..1；缺省不定态", "color" to "#RRGGBB / #AARRGGBB"),
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
    ).map { doc ->
        if (doc.category != "ui") doc else doc.copy(
            props = doc.props + listOf(
                "width / height / padding / radius" to "有限非负 dp 数值；受父约束，非法值忽略。padding 默认 0，card 默认 16（一次）",
                "background" to "#RRGGBB / #AARRGGBB 背景色",
                "weight / align" to "子项作用域：Row 权重/上下对齐，Column 权重/左右对齐，Box 九宫格；weight 需主轴有界",
                "key / id" to "节点身份；stack 选页仅认 page.key",
                "规范名" to "别名回退已移除；switch 用 checked/onToggle，不用 value/onChange。完整迁移见 LUAX §5",
            ),
        )
    }

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
