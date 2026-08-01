-- t6: string lib
print(string.len("hello"))
print(string.upper("hello"))
print(string.lower("HELLO"))
print(string.sub("hello world", 1, 5))
print(string.sub("hello", -3))
print(string.rep("ab", 3))
-- concat & tostring
print("n=" .. tostring(42) .. " b=" .. tostring(true))
print(tonumber("123") + 1)
print(tonumber("xx"))
-- length of string
print(#"luax")
