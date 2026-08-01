-- t7: metatables
local proto = { greet = function(self) return "hi " .. self.name end }
local mt = { __index = proto, __tostring = function(self) return "<Obj " .. self.name .. ">" end, __len = function(self) return 99 end }
local obj = setmetatable({ name = "lua" }, mt)
print(obj:greet())            -- hi lua  (via __index)
print(tostring(obj))         -- <Obj lua>  (via __tostring)
print(#obj)                  -- 99  (via __len)
print(obj.greet)             -- function (from __index)

-- OOP-ish class
local Animal = {}
Animal.__index = Animal
function Animal.new(name)
  return setmetatable({name = name}, Animal)
end
function Animal:speak() return self.name .. " speaks" end
local a = Animal.new("dog")
print(a:speak())
