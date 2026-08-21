-- t15_ui.lua — full UI tree serialization coverage.
-- Run with:  lx --ui tests/t15_ui.lua
-- Exercises every ui.* constructor, all primitive prop value kinds
-- (string escapes, numbers, booleans incl. false, nil, handlers), nested
-- children, plain-array props (-> json arrays), and ui-node-valued props.
local ui = require("ui")

local clicks = 0

return ui.app{
  title = "escapes \n \t \r \" \\ and a \x01 control",
  flag = false,                 -- boolean false prop
  nothing = nil,                -- nil prop  -> json null
  count = 42,                   -- numeric prop
  onGo = function() clicks = clicks + 1 end,   -- handler prop

  ui.column{
    -- every remaining constructor so each ui_* body executes
    ui.text{ text = "hi" },
    ui.button{ text = "go", onClick = function() clicks = clicks + 1 end },
    ui.card{ ui.text{ text = "in card" } },
    ui.input{ value = "v" },
    ui.image{ src = "img.png" },
    ui.spacer{ size = 8 },
    ui.divider{},
    ui.scrollview{ ui.text{ text = "scrolled" } },
    ui.list{
      ui.listitem{ text = "one" },
      ui.listitem{ text = "two" },
    },
    ui.stack{
      selected = "a",
      ui.page{ key = "a", ui.text{ text = "pageA" } },
      ui.page{ key = "b", ui.text{ text = "pageB" } },
    },
    ui.switch{ label = "on", checked = true, onChange = function() end },

    -- plain-array prop -> serialized as a json array of mixed values
    items = { 1, "two", false, nil },
    -- ui-node-valued prop -> serialized via jnode (not an array)
    left = ui.text{ text = "asProp" },
  },
}
