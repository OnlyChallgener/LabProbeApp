-- BE50 GlobalSpeed adapter. The transfer worker uses this router's own identity.
-- The public dev_sta contract matches the BE72 firmware speedtest module.
-- Nodes come from the CAICT match service (per-province/per-carrier, like the
-- official client); the verified static list is only a fallback.
module("shinya_speed", package.seeall)

local cjson = require "cjson"
local root = "/tmp/speedtest"
local history = "/etc/speedtest/history_rst.json"
local MATCH_URL = "https://dlcv2.cnspeedtest.cn:8443/dataServer/mobilematch_many.php?ip=&network=5&province=&city=&wifioper=&mobileoperid=0&ipv6=&model=Linux"
local fallback_nodes = {
  {id="301000101", name="长沙移动", host="111.8.9.149"},
  {id="308000101", name="衡阳移动", host="111.8.9.165"},
  {id="302000101", name="张家界移动", host="111.8.9.205"},
  {id="309000101", name="郴州移动", host="111.8.9.173"},
  {id="303000101", name="常德移动", host="111.8.9.213"},
}

local function read(path)
  local file = io.open(path, "r")
  if not file then return nil end
  local data = file:read("*a")
  file:close()
  return data
end

local function pipe(command)
  local stream = io.popen(command .. " 2>/dev/null", "r")
  if not stream then return "" end
  local result = stream:read("*a") or ""
  stream:close()
  return (result:gsub("%s+$", ""))
end

local function process_running()
  local text = read(root .. "/worker.pid")
  local pid = text and tonumber(text)
  if not pid or pid < 2 or pid > 999999 then return false end
  return os.execute("kill -0 " .. pid .. " >/dev/null 2>&1") == 0
end

-- EWeb's legacy Lua bridge mangles non-ASCII JSON bytes; escape names as \u.
local function escape_name(name)
  local out, i = {}, 1
  local bytes = {name:byte(1, -1)}
  while i <= #bytes do
    local b = bytes[i]
    local cp
    if b < 0x80 then cp = b; i = i + 1
    elseif b < 0xE0 and i + 1 <= #bytes then cp = (b % 0x20) * 0x40 + (bytes[i+1] % 0x40); i = i + 2
    elseif i + 2 <= #bytes then cp = (b % 0x10) * 0x1000 + (bytes[i+1] % 0x40) * 0x40 + (bytes[i+2] % 0x40); i = i + 3
    else cp = 0xFFFD; i = i + 1 end
    if cp < 0x80 then
      out[#out+1] = string.format("\\u%04x", cp)
    else
      out[#out+1] = string.format("\\u%04x", cp)
    end
  end
  return table.concat(out)
end

local function servers_payload(list)
  local parts = {}
  for _, n in ipairs(list) do
    parts[#parts+1] = '{"id":"' .. n.id .. '","name":"' .. escape_name(n.name) .. '"}'
  end
  return '{"error_code":"0","msg":"success","servers":[' .. table.concat(parts, ',') .. ']}'
end

-- Ask the CAICT match service for nodes matching this router's exit
-- (province + carrier resolved server-side, exactly like the official client).
local function match_nodes()
  local body = pipe("curl -sk4 --noproxy '*' --connect-timeout 3 --max-time 6 '" .. MATCH_URL .. "'")
  if body ~= "" then
    local ok, rows = pcall(cjson.decode, body)
    if ok and type(rows) == "table" then
      local list = {}
      for _, row in ipairs(rows) do
        if type(row) == "table" and row.hostid and row.hostip then
          list[#list+1] = {id=tostring(row.hostid), name=tostring(row.hostname or row.hostid), host=tostring(row.hostip)}
        end
      end
      if #list > 0 then return list end
    end
  end
  return fallback_nodes
end

local function find_node(list, id)
  for _, n in ipairs(list) do
    if n.id == id then return n end
  end
  return nil
end

function module_get(param)
  local ok, request = pcall(cjson.decode, param or "")
  if not ok or type(request) ~= "table" then
    return cjson.encode({error_code="-1", msg="invalid request"})
  end
  local kind = request.type
  if kind == "get_servers" then
    if request.sdk and request.sdk ~= "globalspeed" then
      return cjson.encode({error_code="-1", servers={}})
    end
    return servers_payload(match_nodes())
  end
  if kind == "get_proc_stat" then
    return cjson.encode({get_proc_stat=process_running() and "1" or "0"})
  end
  if kind == "get_cur_intf_rst" then
    return read(root .. "/current.json") or cjson.encode({intf={}, tmp_rst={}})
  end
  if kind == "get_his_rst" then
    return read(history) or cjson.encode({his_rst={}})
  end
  if kind == "get_last_rst" then
    return read("/etc/speedtest/last_rst.json") or cjson.encode({error_code="-1"})
  end
  if kind == "start_test" or kind == "start_test_with_server" then
    if process_running() then return cjson.encode({error_code="-99", msg="already running"}) end
    if request.sdk and request.sdk ~= "globalspeed" then
      return cjson.encode({error_code="-1", msg="globalspeed required"})
    end
    local interfaces = request.intf
    if type(interfaces) ~= "table" or #interfaces ~= 1 or interfaces[1] ~= "wan" then
      return cjson.encode({error_code="-1", msg="wan required"})
    end
    local node = request.server_id
    if not node and type(request.set_nodes) == "table" and type(request.set_nodes.wan) == "table" then
      node = request.set_nodes.wan[1]
    end
    local list = match_nodes()
    local picked = node and find_node(list, node) or list[1]
    if not picked then return cjson.encode({error_code="-1", msg="unknown node"}) end
    os.execute("mkdir -p " .. root .. " /etc/speedtest")
    local cmd = "/usr/bin/lua /usr/local/lua/dev_sta/shinya_globalspeed_worker.lua " .. picked.id ..
                " " .. picked.host .. " </dev/null >/tmp/speedtest/worker.log 2>&1 &"
    os.execute(cmd)
    return cjson.encode({error_code="0"})
  end
  return cjson.encode({error_code="-1", msg="unknown type"})
end
