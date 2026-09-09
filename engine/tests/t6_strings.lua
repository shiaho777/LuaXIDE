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

-- byte / char / reverse
assert(string.byte("A") == 65, "byte")
assert(string.byte("A", 1) == 65, "byte explicit i")
local b1, b2 = string.byte("AB", 1, 2)
assert(b1 == 65 and b2 == 66, "byte range")
assert(string.byte("hello", -1) == 111, "byte negative i")
assert(string.char(72, 105) == "Hi", "char")
assert(string.reverse("abc") == "cba", "reverse")

-- format
assert(string.format("%d", 42) == "42", "fmt d")
assert(string.format("%5.2f", 3.14159) == " 3.14", "fmt width+prec")
assert(string.format("%-6s|", "ab") == "ab    |", "fmt left")
assert(string.format("%s %s", 1, true) == "1 true", "fmt s coerces")
assert(string.format("%x %X %o", 255, 255, 8) == "ff FF 10", "fmt hex/oct")
assert(string.format("%e", 1000) == "1.000000e+03", "fmt e")
assert(string.format("100%%") == "100%", "fmt percent")
assert(string.format("%q", 'a"b') == '"a\\"b"', "fmt q")
assert(string.format("%c", 65) == "A", "fmt c")
assert(string.format("%03d", 5) == "005", "fmt zero pad")

-- method sugar on strings
assert(("AbC"):lower() == "abc", "method lower")
assert(("x"):rep(3) == "xxx", "method rep")
assert(("s").nope == nil, "unknown string method is nil")

-- print something so the script has observable output
print("t6 strings ok")
