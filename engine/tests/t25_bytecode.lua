-- t25_bytecode.lua — bytecode VM v0 coverage.
-- Every assertion here must hold identically on the tree-walking interpreter;
-- the Makefile bc-diff target runs this file under LUAX_NO_BC=1 and diffs.
local fails = 0
local function contains(s, sub)
  s = tostring(s); sub = tostring(sub)
  for i = 1, #s - #sub + 1 do
    if string.sub(s, i, i + #sub - 1) == sub then return true end
  end
  return false
end
local function eq(got, want, label)
  if got ~= want then
    print("FAIL " .. label .. ": got " .. tostring(got) .. " want " .. tostring(want))
    fails = fails + 1
  end
end

-- arithmetic & numeric edge semantics
eq(7 % 3, 1, "mod")
eq(-7 % 3, 2, "mod negative")
eq(7 // 2, 3, "idiv")
eq(-7 // 2, -4, "idiv negative")
eq(2 ^ 10, 1024, "pow")
eq(1 / 4, 0.25, "div")
eq(~5, -6, "bnot")
eq(5 & 3, 1, "band")
eq(5 | 3, 7, "bor")
-- bitwise ops also run inside a function body — assert there too
local function bits(a, b) return a & b, a | b, a ~ b, ~a, a << 2, a >> 1 end
local g1, g2, g3, g4, g5, g6 = bits(5, 3)
eq(g1, 1, "band in fn")
eq(g2, 7, "bor in fn")
eq(g3, 6, "bxor in fn")
eq(g4, -6, "bnot in fn")
eq(g5, 20, "shl in fn")
eq(g6, 2, "shr in fn")
-- mixed precedence: | is lower than ~ and &, compiled into the same body
local function mix() return 12 | 10 & 3 end
eq(mix(), 14, "bit precedence in fn")
eq(5 ~ 3, 6, "bxor")
eq(1 << 10, 1024, "shl")
eq(1024 >> 3, 128, "shr")
eq(-(3 + 4) * 2, -14, "precedence")

-- strings
eq("a" .. "b" .. 1, "ab1", "concat")
eq(#"hello", 5, "len")
eq(string.sub("hello", 2, 3), "el", "sub")
eq(string.rep("ab", 3), "ababab", "rep")
eq(string.upper("aB"), "AB", "upper")
eq(tostring(1.5), "1.5", "tostring num")
eq(tostring(nil), "nil", "tostring nil")

-- comparisons & logic (value-preserving and/or)
eq(1 < 2, true, "lt")
eq(2 <= 2, true, "le")
eq("x" == "x", true, "eq str")
eq(1 ~= 2, true, "ne")
eq(false or "fallback", "fallback", "or value")
eq(nil and 9, nil, "and nil")
eq(false and 9, false, "and false value")
eq(true and 9, 9, "and true")
eq(not nil, true, "not")

-- scopes & shadowing
local x = "outer"
do
  local x = "inner"
  eq(x, "inner", "shadow inner")
end
eq(x, "outer", "shadow outer")
for i = 1, 1 do local x = "loop" ; eq(x, "loop", "shadow loop") end
eq(x, "outer", "shadow after loop")

-- while / repeat / break
local n = 0
while true do n = n + 1 if n >= 5 then break end end
eq(n, 5, "while break")
local r = 0
repeat r = r + 1 local seen = r until seen >= 3   -- until sees body locals
eq(r, 3, "repeat sees body locals")
local nest = 0
for i = 1, 3 do
  for j = 1, 3 do
    if j == 2 then break end
    nest = nest + 1
  end
end
eq(nest, 3, "nested break inner only")

-- numeric for
local acc = {}
for i = 10, 2, -4 do acc[#acc + 1] = i end
eq(#acc, 3, "desc count")
eq(acc[2], 6, "desc step")
local empty = 0
for i = 1, 0 do empty = empty + 1 end
eq(empty, 0, "empty range")

-- generic for
local vals = 0
local t = {a = 1, b = 2, c = 3}
for k, v in pairs(t) do vals = vals + v end
eq(vals, 6, "pairs values")
local arr = {"x", "y", "z"}
local cat = ""
for idx, v in ipairs(arr) do cat = cat .. idx .. v end
eq(cat, "1x2y3z", "ipairs")

-- tables: every positional field expands multi-values (engine semantics)
function two() return 10, 20 end
local ct = {1, two(), 50}
eq(#ct, 4, "positional field expansion")
local kt = {x = two()}
eq(kt.x, 10, "kv takes single value")

-- multi-return plumbing
local a, b = two()
eq(a, 10, "multi a")
eq(b, 20, "multi b")
local only = two()
eq(only, 10, "multi adjusted to one")
local function passthru(...) return ... end
local p1, p2 = passthru(two())
eq(p2, 20, "tail multret through params")

-- varargs function stays tree-walked but must behave identically
local function va(...)
  local s = select("#", ...)
  local first = ...
  return s, first
end
local vs, vf = va("A", "B", "C")
eq(vs, 3, "vararg select#")
eq(vf, "A", "vararg first")

-- a function reading a top-level local captures it via the chunk's env
-- (capmode): the same name resolves identically on both engine paths
local base = 100
local function addbase(v) return v + base end
eq(addbase(5), 105, "top-level local capture")

-- methods
local obj = {n = 40}
function obj:bump(d) self.n = self.n + d return self.n end
eq(obj:bump(2), 42, "methodcall self")
eq(obj.n, 42, "methodcall mutates")

-- metatables
local proto = {greet = function() return "hi" end}
local inst = setmetatable({}, {__index = proto})
eq(inst.greet(), "hi", "__index chain")
local boxed = setmetatable({items = {1, 2, 3}}, {
  __len = function() return 99 end,
  __tostring = function() return "BOX" end,
})
eq(#boxed, 99, "__len hook")
eq(tostring(boxed), "BOX", "__tostring hook")

-- recursion via global name
function bfib(n) if n < 2 then return n end return bfib(n-1) + bfib(n-2) end
eq(bfib(15), 610, "recursive global fn")

-- runtime errors keep line-prefixed messages under the VM
local eok, emsg = pcall(function()
  local q = nil
  return q.field
end)
eq(eok, false, "pcall caught")
if not contains(emsg, "attempt to index") then
  print("FAIL error message shape: " .. tostring(emsg)); fails = fails + 1
end
if not contains(emsg, "line ") then
  print("FAIL line prefix missing: " .. tostring(emsg)); fails = fails + 1
end

-- rep-too-large guard identical on both engines
local rok, rmsg = pcall(string.rep, "a", 1e18)
eq(rok, false, "rep guard")

-- assignment order: all RHS first, then targets left-to-right
local ai = 1
local at = {}
ai, at[ai] = 2, "x"          -- at gets key from UPDATED ai (=2)
eq(ai, 2, "assign rhs order")
eq(at[2], "x", "target key sees updated value")
eq(at[1], nil, "no stale key")

-- missing values become nil
local m1v, m2v = 1
eq(m2v, nil, "missing value nil")

-- select / unpack round trip
eq(select("#", 1, 2, 3), 3, "select #")
local u1, u2, u3 = table.unpack({7, 8, 9})
eq(u1 + u2 + u3, 24, "unpack")

-- positional counter shares key space with explicit keys (engine semantics;
-- real Lua would keep "one" here — both engine paths agree on "two")
local mt2 = {[1] = "one", "two"}
eq(mt2[1], "two", "positional overrides explicit key")

-- multi-value expansion with a fixed prefix (regressions found by bc-fuzz):
-- f(x, g()) must pass x AND g's expanded results; same for returns,
-- locals, assignments, method calls and table constructors.
local function nargs(...) return select("#", ...) end
local function two() return 1, 2 end
local function three() return 1, 2, 3 end
local function argcount() return nargs(10, three()) end
eq(argcount(), 4, "call arg expands last call")
local function retmulti() return 7, three() end
eq(select("#", retmulti()), 4, "return expands last call")
local function localmulti() local x, y = 5, two() return x, y end
local lx, ly = localmulti()
eq(lx, 5, "local multi keeps prefix")
eq(ly, 1, "local multi keeps call result")
local function assignmulti() local x, y x, y = 5, two() return x, y end
local ax, ay = assignmulti()
eq(ax, 5, "assign multi keeps prefix")
eq(ay, 1, "assign multi keeps call result")
local mobj = { n = function(self, ...) return select("#", ...) end }
local function methcall(o) return o:n(10, two()) end
eq(methcall(mobj), 3, "method arg expands last call")
local function ctormulti() return {two(), 9} end
local tt = ctormulti()
eq(#tt, 3, "ctor expands call positional")
eq(tt[3], 9, "ctor positional after expansion")

-- repeat..until exits when the condition is TRUE (was inverted in the VM)
local function rp(n) local i = 0 repeat i = i + 1 until i >= n return i end
eq(rp(3), 3, "repeat until exits when true")
eq(rp(1), 1, "repeat until single pass")

-- nil expressions must actually write nil: a stale register would leak the
-- previous iteration's value (LOADNIL used to emit a zero-length store)
local function nilv(i) return (i < 3) and i or nil end
eq(nilv(3), nil, "or-nil result is nil")
eq(nilv(1), 1, "or-nil keeps truthy path")
local function stalereg()
  local t = {}
  for i = 1, 2 do t.v = (i == 1) and 9 or nil end
  return t.v
end
eq(stalereg(), nil, "no stale register through nil")

-- literal folding: compile-time results must match runtime formulas exactly
local function folds()
  return 7 % 3, -7 % 3, 7 // 2, 2 ^ 10, 1 / 4, ~5, 5 & 3, 12 | 10 & 3,
         "a" .. "b", #"hello", -(-3), not nil, 3 < 4
end
local f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13 = folds()
eq(f1, 1, "fold mod"); eq(f2, 2, "fold mod neg"); eq(f3, 3, "fold idiv")
eq(f4, 1024, "fold pow"); eq(f5, 0.25, "fold div"); eq(f6, -6, "fold bnot")
eq(f7, 1, "fold band"); eq(f8, 14, "fold bor band precedence")
eq(f9, "ab", "fold concat"); eq(f10, 5, "fold len"); eq(f11, 3, "fold unm")
eq(f12, true, "fold not nil"); eq(f13, true, "fold lt")
local function foldinf() return 1 / 0 > 0 end
eq(foldinf(), true, "fold div by zero keeps inf")

-- a table ctor as the FIRST call arg: its dst register is the next free slot,
-- so the ctor's internal temps used to alias it and clobber the new table
local function ctorfirst() return type({1, 2}) end
eq(ctorfirst(), "table", "table ctor in first call arg")
local function ctorfirstmt() return setmetatable({tag = "t"}, {}).tag end
eq(ctorfirstmt(), "t", "table ctor first arg with second arg")
local function ctorfirst2() local t = {1, 2, 3} return #{7, t} end
eq(ctorfirst2(), 2, "table ctor arg keeps own elements")

-- a RETURN inside a conditional block does not make the function's end
-- unreachable: the fallthrough path still needs the epilogue
local function ifret(c) if c then return "yes" end end
eq(ifret(true), "yes", "if-return taken")
eq(ifret(false), nil, "if-return fallthrough is nil")
local function ifret2(c) if c then return 1 else return 2 end end
eq(ifret2(true), 1, "if/else then-arm")
eq(ifret2(false), 2, "if/else else-arm")

-- ===== nested functions / captured variables (capmode env ops) =====
local Animal = {}
Animal.__index = Animal
function Animal.new(name) return setmetatable({name = name}, Animal) end
function Animal:speak() return self.name end
eq(Animal.new("dog"):speak(), "dog", "class-style method on captured table")

local function counter()
  local n = 0
  return function() n = n + 1 return n end
end
local c1 = counter()
eq(c1(), 1, "closure mutates captured local")
eq(c1(), 2, "capture persists across calls")
local c2 = counter()
eq(c2(), 1, "second closure gets own binding")

local function outer()
  local v = 7
  local function mid()
    local w = 3
    return function() return v * w end
  end
  return mid()
end
eq(outer()(), 21, "two-level capture")

local function par(a, b) return function() return a - b end end
eq(par(9, 4)(), 5, "captured params")

-- each loop iteration binds a fresh env: closures see their own i
local function iterfns()
  local fns = {}
  for i = 1, 3 do fns[i] = function() return i * 10 end end
  return fns
end
local fs = iterfns()
eq(fs[1]() + fs[2]() + fs[3](), 60, "per-iteration loop var capture")

-- locals declared inside a do-block stay reachable through an escaped closure
local esc
do
  local hidden = 41
  esc = function() hidden = hidden + 1 return hidden end
end
eq(esc(), 42, "escaped do-block env")
eq(esc(), 43, "escaped env mutation persists")

-- local function recursion through the env binding
local function mkrec()
  local function f(n) if n <= 0 then return 0 end return n + f(n - 1) end
  return f(4)
end
eq(mkrec(), 10, "local function recursion")

-- repeat..until sees a body local from inside a closure-friendly body
local function repcap()
  local fns = {}
  local i = 0
  repeat
    i = i + 1
    local j = i * 2
    fns[i] = function() return j end
  until i >= 3
  return fns[1]() + fns[2]() + fns[3]()
end
eq(repcap(), 12, "repeat per-iteration capture")

-- a while-loop closure plus break crossing an opened block env
local function whilecap()
  local fns, i = {}, 0
  while i < 2 do
    i = i + 1
    do
      local k = i + 100
      fns[i] = function() return k end
    end
    if i >= 2 then break end
  end
  return fns[1]() + fns[2]()
end
eq(whilecap(), 203, "do-block capture inside while + break")

-- nested vararg fn still falls back to tree-walk transparently
local function hasvararg()
  local f = function(...) return select('#', ...) end
  return f(1, 2, 3)
end
eq(hasvararg(), 3, "vararg nested fn falls back")

-- ===== top-level chunk on the VM (Phase 1b) =====
-- Everything above already ran on the VM when compilable; these cases pin the
-- chunk-specific semantics: top-level locals, per-iteration capture from the
-- chunk, early return with values, and chunk-level control flow.
local tl_acc = 0
for tl_i = 1, 10 do tl_acc = tl_acc + tl_i end
eq(tl_acc, 55, "top-level numeric loop")

local tl_fns = {}
for tl_i = 1, 3 do tl_fns[tl_i] = function() return tl_i * 10 end end
eq(tl_fns[1]() + tl_fns[2]() + tl_fns[3](), 60, "top-level per-iteration capture")

local tl_s = ""
for tl_i = 1, 5 do
  if tl_i == 3 then
    -- continue-style: skip via nested block + if
  elseif tl_i >= 4 then break
  else tl_s = tl_s .. tl_i
  end
end
eq(tl_s, "12", "top-level break and branch")

if fails > 0 then error("t25 FAILED: " .. fails .. " case(s)") end
print("t25 ok")
return "t25 chunk return value"
