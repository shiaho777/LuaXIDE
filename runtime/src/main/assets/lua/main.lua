local ui = require("ui")
return ui.app{
  key = "home",
  title = "LuaX App",
  ui.column{
    key = "body",
    spacing = 12,
    ui.text{ key = "hello", text = "hello from luax runtime", size = 18, bold = true },
    ui.spacer{ key = "gap", size = 12 },
    ui.card{
      key = "card",
      ui.text{ key = "hint", text = "replace this with your app" },
    },
  },
}
