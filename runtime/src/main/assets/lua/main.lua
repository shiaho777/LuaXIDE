-- Packaged LuaX page. The document is index.html; this file is the logic.
local html = require("html")

local count = 0
local name = ""

html.on("plus", "click", function()
  count = count + 1
  html.setText("count", "taps: " .. count)
end)

html.on("name", "input", function(text)
  name = text
  html.setText("echo", name == "" and "…" or name)
end)

html.setText("count", "taps: 0")
html.setText("echo", "…")
