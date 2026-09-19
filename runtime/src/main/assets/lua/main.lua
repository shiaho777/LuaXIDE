-- runtime template sample: exercises the new UI contract surface
local ui = require("ui")

local count = 0
local msg = ""
local level = 50

local function view()
  return ui.app{
    key = "home",
    title = "LuaX App",
    ui.column{
      key = "body",
      spacing = 12,
      ui.text{ key = "hello", text = "hello from luax runtime", size = 18, bold = true },
      "string child sugar",                       -- bare string -> text node
      ui.row{ key = "actions", spacing = 8,
        ui.button{ key = "plus", text = "+1", onClick = function() count = count + 1 end },
        ui.text{ key = "count", text = "count: " .. count },
      },
      ui.input{ key = "name", label = "live echo", value = msg,
                onChange = function(v) msg = v; count = #v end },
      ui.slider{ key = "level", value = level, from = 0, to = 100,
                 onChange = function(v) level = tonumber(v) or 0 end },
      ui.progress{ key = "bar", value = level / 100 },
      ui.box{ key = "badge-area", height = 64,
        ui.card{ key = "bg", ui.text{ text = "box layer" } },
        ui.text{ key = "badge", text = "★", align = "topright", color = "#E91E63" },
      },
      ui.scrollview{ key = "inner", height = 120,
        ui.text{ text = "inner scroll 1" },
        ui.text{ text = "inner scroll 2" },
        ui.text{ text = "inner scroll 3" },
        ui.text{ text = "inner scroll 4" },
      },
    },
  }
end

return view
