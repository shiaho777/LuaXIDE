-- t27: pattern matching (find / match / gmatch / gsub), math, table.sort.
-- Assert-based; runs identically on tree-walk and bytecode VM (bc-diff).
local fails = 0
local function eq(got, want, label)
  if got ~= want then
    print("FAIL " .. label .. ": got " .. tostring(got) .. " want " .. tostring(want))
    fails = fails + 1
  end
end

-- ---- find ----
eq(select("#", string.find("hello", "ll")), 2, "find plain arity")
local s, e = string.find("hello world", "o w")
eq(s, 5, "find start"); eq(e, 7, "find end")
eq(string.find("hello", "z"), nil, "find miss")
local fs, fe, c1, c2 = string.find("key=value", "(%w+)=(%w+)")
eq(fs, 1, "find pat start"); eq(fe, 9, "find pat end")
eq(c1, "key", "find cap1"); eq(c2, "value", "find cap2")
local fs2 = string.find("abc", "b", 2)
eq(fs2, 2, "find init")
eq(string.find("abc", "b", 3), nil, "find init past")
eq(string.find("abc", "", 4), nil, "find init beyond end")
local ps, pe = string.find("abc", "")
eq(ps, 1, "find empty start"); eq(pe, 0, "find empty end")

-- ---- anchors / classes / sets ----
eq(string.find("hello", "^h"), 1, "anchor head")
eq(string.find("hello", "^e"), nil, "anchor head miss")
eq(string.find("hello", "o$"), 5, "anchor tail")
eq(string.match("123abc", "^%d+"), "123", "class digits")
eq(string.match("  x ", "^%s*(%a+)"), "x", "spaces then word")
eq(string.match("abc", "%a+"), "abc", "letters")
eq(string.match("a1!", "%p"), "!", "punct")
eq(string.match("ABC", "%u+"), "ABC", "upper class")
eq(string.match("ABC", "%l+"), nil, "lower class miss")
eq(string.match("x9", "%w+"), "x9", "alnum")
eq(string.match("Hex", "%x+"), "e", "hexdigit class")
eq(string.match("[a]", "%a+"), "a", "set members are alnum")
eq(string.match("a-c", "[a-z]+"), "a", "range")
eq(string.match("b", "[^ac]"), "b", "negated set")
eq(string.match("a", "[^ac]"), nil, "negated set miss")

-- ---- quantifiers ----
eq(string.match("aaa", "a*"), "aaa", "star")
eq(string.match("aaa", "a-"), "", "minus shortest")
eq(string.match("aaa", "a+"), "aaa", "plus")
eq(string.match("b", "a?"), "", "optional empty")
eq(string.match("abc", "a.-(c)"), "c", "lazy capture")
eq(select(1, string.match("abc", "(a.-(c))")), "abc", "nested lazy")

-- ---- captures ----
local d1, m1, d2 = string.match("2026-09-09", "(%d+)-(%d+)-(%d+)")
eq(d1 .. m1 .. d2, "20260909", "date captures")
eq(string.match("hello", "()ll()"), 3, "position captures start")
local p1, p2 = string.match("hello", "()ll()")
eq(p1, 3, "pos cap 1"); eq(p2, 5, "pos cap 2")
eq(string.match("hello", "(h)(e)(l)(l)(o)"), "h", "multi capture first")

-- ---- %b and %f ----
eq(string.match("f(a(b)c)g", "%b()"), "(a(b)c)", "balanced")
eq(string.match("THE (quick)", "%f[%a]%a+"), "THE", "frontier")

-- ---- gsub ----
local g1, n1 = string.gsub("hello world", "o", "0")
eq(g1, "hell0 w0rld", "gsub string"); eq(n1, 2, "gsub count")
local g2, n2 = string.gsub("hello", "l", { l = "L" })
eq(g2, "heLLo", "gsub table"); eq(n2, 2, "gsub table count")
local g3, n3 = string.gsub("a1 b2", "%a(%d)", "%1%1")
eq(g3, "11 22", "gsub backref"); eq(n3, 2, "gsub backref count")
local g4, n4 = string.gsub("abc", "", "-")
eq(g4, "-a-b-c-", "gsub empty"); eq(n4, 4, "gsub empty count")
local g5, n5 = string.gsub("abc", "x", "y")
eq(g5, "abc", "gsub no match"); eq(n5, 0, "gsub no match count")
local g6, n6 = string.gsub("aaa", "a", "b", 2)
eq(g6, "bba", "gsub limit"); eq(n6, 2, "gsub limit count")
local g7, n7 = string.gsub("abc", "(b)", "[%1]")
eq(g7, "a[b]c", "gsub whole-ref"); eq(n7, 1, "gsub whole-ref count")
local g8, n8 = string.gsub("keep", "zz", function() return "X" end)
eq(g8, "keep", "gsub fn no match"); eq(n8, 0, "gsub fn no match count")
local g9, n9 = string.gsub("a b", "%a", function(c) return c:upper() end)
eq(g9, "A B", "gsub fn repl"); eq(n9, 2, "gsub fn count")
local g10 = string.gsub("n=42", "(%d+)", "<%1>")
eq(g10, "n=<42>", "gsub ref in repl")
-- %0 = whole match
local g11 = string.gsub("ab", "a", "%0%0")
eq(g11, "aab", "gsub %0")

-- ---- gmatch ----
local words = {}
for w in string.gmatch("one two three", "%a+") do words[#words + 1] = w end
eq(#words, 3, "gmatch count")
eq(words[1] .. "," .. words[2] .. "," .. words[3], "one,two,three", "gmatch order")
local kv = {}
for k, v in string.gmatch("a=1, b=2", "(%w+)=(%w+)") do kv[k] = v end
eq(kv.a, "1", "gmatch multi cap 1"); eq(kv.b, "2", "gmatch multi cap 2")
local empt = 0
for _ in string.gmatch("ab", "") do empt = empt + 1 end
eq(empt, 3, "gmatch empty pattern terminates")

-- ---- math ----
eq(math.floor(3.7), 3, "floor")
eq(math.ceil(3.2), 4, "ceil")
eq(math.floor(-3.7), -4, "floor neg")
eq(math.abs(-5), 5, "abs")
eq(math.sqrt(16), 4, "sqrt")
eq(math.max(1, 9, 3), 9, "max")
eq(math.min(4, 2, 8), 2, "min")
math.randomseed(42)
local r1 = math.random(1, 10)
math.randomseed(42)
local r2 = math.random(1, 10)
eq(r1, r2, "random deterministic under seed")
local okr = true
for i = 1, 50 do local r = math.random(5); if r < 1 or r > 5 then okr = false end end
eq(okr, true, "random in range")
local rf = math.random()
okr = rf >= 0 and rf < 1
eq(okr, true, "random float range")

-- ---- table.sort ----
local t = { 5, 2, 8, 1, 9, 3 }
table.sort(t)
eq(table.concat(t, ","), "1,2,3,5,8,9", "sort default")
table.sort(t, function(a, b) return a > b end)
eq(table.concat(t, ","), "9,8,5,3,2,1", "sort desc")
local words2 = { "pear", "apple", "fig" }
table.sort(words2)
eq(table.concat(words2, ","), "apple,fig,pear", "sort strings")
local t2 = { 1, 2, 3 }
table.sort(t2)
eq(table.concat(t2, ","), "1,2,3", "sort small")
local t3 = {}
for i = 1, 100 do t3[i] = (i * 37) % 101 end
table.sort(t3)
local sorted = true
for i = 2, 100 do if t3[i - 1] > t3[i] then sorted = false end end
eq(sorted, true, "sort 100 elements")

-- ---- negative cases (must raise) ----
local function musterr(label, fn)
  local ok, msg = pcall(fn)
  if ok then print("FAIL expected error for: " .. label); fails = fails + 1 end
end
musterr("find non-string", function() return string.find(5, "x") end)
musterr("gsub bad repl", function() return string.gsub("a", "a", 5) end)
musterr("format missing arg", function() return string.format("%d") end)
musterr("format bad verb", function() return string.format("%y", 1) end)
musterr("sort bad comp", function() return table.sort({ 1, 2 }, 5) end)
musterr("char out of range", function() return string.char(300) end)
musterr("gsub bad backref", function() return string.gsub("a", "a", "%2") end)

if fails == 0 then print("t27 patterns ok") end
