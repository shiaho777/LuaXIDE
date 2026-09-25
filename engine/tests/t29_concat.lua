-- t29: lazy concat (rope) — observable semantics must be identical to flat
-- strings in both VM and tree-walk modes (this file is also a bc-diff script).

local function fail(msg) print("FAIL " .. msg) end
local function ok(c, msg) if not c then fail(msg) end end

-- basic concat of strings/numbers, incl. values produced by earlier concats
local a = "ab" .. "cd"
ok(a == "abcd", "basic concat")
ok(#a == 4, "len of rope")
ok(a .. "e" == "abcde", "concat of rope")
ok(("x" .. a) == "xabcd", "rope as right operand")
ok((1 .. 2) == "12", "number concat")
ok((a .. 7) == "abcd7", "rope .. number")

-- the loop that used to be O(n^2)
local s = ""
for i = 1, 50000 do s = s .. i end
ok(#s == 238894, "append loop length")
ok(s:sub(1, 15) == "123456789101112", "append loop head")
ok(s:sub(-5) == "50000", "append loop tail")

-- prepend loop (right-leaning ropes)
local p = ""
for i = 1, 5000 do p = "z" .. p end
ok(#p == 5000 and p == ("z"):rep(5000), "prepend loop")

-- ropes as table keys, both directions
local t = {}
t["he" .. "llo"] = 1
ok(t["hello"] == 1, "rope key write / flat key read")
t["hel" .. "lo2"] = 2
ok(t["hello2"] == 2, "rope key 2")
local big1 = ""
local big2 = ""
for i = 1, 500 do big1 = big1 .. "k" end
for i = 1, 500 do big2 = big2 .. "k" end
t[big1] = 99
ok(t[big2] == 99, "two ropes equal as keys")
ok(rawget(t, big1) == 99 and rawget(t, big2) == 99, "rawget rope key")

-- equality/comparison
ok(big1 == big2, "rope == rope")
ok(big1 == ("k"):rep(500), "rope == flat")
ok(not (big1 == big2 .. "!"), "rope ~= longer")

-- stdlib on ropes
ok(string.upper(a) == "ABCD", "string.upper rope")
ok(string.sub(s, 1, 3) == "123", "string.sub rope")
ok(string.rep("q", 3) .. "r" == "qqqr", "rep result concat")
ok(string.len(s) == 238894, "string.len rope")
ok(string.byte(s, 1) == 49, "string.byte rope")
ok(string.reverse("ab" .. "cd") == "dcba", "string.reverse rope")
ok(string.format("%s-%s", a, s:sub(-3)) == "abcd-000", "string.format rope")
ok(tostring(s):sub(-5) == "50000", "tostring rope")
ok(tonumber("1" .. "0") == 10, "tonumber rope")
ok(table.concat({"a", a, "b"}) == "aabcdb", "table.concat rope member")
ok(table.concat({"a", "b"}, a) == "aabcdb", "table.concat rope sep")
ok(string.find(s, "78910") == 7, "string.find rope")
ok(string.match(s, "^(%d%d%d)") == "123", "string.match rope")
ok(string.gsub("x" .. "y", "y", "z") == "xz", "string.gsub rope subj")
ok(string.gsub("xy", "x", a .. "!") == "abcd!y", "string.gsub rope repl")
ok(select("#", "a" .. "b", "c") == 2, "select rope")

-- ropes through function calls, multi-returns, pcall
local function id(x) return x end
ok(id(s) == s, "rope through call")
local function two() return s, p end
local r1, r2 = two()
ok(r1 == s and r2 == p, "rope multi-return")
local okc, rv = pcall(function() return s .. "!" end)
ok(okc and rv == s .. "!", "rope in pcall")

-- ropes in vararg / unpack paths
ok(select(2, "l" .. "m", "n") == "n", "select nth rope")
local up = table.unpack({"u" .. "v"})
ok(up == "uv", "unpack rope")

-- concat inside error/assert messages
local ok2, e2 = pcall(function() error("e" .. "rr") end)
ok(ok2 == false and e2:sub(-3) == "err", "error(rope)")
local ok3, e3 = pcall(function() assert(false, "as" .. "sert!") end)
ok(ok3 == false and e3:sub(-7) == "assert!", "assert(false, rope)")

-- deep left spine: enough iters to smash a recursive flatten
local d = ""
for i = 1, 200000 do d = d .. "a" end
ok(#d == 200000, "deep spine")
ok(d == ("a"):rep(200000), "deep spine content")

-- s = s .. s (shared subtree / doubling)
local g = "x"
for i = 1, 16 do g = g .. g end
ok(#g == 65536 and g == ("x"):rep(65536), "doubling rope")

-- empty / boundary concats
ok(("" .. "") == "", "empty concat")
ok((s .. "") == s, "concat empty right")
ok(("" .. s) == s, "concat empty left")

-- os/io consumers that take C strings
ok(os.getenv("LX_NON" .. "EXISTENT_VAR_XYZ") == nil, "getenv rope arg")

print("t29 concat ok")
