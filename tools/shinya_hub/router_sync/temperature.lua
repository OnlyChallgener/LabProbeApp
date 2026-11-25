-- Read-only BE50 temperature sensors; missing readings remain absent.
-- Keep CPU and radio measurements separate; missing readings remain absent.
local M = {}
local function read(path)
  local f = io.open(path, 'r')
  if not f then return nil end
  local text = f:read(128); f:close(); return text
end
local function valid(value)
  local n = tonumber(value)
  if n and n == n and n >= -40 and n <= 150 then return n end
end
function M.parse_radio(text)
  if type(text) ~= 'string' then return nil end
  return valid(text:match('CurrentTemperature%s*=%s*(%-?%d+%.?%d*)'))
end
function M.read()
  local result = {temperatureSource = {}, temperatureObservedAt = os.time()}
  local kind = read('/sys/class/thermal/thermal_zone0/type') or ''
  if kind:match('^cpu%-thermal%s*$') then
    local raw = tonumber(read('/sys/class/thermal/thermal_zone0/temp'))
    local cpu = raw and valid(raw / 1000)
    if cpu then
      result.temp = cpu
      result.temperatureSource.temp = 'sysfs:cpu-thermal:millidegree-celsius'
    end
  end
  -- Fixed interfaces/arguments; caller input never reaches the shell.
  for _, pair in ipairs({{'ra0', 'temp_2g'}, {'rai0', 'temp_5g'}}) do
    local pipe = io.popen('timeout -t 2 /usr/sbin/iwpriv ' .. pair[1] .. ' stat 2>/dev/null', 'r')
    if pipe then
      local text = pipe:read(32768) or ''; pipe:close()
      local value = M.parse_radio(text)
      if value then
        result[pair[2]] = value
        result.temperatureSource[pair[2]] = 'iwpriv:' .. pair[1] .. ':CurrentTemperature'
      end
    end
  end
  return result
end
function M.enrich(overview, sn)
  if type(overview) ~= 'table' then return overview end
  local readings = M.read()
  local sources, added = {}, false
  for _, field in ipairs({'temp', 'temp_2g', 'temp_5g'}) do
    if valid(overview[field]) == nil and readings[field] ~= nil then
      overview[field] = readings[field]
      sources[field] = readings.temperatureSource[field]
      added = true
    end
  end
  if added then
    overview.temperatureSource = sources
    overview.temperatureObservedAt = readings.temperatureObservedAt
  end
  return overview
end
return M
