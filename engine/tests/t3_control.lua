-- t3: control flow
if 1 > 0 then
  print("if yes")
elseif 1 < 0 then
  print("elseif yes")
else
  print("else")
end

local i = 1
while i <= 3 do
  print("while", i)
  i = i + 1
end

for n = 1, 3 do print("for", n) end
for n = 10, 2, -4 do print("step", n) end

repeat
  print("repeat once")
until true

-- break
for n = 1, 100 do
  if n == 3 then break end
  print("loop", n)
end

-- nested + early return
local function find(t, target)
  for k, v in pairs(t) do
    if v == target then return k end
  end
  return nil
end
print(find({a=1,b=2,c=3}, 2))
