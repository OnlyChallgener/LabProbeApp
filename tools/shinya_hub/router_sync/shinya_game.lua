-- Game telemetry and narrowly validated controls. All module/command names
-- are fixed. No route, radio channel or feature-library operation is exposed.
package.path = "/usr/lib/lua/?.lua;" .. package.path
module("shinya_game", package.seeall)
local json = require("dkjson")
local HOSTS = {"speed.ino.qq.com", "party.163.com", "ys.mihoyo.com"}

local function executable(name, fallback)
  local pipe=io.popen("command -v "..name.." 2>/dev/null", "r")
  local value=(pipe:read("*a") or ""):gsub("%s+$", "")
  pipe:close()
  return value ~= "" and value or fallback
end
local PING=executable("ping","/bin/ping")
-- Bound to the statically audited BE72 writer, not guessed event enums.
local LOW_RATE_SHA="e024aa8a34952b2ecab2c824ef239303af4cd792be1234081703228a40654dbd"
local function guard_events()
  local pipe=io.popen("sha256sum /usr/sbin/low_rate.elf 2>/dev/null","r")
  local sha=(pipe:read("*a") or ""):match("^(%x+)");pipe:close()
  if sha~=LOW_RATE_SHA then return nil end
  local rows=setmetatable({},{__jsontype="array"})
  local function append(path)
    local f=io.open(path,"r");if not f then return end
    local size=f:seek("end") or 0;local offset=math.max(0,size-32768)
    f:seek("set",offset);if offset>0 then f:read("*l") end
    for line in f:lines() do
      if #line<=2048 then
        local value=json.decode(line)
        if type(value)=="table" then rows[#rows+1]=value end
      end
    end
    f:close()
  end
  append("/tmp/game_wifi/low_rate_upload.log")
  local ok,lfs=pcall(require,"lfs")
  if not ok then return nil end
  if lfs.attributes("/tmp/mvp_edge/bus_upload/","mode")=="directory" then
    local files={}
    for name in lfs.dir("/tmp/mvp_edge/bus_upload/") do
      if name:match("^low_rate_upload_%d+%.log$") then files[#files+1]=name end
    end
    table.sort(files)
    for i=math.max(1,#files-3),#files do append("/tmp/mvp_edge/bus_upload/"..files[i]) end
  end
  return rows
end

local function telemetry()
  local conn=require("ubus").connect()
  if not conn then return {error="Firmware bus unavailable"} end
  local function read(object,method)
    local ok,value=pcall(conn.call,conn,object,method,{})
    return ok and value or nil
  end
  local value={source="firmware.rcgame",observedAt=os.time(),
               active=read("rcgame","games") or {},
               rounds=read("round_identify","get"),probes=read("icmp-checker","show") or {}}
  conn:close()
  local log={}
  for _,name in ipairs({"/tmp/log/rcgame_status.log.old","/tmp/log/rcgame_status.log.1","/tmp/log/rcgame_status.log","/tmp/log/rcgame_status_upload.log"}) do
    local f=io.open(name,"r")
    if f then
      local size=f:seek("end") or 0
      f:seek("set",math.max(0,size-16384))
      log[#log+1]=f:read("*a") or "";f:close()
    end
  end
  local ok,lfs=pcall(require,"lfs")
  if ok and lfs.attributes("/tmp/mvp_edge/bus_upload/","mode")=="directory" then
    local files={}
    for name in lfs.dir("/tmp/mvp_edge/bus_upload/") do
      if name:match("^rcgame_status_upload_%d+%.log$") then files[#files+1]=name end
    end
    table.sort(files)
    for i=math.max(1,#files-3),#files do
      local f=io.open("/tmp/mvp_edge/bus_upload/"..files[i],"r")
      if f then local size=f:seek("end") or 0;f:seek("set",math.max(0,size-16384));log[#log+1]=f:read("*a") or "";f:close() end
    end
  end
  value.events=table.concat(log,"\n")
  local guard=guard_events()
  value.guardLogAvailable=guard~=nil
  value.guardEvents=guard or {}
  if guard then value.guardWriterSha256=LOW_RATE_SHA end
  return value
end

local function probe(host)
  local pipe=io.popen("timeout -t 8 "..PING.." -n -c 3 -W 1 "..host.." 2>/dev/null", "r")
  local output=pipe:read("*a") or ""
  pipe:close()
  local sent,received=output:match("(%d+) packets transmitted, (%d+) packets received")
  if not sent then sent,received=output:match("(%d+) packets transmitted, (%d+) received") end
  local lo,avg,hi=output:match("min/avg/max[^=]*=%s*([%d%.]+)/([%d%.]+)/([%d%.]+)")
  local samples={}
  for value in output:gmatch("time[=<]([%d%.]+)%s*ms") do samples[#samples+1]=tonumber(value) end
  sent=tonumber(sent);received=tonumber(received)
  local row={host=host,observedAt=os.time(),sent=sent or 0,received=received or 0,samples=samples}
  if sent and sent>0 then row.lossRate=(sent-received)*100/sent end
  if avg then row.min=tonumber(lo);row.avg=tonumber(avg);row.max=tonumber(hi) end
  row.status=avg and received and received>0 and "success" or "fail"
  if row.status=="fail" then row.message=sent and "ICMP probes received no reply" or "Host resolution or probe failed" end
  return row
end

local native
function module_get(param)
  local request=json.decode(param or "{}") or {}
  if request.action=="catalog" then
    local f=io.open("/usr/share/ndpi/db.default.json","r")
    if not f then return json.encode({error="Game recognition library unavailable"}) end
    local raw=f:read(1048577);f:close()
    if not raw or #raw>1048576 then return json.encode({error="Game recognition library invalid"}) end
    local library=json.decode(raw)
    if type(library)~="table" or type(library.apps)~="table" then return json.encode({error="Game recognition library invalid"}) end
    local ids={}
    -- Confirm loaded classifier IDs, rather than advertising a file that has
    -- not been loaded into the running firmware classifier.
    local p=io.popen("rdpi -t 2>/dev/null","r")
    local loaded=p:read(524289) or "";p:close()
    if #loaded>524288 then return json.encode({error="Loaded game recognition table invalid"}) end
    local current={}
    for line in loaded:gmatch("[^\n]+") do
      local id=line:match("([34]%-%d+%-%d+%-%d+)%s*$")
      if id then current[id]=true end
    end
    for _,app in ipairs(library.apps) do
      local id=tostring(app.index or "")
      if id:match("^[34]%-%d+%-%d+%-0$") and current[id] then ids[#ids+1]=id end
    end
    return json.encode({source="firmware.rdpi",ids=ids,version=library.version,observedAt=os.time()})
  end
  if request.action=="telemetry" then return json.encode(telemetry()) end
  if request.action=="status" then
    return json.encode({source="current-router-firmware",rcgame=native("dev_sta","get","rcgame"),
      flowctrl=native("dev_config","get","flowctrl"),qos=native("dev_config","get","flowctrl_qos"),
      lowRate=native("ac_config","get","lowRate"),policy=json.decode(module_get(json.encode({action="policy"})))})
  end
  if request.action=="policy" then
    local conn=require("ubus").connect()
    if not conn then return json.encode({error="Firmware bus unavailable"}) end
    local ok,state=pcall(conn.call,conn,"rcgame","status",{})
    conn:close()
    if not ok or type(state)~="table" then return json.encode({error="Game policy unavailable"}) end
    local value={source="firmware.rcgame"}
    for _,k in ipairs({"air_group","whole_air_rate_threshold","single_air_rate_threshold","whole_air_rate_count_limit","single_air_adjust_count_limit","group_flowctrl_level","whole_d_threshold_pre","whole_d_reserve"}) do value[k]=state[k] end
    return json.encode(value)
  end
  if request.action and request.action~="probe" then return json.encode({error="Unsupported game action"}) end
  local rows={}
  for _,host in ipairs(HOSTS) do rows[#rows+1]=probe(host) end
  return json.encode({source="firmware.shinya_game",observedAt=os.time(),rows=rows})
end

native=function(namespace,action,name,data)
  local command=namespace.." "..action.." -m "..name
  if data then command=command.." '"..json.encode(data):gsub("'","'\\''").."'" end
  local p=io.popen(command.." 2>/dev/null","r")
  local output=p:read("*a") or "";p:close()
  return json.decode(output)
end

local function priority(enabled)
  local rules=native("dev_config","get","flowctrl_qos")
  local before=native("dev_sta","get","rcgame")
  local rule
  for _,r in ipairs(rules and rules.list or {}) do if r.comment=="games" then rule=r end end
  if not rule or (rule.enable~="on" and rule.enable~="off") or
     not tostring(rule.tc_pri):match("^%d+$") or
     not before or (before.air_flowctrl~="0" and before.air_flowctrl~="1") then
    return {error="Current firmware has no audited game priority controls"}
  end
  local wanted=enabled and "on" or "off"
  local wanted_air=enabled and "1" or "0"
  local function apply(enable,air)
    native("dev_config","update","flowctrl_qos",{list={{comment="games",enable=enable,tc_pri=rule.tc_pri}}})
    local bus=require("ubus").connect()
    if not bus then error("Firmware bus unavailable") end
    local ok,value=pcall(bus.call,bus,"rcgame",air=="1" and "enable" or "disable",{mod="air_flowctrl"})
    bus:close();if not ok then error("Game air priority command failed") end
  end
  local function confirm(enable,air)
    local q=native("dev_config","get","flowctrl_qos")
    local g=native("dev_sta","get","rcgame")
    for _,r in ipairs(q and q.list or {}) do
      if r.comment=="games" then return r.enable==enable and g and g.air_flowctrl==air end
    end
    return false
  end
  local ok=pcall(apply,wanted,wanted_air)
  if not ok or not confirm(wanted,wanted_air) then
    local restored=pcall(apply,rule.enable,before.air_flowctrl)
    return {error=restored and confirm(rule.enable,before.air_flowctrl) and
      "Game priority readback mismatch; restored previous configuration" or "Game priority restoration failed"}
  end
  return {result="success",gamePriority=enabled and 1 or 0,air_flowctrl=wanted_air,source="current-router-firmware"}
end

function module_set(param)
  local request=json.decode(param or "{}") or {}
  if request.action=="gamePriority" and type(request.enable)=="boolean" then
    return json.encode(priority(request.enable))
  end
  if request.action=="flowMode" and (request.mode==0 or request.mode==1) then
    -- Exact profiles measured by switching the official two-level control.
    -- rcgame.get omits these parameters; its ubus status is the readback.
    local before=json.decode(module_get(json.encode({action="policy"})))
    if not before or not before.air_group or #before.air_group~=5 then
      return json.encode({error="Current firmware has no audited air-priority profile"})
    end
    local rates=request.mode==0 and {3,15,60,240,400} or {2,10,30,100,200}
    local groups=json.decode(json.encode(before.air_group))
    for _,r in ipairs(groups) do
      local i=tonumber(tostring(r.group_name):match("^air_group_([1-5])$"))
      if not i then return json.encode({error="Unrecognized game air-priority group"}) end
      r.limitrate=rates[i]
    end
    local wanted={air_group=groups,whole_air_rate_threshold=request.mode==0 and 50 or 30}
    local function apply(data)
      local bus=require("ubus").connect();if not bus then return false end
      local ok=pcall(bus.call,bus,"rcgame","air_flowctrl",data);bus:close();if not ok then return false end
      local after=json.decode(module_get(json.encode({action="policy"})))
      if not after or after.whole_air_rate_threshold~=data.whole_air_rate_threshold then return false end
      for _,r in ipairs(data.air_group) do
        local found
        for _,a in ipairs(after.air_group or {}) do if a.group_name==r.group_name then found=a end end
        if not found or found.limitrate~=r.limitrate or found.nego_rate_min~=r.nego_rate_min or found.nego_rate_max~=r.nego_rate_max then return false end
      end
      return true
    end
    if not apply(wanted) then
      return json.encode({error=apply({air_group=before.air_group,whole_air_rate_threshold=before.whole_air_rate_threshold}) and
        "Flow level readback mismatch; restored previous configuration" or "Flow level restoration failed"})
    end
    return json.encode({result="success",flowCtrlMode=request.mode,source="current-router-firmware"})
  end
  if request.action=="flowBand" and type(request.band)=="number" and
     request.band==math.floor(request.band) and request.band>=30 and request.band<=3000 then
    local before=native("dev_config","get","flowctrl")
    local row
    for _,r in ipairs(before and before.list or {}) do if r.ifname=="br-wan" then row=r end end
    if not row or not tostring(row.downloadBand):match("^%d+$") then
      return json.encode({error="Current firmware has no audited WAN bandwidth"})
    end
    local old=tostring(row.downloadBand)
    local wanted=tostring(request.band)
    local function unchanged(after)
      for _,r in ipairs(before.list or {}) do
        local found
        for _,a in ipairs(after.list or {}) do if a.ifname==r.ifname then found=a end end
        if not found then return false end
        for k,v in pairs(r) do
          if not (r.ifname=="br-wan" and k=="downloadBand") and tostring(found[k])~=tostring(v) then return false end
        end
      end
      for _,k in ipairs({"tcSwitch","reserve_switch","up_res_proportion","down_res_proportion","p2pSwtich","wanNum"}) do
        if before[k]~=nil and tostring(after[k])~=tostring(before[k]) then return false end
      end
      return true
    end
    local function apply(value)
      -- This firmware uses set for WAN bandwidth. Preserve the full
      -- existing list and the fixed global configuration fields.
      local saved={list=json.decode(json.encode(before.list))}
      for _,r in ipairs(saved.list) do if r.ifname=="br-wan" then r.downloadBand=value end end
      for _,k in ipairs({"tcSwitch","reserve_switch","up_res_proportion","down_res_proportion","p2pSwtich","wanNum","version"}) do saved[k]=before[k] end
      native("dev_config","set","flowctrl",saved)
      local after=native("dev_config","get","flowctrl")
      for _,r in ipairs(after and after.list or {}) do
        if r.ifname=="br-wan" then return tostring(r.downloadBand)==value and unchanged(after) end
      end
      return false
    end
    if not apply(wanted) then
      local saved={list=before.list}
      for _,k in ipairs({"tcSwitch","reserve_switch","up_res_proportion","down_res_proportion","p2pSwtich","wanNum","version"}) do saved[k]=before[k] end
      native("dev_config","set","flowctrl",saved)
      local restored=native("dev_config","get","flowctrl")
      return json.encode({error=restored and unchanged(restored) and "Bandwidth readback mismatch; restored previous configuration" or "Bandwidth restoration failed"})
    end
    return json.encode({result="success",flowCtrlBand=request.band,source="current-router-firmware"})
  end
  return json.encode({error="Unsupported game control"})
end
