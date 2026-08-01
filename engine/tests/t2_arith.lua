-- t2: arithmetic, comparison, logical, bitwise, math
print(7 + 3, 7 - 3, 7 * 3, 7 / 2, 7 // 2, 7 % 3, 2 ^ 10)
print(1.5 + 2.5)
print(1 < 2, 2 <= 2, 3 > 4, 3 == 3, 3 ~= 4)
print(true and "yes" or "no")
print(false and "x" or "y")
print(nil and 1)
print(5 & 3, 5 | 2, 5 ~ 1, ~0, 1 << 4, 256 >> 4)
print(10 // 0 == 10/0 and "inf" or "?") -- guard: 10//0 -> error in lua, skip
