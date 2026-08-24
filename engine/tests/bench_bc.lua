-- bench_bc.lua — bytecode VM vs tree-walk micro-benchmark.
-- Run with the VM enabled (default) and LUAX_NO_BC=1; compare wall times.
-- Functions are global so their bodies compile (locals of the main chunk that
-- are captured by nested closures force the tree-walk fallback by design).

function bfib(n) if n < 2 then return n end return bfib(n - 1) + bfib(n - 2) end

local function bench_fib()
  local t0 = os.clock()
  bfib(23)
  return os.clock() - t0
end

local function bench_loop()
  local t0 = os.clock()
  local s = 0
  for i = 1, 3000000 do s = s + i % 7 end
  return os.clock() - t0, s
end

local function bench_string()
  local t0 = os.clock()
  local acc = ""
  for i = 1, 40000 do acc = acc .. "ab" end
  local n = #acc
  for i = 1, 20000 do n = #tostring(i) - n end
  return os.clock() - t0, n
end

local function bench_table()
  local t0 = os.clock()
  local tt = {}
  for i = 1, 200000 do tt[i] = { id = i, sq = i * i } end
  local sum = 0
  for _, rec in pairs(tt) do sum = sum + rec.sq end
  return os.clock() - t0, sum
end

local f = bench_fib()
local l, lsum = bench_loop()
local s, sn = bench_string()
local tb, tsum = bench_table()

-- this engine's string lib has no format(); truncate via sub
local function f3(x)
  local str = tostring(x)
  local dot = 0
  for i = 1, #str do if string.sub(str, i, i) == "." then dot = i end end
  if dot == 0 then return str .. ".000" end
  return string.sub(str, 1, dot + 3)
end

print("fib(23)         " .. f3(f) .. "s")
print("loop 3e6        " .. f3(l) .. "s  (sum " .. lsum .. ")")
print("string 60k ops  " .. f3(s) .. "s")
print("table 2e5 rows  " .. f3(tb) .. "s  (sum " .. tsum .. ")")
