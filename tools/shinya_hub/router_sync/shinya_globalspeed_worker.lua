-- Router-local ARMv7 GlobalSpeed transfer worker for BE50.
-- One bounded session, using this router's own sys_mac and br-wan.
local cjson = require "cjson"
local root = "/tmp/speedtest"
local config = {
  ["308000101"]={host="111.8.9.165", name="衡阳移动"},
  ["301000101"]={host="111.8.9.149", name="长沙移动"},
  ["302000101"]={host="111.8.9.205", name="张家界移动"},
  ["309000101"]={host="111.8.9.173", name="郴州移动"},
  ["303000101"]={host="111.8.9.213", name="常德移动"},
}
local node = config[arg[1] or ""]
if arg[2] and #arg[2] > 3 then
  node = {host = arg[2], name = (node and node.name) or arg[1] or "node"}
end
if not node then os.exit(2) end
os.execute("mkdir -p " .. root .. " /etc/speedtest")
local pid = assert(io.open(root .. "/worker.pid", "w"))
local stat = assert(io.open("/proc/self/stat", "r")):read("*l")
pid:write(stat:match("^(%d+)") or "0")
pid:close()

local function pipe(command)
  local stream = io.popen(command .. " 2>/dev/null", "r")
  if not stream then return "" end
  local result = stream:read("*a") or ""
  stream:close()
  local clean = result:gsub("%s+$", "")
  return clean
end
local function atomic(path, value)
  local tmp = path .. ".new"
  local file = assert(io.open(tmp, "w"))
  file:write(cjson.encode(value))
  file:close()
  assert(os.rename(tmp, path))
end
local function md5(value)
  return pipe("printf '%s' '" .. value .. "' | md5sum"):match("^([0-9a-f]+)")
end
local state = {intf={"wan"}, stat="start", stage="connecting", started_at=os.time(), error_code="0", tmp_rst={wan={}}}
local function current()
  atomic(root .. "/current.json", state)
end
-- curl's own progress meter reports the transfer's current byte rate. Read
-- the meter as it arrives; do not infer speeds from total interface traffic.
local function meter_rate(line)
  local fields = {}
  for item in line:gmatch("%S+") do fields[#fields+1] = item end
  if #fields ~= 12 or not fields[1]:match("^%d+$") or not fields[3]:match("^%d+$") then return nil end
  local amount, suffix = fields[12]:match("^(%d+%.?%d*)([kKmMgG]?)$")
  amount = tonumber(amount)
  if not amount then return nil end
  local scale = ({k=1024,m=1024^2,g=1024^3})[suffix:lower()] or 1
  return amount * scale * 8 / 1000000
end
local function transfer(command, field)
  local output_file=root .. "/transfer-result.txt"
  os.remove(output_file)
  local stream=assert(io.popen(command .. " 3>&1 1>" .. output_file .. " 2>&3", "r"))
  local line=""
  while true do
    local ch=stream:read(1)
    if not ch then break end
    if ch=="\r" or ch=="\n" then
      local speed=meter_rate(line)
      if speed then
        local values=state.tmp_rst.wan[field] or {}
        if #values<60 then values[#values+1]=tonumber(string.format("%.2f",speed)) end
        state.tmp_rst.wan[field]=values
        current()
      end
      line=""
    elseif #line<1024 then line=line .. ch end
  end
  stream:close()
  local f=io.open(output_file,"r");local result=f and f:read("*a") or ""
  if f then f:close() end
  os.remove(output_file)
  return result:gsub("%s+$","")
end
local function fail(message)
  state.stat = "end"
  state.error_code = "-1"
  state.msg = message
  current()
  os.remove(root .. "/worker.pid")
end
local function positive(value)
  local number = tonumber(value)
  return number and number == number and number > 0 and number < 10000000000 and number or nil
end
local function fixed(value)
  return string.format("%.2f", value)
end
-- Sustained-window rate like a normal speed test: hold the transfer for the
-- whole window, drop the ramp-up seconds and average the meter's per-second
-- rates; fall back to overall bytes/time when samples are sparse.
local function steady_mbps(samples, bytes, seconds)
  if samples and #samples >= 4 then
    local sum, count = 0, 0
    for index = 3, #samples do
      sum = sum + samples[index]
      count = count + 1
    end
    if count > 0 then return fixed(sum / count) end
  end
  return fixed(bytes * 8 / seconds / 1000000)
end
local function measure()
  current()
  local info = cjson.decode(pipe("dev_sta get -m sysinfo"))
  local mac = info and info.sys_mac or ""
  if not mac:match("^%x%x:%x%x:%x%x:%x%x:%x%x:%x%x$") then error("device identity unavailable") end
  local imei = "RJIMEI" .. mac
  local base = "http://" .. node.host .. ":65499"
  state.stage = "latency"
  current()
  local latency = {}
  for _ = 1, 3 do
    local output = pipe("curl --noproxy '*' --interface br-wan --connect-timeout 3 --max-time 4 " ..
      "-s -o /dev/null -w '%{time_connect}' '" .. base .. "/'")
    local value = positive(output)
    if value then latency[#latency + 1] = value * 1000 end
  end
  local latency_ms, jitter_ms
  if #latency > 0 then
    local sum, differences = 0, 0
    for index, sample in ipairs(latency) do
      sum = sum + sample
      if index > 1 then differences = differences + math.abs(sample - latency[index-1]) end
    end
    latency_ms = fixed(sum / #latency)
    if #latency > 1 then jitter_ms = fixed(differences / (#latency - 1)) end
  end
  state.tmp_rst.wan.latency = latency_ms
  state.tmp_rst.wan.jitter = jitter_ms
  state.stage = "connecting"
  current()
  local now = os.time()
  local bandwidth = 100
  local token = md5(md5("model=RJRouterPlus&imei=" .. imei) ..
                   md5("stime=" .. now .. "&band=" .. bandwidth .. "&rand=12345555"))
  if not token or #token ~= 32 then error("session token unavailable") end
  local query = "flag=true&bandwidth=100&model=RJRouterPlus&imei=RJIMEI" ..
                mac:gsub(":", "%%3A") .. "&time=" .. now .. "&token=" .. token ..
                "&pkg=rjrt&rnd=" .. now
  local response = pipe("curl --noproxy '*' --interface br-wan --connect-timeout 3 --max-time 8 " ..
                        "-s '" .. base .. "/speed/dovalid?" .. query .. "'")
  local key = response:match("^1%-([0-9a-fA-F%-]+)$")
  if not key or #key ~= 36 then error("GlobalSpeed session rejected") end
  state.stat = "running"
  state.stage = "download"
  current()
  state.tmp_rst.wan.downspeed = {}
  local url = base .. "/speed/File(1G).dl?r=" .. now .. "&key=" .. key
  -- Hold the download for a short steady window (8s of the ~20s total
  -- budget); the endpoint serves a 1 GiB file so the transfer is
  -- time-bounded, not size-bounded.
  local output = transfer("curl --noproxy '*' --interface br-wan --connect-timeout 3 --max-time 8 " ..
    "-o /dev/null -A 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36' " ..
    "-H 'Connection: close' -w '%{http_code} %{size_download} %{time_total}' '" .. url .. "'", "downspeed")
  local code, bytes, seconds = output:match("(%d%d%d)%s+([%d%.]+)%s+([%d%.]+)$")
  bytes, seconds = positive(bytes), positive(seconds)
  if code ~= "200" or not bytes or not seconds or bytes < 100000 then error("GlobalSpeed download failed") end
  local down = steady_mbps(state.tmp_rst.wan.downspeed, bytes, seconds)
  state.stage = "upload"
  current()
  local payload = root .. "/upload.bin"
  -- Use a larger real upload only when both RAM and the temporary filesystem
  -- have headroom. Fast links can finish the old 8 MiB before curl's first
  -- progress sample; this increases measurement time without inventing data.
  local free_kb=tonumber(pipe("df -Pk " .. root .. " | awk 'NR==2 {print $4}'")) or 0
  local mem_kb=tonumber(pipe("awk '/^MemAvailable:/ {print $2}' /proc/meminfo")) or 0
  local payload_bytes=(free_kb>=98304 and mem_kb>=98304) and 16777216 or 8388608
  local prepared=os.execute("head -c " .. payload_bytes .. " /dev/zero > " .. payload)
  if prepared ~= 0 and prepared ~= true then error("upload buffer unavailable") end
  -- Sustained upload inside the ~20s total budget: repeat real payload
  -- rounds until the steady window is long enough. Round 1 is ramp-up and
  -- is excluded from the reported rate; a later refusal keeps earlier
  -- rounds instead of failing the whole test.
  state.tmp_rst.wan.upspeed = {}
  local steady_bytes, steady_seconds, rounds = 0, 0, 0
  local up_code, up_bytes, up_seconds
  local upload_deadline = os.time() + 12
  while rounds < 4 and os.time() < upload_deadline do
    rounds = rounds + 1
    local up_output = transfer("curl --noproxy '*' --interface br-wan --connect-timeout 3 --max-time 12 " ..
      "-o /dev/null -w '%{http_code} %{size_upload} %{time_total}' -H 'Key: " .. response ..
      "' -F 'upload=@" .. payload .. ";filename=SPEED_" .. now .. "_" .. rounds .. "' '" ..
      base .. "/speed/doAnalsLoad.do'", "upspeed")
    up_code, up_bytes, up_seconds = up_output:match("(%d%d%d)%s+([%d%.]+)%s+([%d%.]+)$")
    up_bytes, up_seconds = positive(up_bytes), positive(up_seconds)
    if up_code ~= "200" or not up_bytes or not up_seconds or up_bytes < payload_bytes then
      if rounds == 1 then error("GlobalSpeed upload failed") end
      break
    end
    if rounds >= 2 then
      steady_bytes = steady_bytes + up_bytes
      steady_seconds = steady_seconds + up_seconds
    end
    if rounds >= 2 and steady_seconds >= 5 then break end
  end
  os.remove(payload)
  local up
  if steady_seconds > 0 then
    up = steady_bytes * 8 / steady_seconds / 1000000
  else
    up = up_bytes * 8 / up_seconds / 1000000
  end
  state.tmp_rst.wan.latency = latency_ms
  state.tmp_rst.wan.jitter = jitter_ms
  local record = {timestamp=tostring(now), intf={"wan"}, stat="end", error_code="0",
    tmp_rst={wan={downspeed=fixed(down), upspeed=fixed(up), latency=latency_ms,
                  jitter=jitter_ms}}}
  atomic("/etc/speedtest/last_rst.json", record)
  local history_file = io.open("/etc/speedtest/history_rst.json", "r")
  local history = {his_rst={}}
  if history_file then
    local content = history_file:read("*a") or ""
    history_file:close()
    local ok, decoded = pcall(cjson.decode, content)
    if ok and type(decoded) == "table" and type(decoded.his_rst) == "table" then history = decoded end
  end
  table.insert(history.his_rst, 1, record)
  while #history.his_rst > 20 do table.remove(history.his_rst) end
  atomic("/etc/speedtest/history_rst.json", history)
  state.stat = "end"
  state.stage = "complete"
  current()
end

local ok, message = pcall(measure)
if not ok then fail(tostring(message):gsub("^.-: ", "")) end
os.remove(root .. "/upload.bin")
os.remove(root .. "/worker.pid")
