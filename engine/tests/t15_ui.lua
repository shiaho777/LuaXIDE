-- t15_ui.lua — full UI tree serialization coverage.
-- Run with:  lx --ui tests/t15_ui.lua
-- Exercises every ui.* constructor, all primitive prop value kinds
-- (string escapes, numbers, booleans incl. false, nil, handlers), nested
-- children, plain-array props (-> json arrays), and ui-node-valued props.
local ui = require("ui")

local clicks = 0

-- all 19 constructors must exist
for _, name in ipairs{
  "app","column","row","text","button","card","input","image","spacer",
  "divider","scrollview","list","listitem","stack","page","switch",
  "box","slider","progress",
} do
  assert(type(ui[name]) == "function", "missing ui." .. name)
end

-- string shorthand: ui.text("hi") ≡ ui.text{text="hi"} (text constructor only)
local sugar = ui.text("hi")
assert(type(sugar) == "table" and sugar.__ui == "text" and sugar.text == "hi",
       "ui.text string shorthand")
local canon = ui.text{ text = "hi" }
assert(sugar.text == canon.text and sugar.__ui == canon.__ui,
       "ui.text shorthand equivalence")
-- other constructors keep their contract: a string arg is not a props table
local nosugar = ui.button("no sugar")
assert(nosugar.__ui == "button" and nosugar.text == nil,
       "ui.button string arg is not a props object")

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

    -- string child sugar: a bare string in child position serializes as a
    -- text node; the three engine extensions (box/slider/progress) appear
    -- here so every constructor body executes
    "hello",
    ui.box{ ui.slider{ min = 0, max = 100 }, ui.progress{ value = 50 } },
    ui.text("sugar child"),
  },
}
