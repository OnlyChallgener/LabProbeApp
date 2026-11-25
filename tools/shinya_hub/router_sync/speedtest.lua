-- BE50 GlobalSpeed adapter. The transfer worker uses this router's own identity.
-- The public dev_sta contract matches the BE72 firmware speedtest module.
module("speedtest", package.seeall)

local cjson = require "cjson"
local root = "/tmp/speedtest"
local history = "/etc/speedtest/history_rst.json"
local nodes = {
  {id="301000101", name="长沙移动"},
  {id="308000101", name="衡阳移动"},
  {id="302000101", name="张家界移动"},
  {id="309000101", name="郴州移动"},
  {id="303000101", name="常德移动"},
}
local allowed = {}
for _, item in ipairs(nodes) do allowed[item.id] = true end

local function read(path)
  local file = io.open(path, "r")
  if not file then return nil end
  local data = file:read("*a")
  file:close()
  return data
end

local function process_running()
  local text = read(root .. "/worker.pid")
  local pid = text and tonumber(text)
  if not pid or pid < 2 or pid > 999999 then return false end
  return os.execute("kill -0 " .. pid .. " >/dev/null 2>&1") == 0
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
    -- EWeb's legacy Lua bridge mangles non-ASCII JSON string bytes. Keep the
    -- on-wire names as JSON Unicode escapes so the app receives Chinese text.
    return [[{"error_code":"0","msg":"success","servers":[{"id":"301000101","name":"\u957f\u6c99\u79fb\u52a8"},{"id":"308000101","name":"\u8861\u9633\u79fb\u52a8"},{"id":"302000101","name":"\u5f20\u5bb6\u754c\u79fb\u52a8"},{"id":"309000101","name":"\u90f4\u5dde\u79fb\u52a8"},{"id":"303000101","name":"\u5e38\u5fb7\u79fb\u52a8"}]}]]
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
    node = node or nodes[1].id
    if not allowed[node] then return cjson.encode({error_code="-1", msg="unknown node"}) end
    os.execute("mkdir -p " .. root .. " /etc/speedtest")
    local cmd = "/usr/bin/lua /usr/local/lua/dev_sta/shinya_globalspeed_worker.lua " .. node ..
                " </dev/null >/tmp/speedtest/worker.log 2>&1 &"
    os.execute(cmd)
    return cjson.encode({error_code="0"})
  end
  return cjson.encode({error_code="-1", msg="unknown type"})
end
