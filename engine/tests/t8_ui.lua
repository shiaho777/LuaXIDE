-- declarative UI tree: returned value is serialized to JSON
local ui = require("ui")

local count = 0

return ui.app {
  title = "My app",
  ui.column {
    ui.text { text = "Hello, LuaX!", size = 20 },
    ui.button {
      text = "count",
      onClick = function() count = count + 1 end,
    },
    ui.row {
      ui.text { text = "left" },
      ui.divider {},
      ui.text { text = "right" },
    },
  },
}
