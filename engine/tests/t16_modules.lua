-- t16_modules.lua — require() / package.loaded coverage (cwd-relative root).
-- The CLI runs from the engine/ directory, so module names resolve under
-- tests/modfixture/... via the dotted-name -> path translation.

local function contains(s, sub)
  s = tostring(s); sub = tostring(sub)
  if sub == "" then return true end
  for i = 1, #s - #sub + 1 do
    if string.sub(s, i, i + #sub - 1) == sub then return true end
  end
  return false
end

local function ok(msg, cond) if not cond then error("t16 fail: "..msg) end end

-- dotted name -> tests/modfixture/m1.lua, returns the module table
local m1 = require("tests.modfixture.m1")
ok("m1 loaded", type(m1) == "table")
ok("m1.greet", m1.greet() == "hi")

-- init.lua variant: tests/modfixture/lib/init.lua
local lib = require("tests.modfixture.lib")
ok("lib via init", lib.via == "init")

-- nested require: chain/a.lua requires chain/b.lua
local a = require("tests.modfixture.chain.a")
ok("chain", a.val == 42)

-- require returns the same cached table on the second call
ok("cached", require("tests.modfixture.m1") == m1)

-- module with no explicit return -> package.loaded entry is true
local plain = require("tests.modfixture.plain")
ok("no-return is true", plain == true)

-- error inside a module propagates with a clear message
local eok, emsg = pcall(require, "tests.modfixture.boom")
ok("module error caught", eok == false)
ok("module error message", contains(emsg, "error loading module"))
ok("module error reason", contains(emsg, "kaboom"))

-- missing module: resolve path exhausts all candidates then errors
local mok, merr = pcall(require, "tests.modfixture.does_not_exist")
ok("missing caught", mok == false)
ok("missing message", contains(merr, "not found") and contains(merr, "does_not_exist"))

-- bad argument type to require
local bok, _ = pcall(require, 42)
ok("require bad arg", bok == false)

-- empty module name -> j==0 in path resolution -> not found
local eok2, _ = pcall(require, "")
ok("empty name", eok2 == false)

-- require("ui") shortcut returns the global ui table
ok("ui shortcut", require("ui") ~= nil and type(require("ui")) == "table")

-- a name containing an explicit slash separator still resolves to a file
local slashmod = require("tests/modfixture/m1")
ok("slash name loads", type(slashmod) == "table" and slashmod.greet() == "hi")

-- forcing package to a table without a "loaded" field recreates it
package = { note = "reset" }
local m1b = require("tests.modfixture.m1")
ok("package without loaded", type(m1b) == "table")

-- forcing package to non-table recreates both package and loaded
package = nil
local m1c = require("tests.modfixture.m1")
ok("package nil recreates", type(m1c) == "table")

print("t16 ok")
return 0
