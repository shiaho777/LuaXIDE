-- t24_snake.lua — Snake, pure Lua, for the LuaXIDE engine.
--
-- Demonstrates what the "execution is truth" loop can do with zero C changes:
--   * the engine has no math.random  -> a tiny LCG PRNG written in Lua
--   * the engine has no timer        -> the App drives ticks via an onTick
--     handler (a function prop serialized as {"__handler":N}); the App scans
--     the returned tree, finds onTick + interval, and invokes it on a timer.
--   * no canvas                      -> the board is a grid of ui.text cells
--     (TextComponent gained a `color` prop so snake/food/empty are distinct).
--
-- Board cells:  ◉ head   ● body   ★ food   · empty
local W, H = 12, 12
local ui = require("ui")

-- ---- minimal PRNG (MINSTD; 48271 * 2^31-1 < 2^53 so doubles stay exact) ----
local seed = (os.clock() * 1000000) % 2147483647
local function rnd(n)
  seed = (seed * 48271) % 2147483647
  return (seed % n) + 1
end

-- ---- game state ----
local snake = {}   -- array of {x=,y=}, head at [1]
local dir = { x = 1, y = 0 }
local food = nil   -- {x=,y=} or nil when the board is full (you win)
local score = 0
local over = false
local paused = false
local speed = 260 -- ms per tick; the UI re-reads `interval` every tree rebuild

local function occupied(x, y)
  for i = 1, #snake do
    local s = snake[i]
    if s.x == x and s.y == y then return true end
  end
  return false
end

local function spawnFood()
  local tries = 0
  while tries < 300 do
    tries = tries + 1
    local x, y = rnd(W), rnd(H)
    if not occupied(x, y) then food = { x = x, y = y } return end
  end
  food = nil -- board full -> win state
end

local function reset()
  snake = { { x = 3, y = 6 }, { x = 2, y = 6 }, { x = 1, y = 6 } }
  dir = { x = 1, y = 0 }
  score = 0
  over = false
  paused = false
  spawnFood()
end

-- one tick: called by the App every `interval` ms via the onTick handler
local function step()
  if over or paused then return end
  local h = snake[1]
  local nx, ny = h.x + dir.x, h.y + dir.y
  if nx < 1 or nx > W or ny < 1 or ny > H then over = true return end -- wall
  local grows = food ~= nil and nx == food.x and ny == food.y
  for i = 1, #snake do
    if snake[i].x == nx and snake[i].y == ny then
      -- the tail cell frees up this tick unless we grow into it
      if not (not grows and i == #snake) then over = true return end
    end
  end
  table.insert(snake, 1, { x = nx, y = ny })
  if grows then
    score = score + 1
    spawnFood()
  else
    table.remove(snake)
  end
end

local function turn(dx, dy)
  if over or paused then return end
  local h, n = snake[1], snake[2]
  if n and n.x == h.x + dx and n.y == h.y + dy then return end -- no reversing
  dir = { x = dx, y = dy }
end

local function cell(x, y)
  if food and x == food.x and y == food.y then return "★", "#EF5350" end
  for i = 1, #snake do
    local s = snake[i]
    if s.x == x and s.y == y then
      if i == 1 then return "◉", "#66BB6A" end
      return "●", "#26A69A"
    end
  end
  return "·", "#78909C"
end

local function view()
  local rows = {}
  for y = 1, H do
    local cells = {}
    for x = 1, W do
      local ch, col = cell(x, y)
      table.insert(cells, ui.text { text = ch, size = 13, color = col, animate = false })
    end
    local row = ui.row { spacing = 0 }
    for i = 1, #cells do rawset(row, i, cells[i]) end
    table.insert(rows, row)
  end
  local board = ui.column { spacing = 0 }
  for i = 1, #rows do rawset(board, i, rows[i]) end

  local status = "Score " .. score
  if over then status = status .. "   ·   GAME OVER" end
  if paused then status = status .. "   ·   paused" end

  return ui.app {
    title = "Snake — LuaXIDE",
    ui.text { text = status, size = 15, animate = false },
    board,
    ui.row { spacing = 8,
      ui.button { text = "◀", onClick = function() turn(-1, 0) end },
      ui.button { text = "▲", onClick = function() turn(0, -1) end },
      ui.button { text = "▼", onClick = function() turn(0, 1) end },
      ui.button { text = "▶", onClick = function() turn(1, 0) end },
    },
    ui.row { spacing = 8,
      ui.button { text = paused and "▶ Play" or "⏸ Pause", onClick = function() paused = not paused end },
      ui.button { text = "↻ Restart", onClick = function() reset() end },
      ui.button { text = "＋", onClick = function() if speed > 80 then speed = speed - 40 end end },
      ui.button { text = "－", onClick = function() speed = speed + 40 end },
    },
    ui.text { text = "tick " .. speed .. "ms · 纯 Lua · 无引擎改动", size = 11, animate = false },
    -- App contract: scan the tree for onTick + interval, invoke onTick on a timer
    onTick = function() step() end,
    interval = speed,
  }
end

reset()
return view
