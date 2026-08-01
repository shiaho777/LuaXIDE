-- t4: functions, closures, varargs, multi-return
local function add(a, b) return a + b end
print(add(2, 3))

local function counter()
  local n = 0
  return function() n = n + 1; return n end
end
local c = counter()
print(c(), c(), c())

-- closure captures by reference
local function make_adder(x)
  return function(y) return x + y end
end
local add10 = make_adder(10)
print(add10(5), add10(20))

-- varargs
local function sum(...)
  local args = {...}
  local s = 0
  for i = 1, #args do s = s + args[i] end
  return s
end
print(sum(1, 2, 3, 4))

-- multi-return
local function two() return 10, 20 end
local a, b = two()
print(a, b)
print(two())
local t = {two()}
print(#t, t[1], t[2])

-- recursion
local function fib(n)
  if n < 2 then return n end
  return fib(n - 1) + fib(n - 2)
end
print(fib(15))

-- pcall
local ok, err = pcall(function() error("boom") end)
print(ok, err)
local ok2, res = pcall(function() return 42 end)
print(ok2, res)
