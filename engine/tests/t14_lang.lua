-- t14_lang.lua — broad language/stdlib coverage + runtime-error cases.
-- Uses assert() so any wrong value or unexpected runtime error fails the run.
local function eq(a, ...) local t = {...} for i = 1, #t do if a ~= t[i] then error("mismatch "..tostring(a).." "..tostring(t[i])) end end end

-- do ... end blocks (own scope), return propagation through do
local d = nil
do local x = 7 d = x end
assert(d == 7)

-- nested do with inner return
local function fdo() do return 99 end return 0 end
assert(fdo() == 99)

-- closures + upvalues capture & mutation across calls
local function counter()
  local n = 0
  return function() n = n + 1; return n end
end
local c1 = counter()
assert(c1() == 1 and c1() == 2 and c1() == 3)
local c2 = counter()
assert(c2() == 1)               -- independent upvalue

-- shared upvalue across two closures
local function pair()
  local v = 0
  local function get() return v end
  local function set(x) v = x end
  return get, set
end
local g, s = pair()
s(42); assert(g() == 42); s(8); assert(g() == 8)

-- varargs forwarding & counting
local function va(...)
  local t = {...}
  return #t, ...
end
assert((va()) == 0)
-- va returns (count, ...) so select("#", va(...)) sees count + the args
assert(select("#", va(1,2,3)) == 4)

-- multi-return truncation in the middle of a list
assert(va(10, 20, 30, 40) == 4)  -- count is first return
local a, b = va(1, 2, 3)
assert(a == 3 and b == 1)

-- length of string & of table & of table with holes gap
assert(#"hello" == 5)
assert(#{10,20,30} == 3)

-- unary minus, not, bitwise not, length on numbers is an error
assert((-5) == -5 and (not nil) == true and (not false) == true and (not 0) == false)
assert((~0) == -1)

-- every binary operator
eq(7 % 3, 1); eq(2 ^ 10, 1024); eq(7 // 2, 3); eq(1 << 4, 16); eq(256 >> 2, 64)
eq(12 | 10, 14); eq(12 & 10, 8); eq(12 ~ 10, 6); eq(1 .. 2 .. 3, "123")
eq(3 < 5, true); eq(3 > 5, false); eq(3 <= 3, true); eq(3 >= 4, false)
eq(1 == 1.0, true); eq("a" ~= "b", true)
eq(true and "x", "x"); eq(false or "y", "y"); eq(nil and 1, nil); eq(0 or "z", 0)

-- string escapes (hex / decimal / alarm / backspace / formfeed / vtab / z-skip)
assert("\x48\x49" == "HI")
assert("\065\066" == "AB")
assert("a\ab\fc\vd" == "a\007b\012c\011d")
-- \z skips following whitespace on the same line (stops at the closing quote)
assert("a\z   b" == "ab")

-- long string / long comment, with leading newline dropped and newlines counted
local long = [[line1
line2]]
assert(long == "line1\nline2")
local long2 = [==[has ]] brackets inside]==]
assert(long2 == "has ]] brackets inside")
-- long comment spanning newlines
--[[ this
   is ignored ]] local after = 1
assert(after == 1)

-- method & call suffixes with table- and string-argument forms
local O = {}
function O.dot(a, b) return a + b end
function O:m(args) return self, args.x end
function O:tag(s) return s end
assert(O.dot(2, 3) == 5)                          -- function t.m declaration
local selfref, r = O:m{x = 9}                     -- method call with { } args
assert(selfref == O and r == 9)
assert(O:tag"hi" == "hi")                         -- method call with string arg
assert(O:tag{x = 1}.x == 1)                       -- method call with table arg

-- call expression with table / string argument (no parens)
local function take(t) return t.k end
assert(take{k = 5} == 5)
local function id(s) return s end
assert(id"boom" == "boom")

-- generic for with a custom multi-value iterator
local function stateless(st, ctrl)
  ctrl = (ctrl or 0) + 1
  if ctrl > st then return nil end
  return ctrl, ctrl * 10
end
local seen = {}
for k, v in stateless, 3, 0 do seen[k] = v end
assert(seen[1] == 10 and seen[2] == 20 and seen[3] == 30)

-- numeric for with break (descending)
local last
for i = 5, 1, -1 do last = i; if i == 3 then break end end
assert(last == 3)

-- repeat/until reads a loop-local
local done
repeat local x = 1; done = x until x == 1
assert(done == 1)

-- metatables: __index (table) chain, __index (function), __newindex function,
-- __newindex table, __len, __tostring, __call-via-index
local Base = {kind = "base"}
function Base:who() return self.name end
local Der = setmetatable({}, {__index = function(t, k)
  if k == "computed" then return 100 end
  return Base[k]
end})
local obj = setmetatable({name = "o"}, {__index = Der})
assert(obj.kind == "base")        -- __index table chain: obj -> Der -> Base
assert(obj.computed == 100)       -- __index function
assert(obj:who() == "o")

-- deep __index chain (well beyond any old hard cap) still resolves
local deep = { base = "ok" }
for i = 1, 500 do deep = setmetatable({}, {__index = deep}) end
assert(deep.base == "ok")

-- an absurdly deep chain (>1024) is treated as a cycle and errors out
local toolong = { x = 1 }
for i = 1, 1100 do toolong = setmetatable({}, {__index = toolong}) end
local dlok, dlmsg = pcall(function() return toolong.x end)
assert(dlok == false, "expected deep-chain error")
local m = tostring(dlmsg)
-- the message reports the chain-too-deep guard; verify it mentions "deep"
local found = false
for i = 1, #m - 3 do
  if string.sub(m, i, i+3) == "deep" then found = true break end
end
assert(found, "deep chain error msg: "..m)

-- __newindex as a function records assignments to absent keys
local log = {}
local guard = setmetatable({}, {__newindex = function(t, k, v) log[#log+1] = k.."="..tostring(v) end})
guard.a = 1; guard.b = 2
assert(log[1] == "a=1" and log[2] == "b=2")

-- __newindex as a table routes writes into a backing table
local backing = {}
local routed = setmetatable({}, {__newindex = backing})
routed.x = 9
assert(backing.x == 9 and routed.x == nil)

-- __len and __tostring
local mt = setmetatable({}, {__len = function() return 7 end, __tostring = function() return "T!" end})
assert(#mt == 7)
assert(tostring(mt) == "T!")

-- tostring of every value kind (table / function via print too)
assert(tostring(nil) == "nil" and tostring(false) == "false" and tostring(0) == "0")
assert(string.sub(tostring({}), 1, 6) == "table:")          -- table: 0x...
local fnstr = tostring(print)                        -- print is a C function
assert(string.sub(fnstr, 1, 9) == "function:")
print({})                                            -- exercises table formatting on stdout
print(print)                                         -- C function formatting

-- reading an absent key by indexing with nil (hashVal(nil-key) path)
local nt = {}
assert(nt[nil] == nil)

-- select / table.unpack variants
assert(select("#", 1, 2, 3) == 3)
assert((select(2, "a", "b", "c")) == "b")      -- first of the tail, parenthesised
assert((select(-1, "a", "b", "c")) == "c")     -- negative index selects last
local u = {table.unpack({10, 20, 30}, 2, 3)}
assert(u[1] == 20 and u[2] == 30 and #u == 2)

-- metatable accessors & raw ops
local raw = setmetatable({a = 1}, {__index = function() return 99 end})
assert(raw.a == 1 and raw.b == 99)
assert(rawget(raw, "b") == nil)                       -- rawget skips __index
rawset(raw, "c", 5); assert(raw.c == 5)
assert(rawequal(raw, raw) == true)
assert(getmetatable(raw) ~= nil)
assert(getmetatable(5) == nil)                        -- non-table -> nil
assert(getmetatable({}) == nil)

-- string library
assert(string.len("abc") == 3 and string.len("X") == 1)
assert(string.upper("aB3") == "AB3")
assert(string.lower("aB3") == "ab3")
assert(string.sub("hello", 2, 4) == "ell")
assert(string.sub("hello", -2) == "lo")
assert(string.rep("ab", 3) == "ababab")
assert(string.rep("x", 0) == "")

-- table library
local arr = {}
table.insert(arr, 1); table.insert(arr, 3); table.insert(arr, 2, 2)
assert(arr[1] == 1 and arr[2] == 2 and arr[3] == 3)
local rem = table.remove(arr, 1)
assert(rem == 1 and arr[1] == 2)
assert(table.concat({1, 2, 3}, "-") == "1-2-3")
assert(table.concat({1, 2}, ",", 1, 1) == "1")

-- io.write / io.flush (no stdin involved)
io.write("w", 1, "\n"); io.flush()

-- os library (env may be unset -> nil is acceptable)
assert(type(os.time()) == "number" and os.time() > 0)
assert(type(os.clock()) == "number")
local path = os.getenv("PATH")
assert(path == nil or type(path) == "string")
assert(os.getenv("THIS_DOES_NOT_EXIST_X9Q") == nil)

-- next/pairs over mixed table
local mixed = {x = 1, y = 2, [1] = "seq"}
local n = 0
for k, v in pairs(mixed) do n = n + 1 end
assert(n == 3)
local first = next(mixed)
assert(first ~= nil)

-- pcall: success multi-return + error capture
local ok, e1, e2 = pcall(function() return 1, 2 end)
assert(ok == true and e1 == 1 and e2 == 2)
local ok2, msg = pcall(error, "boom")
assert(ok2 == false and string.sub(msg, -4) == "boom")   -- error() prepends location

-- ---- runtime error cases (must raise, never crash) ----
local function musterr(label, fn)
  local ok, msg = pcall(fn)
  assert(not ok, "expected error for: " .. label)
  return msg
end

musterr("arith on nil",      function() return nil + 1 end)
musterr("arith on bool",     function() return true - 1 end)
musterr("string->number",    function() return "abc" * 2 end)
musterr("length of number",  function() return #5 end)
musterr("index a number",    function() return (1).x end)
-- strings index the string library (method sugar): ("s").y is nil, not an error
assert(("s").y == nil, "unknown string method should be nil")
assert(("s").upper == string.upper, "string method sugar resolves to string lib")
assert(("abc"):len() == 3, "method call sugar on strings")
musterr("newindex number",   function() (1).z = 5 end)
musterr("call a number",     function() return (5)() end)
musterr("call a nil",        function() local n; return n() end)
musterr("assign to call",    function() local x; x, print() = 1, 2 end)
musterr("table index nil",   function() local t = {}; t[nil] = 1 end)
musterr("break outside loop",function() local f = function() break end; f() end)
musterr("next bad key",      function() next({a=1}, "zz") end)
musterr("assert no msg",     function() assert(false) end)
musterr("assert nonstr msg", function() assert(false, 42) end)
musterr("select zero",       function() select(0, 1, 2) end)
musterr("unpack too many",   function() table.unpack({1,2,3}, 1, 100) end)
musterr("rep too large",     function() string.rep("a", 1e18) end)
musterr("string.len(5)",     function() string.len(5) end)
musterr("table.insert(5)",   function() table.insert(5, 1) end)
musterr("pairs(5)",          function() for _ in pairs(5) do end end)
musterr("ipairs(5)",         function() for _ in ipairs(5) do end end)
musterr("rawget(5)",         function() rawget(5, "x") end)
musterr("rawset(5)",         function() rawset(5, "x", 1) end)
musterr("concat non-str",    function() table.concat({{}}) end)

print("t14 ok")
return 0
