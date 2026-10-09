-- HTML host: register a click and queue the opening text.
local html = require("html")
local count = 0

html.on("plus", "click", function()
  count = count + 1
  html.setText("count", "taps: " .. count)
end)
html.setText("count", "taps: " .. count)
html.addClass("plus", "primary")
print("t8-ok")
