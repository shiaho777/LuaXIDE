-- t5: tables, ipairs, pairs, table lib
local t = {10, 20, 30, x = "hi", y = "yo"}
print(#t)
print(t[1], t[2], t[3])
print(t.x, t.y, t["x"])

for i, v in ipairs(t) do print("i", i, v) end

local count = 0
for k, v in pairs(t) do count = count + 1 end
print("pairs count", count)

table.insert(t, 40)
print(#t, t[4])
table.insert(t, 1, 5)
print(t[1], t[2])
print(table.concat({"a","b","c"}, "-"))
print(table.remove(t, 1))

-- nested
local m = {
  {1,2,3},
  {4,5,6},
}
print(m[1][2], m[2][3])

-- multi-assign swap
local a, b = 1, 2
a, b = b, a
print(a, b)
