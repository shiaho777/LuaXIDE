-- HTML host argument checks. Ops themselves are asserted from C (t22/t26).
local html = require("html")

local function musterr(fn)
  local ok = pcall(fn)
  assert(ok == false, "expected an error")
end

musterr(function() html.setText(1, "x") end)
musterr(function() html.setText("id") end)
musterr(function() html.on("a", "click", "nope") end)
musterr(function() html.setAttr("id", "name") end)
musterr(function() require("ui") end)

html.setText("t", "a")
html.setHtml("box", "<b>hi</b>")
html.setAttr("img", "src", "a.png")
html.setValue("name", "lua")
html.addClass("t", "on")
html.removeClass("t", "on")
html.on("t", "click", function(payload) end)
print("t15-ok")
