-- Bounded, read-only station history and RDP preflight for the independent HUB.
package.path="/usr/lib/lua/?.lua;"..package.path
module("shinya_station",package.seeall)
local json=require("dkjson")
local nixio=require("nixio")
local fs=require("nixio.fs")
local function read(path,limit)
 local f=io.open(path,"r");if not f then return "" end
 local size=f:seek("end") or 0;f:seek("set",math.max(0,size-limit))
 local value=f:read(limit) or "";f:close();return value
end
local function stamp(s)
 if type(s)~="string" then return nil end
 local y,m,d,h,n,t=s:match("^(%d%d%d%d)%-(%d%d)%-(%d%d) (%d%d):(%d%d):(%d%d)$")
 if not y then return nil end
 return os.time({year=tonumber(y),month=tonumber(m),day=tonumber(d),hour=tonumber(h),min=tonumber(n),sec=tonumber(t)})
end
local function history()
 local now=os.time();local rows={};local seen={}
 local boot=read("/proc/sys/kernel/random/boot_id",100):gsub("%s","")
 local uptime=tonumber(read("/proc/uptime",100):match("^[%d%.]+"))
 local function add(mac,kind,at,id,source)
  if type(mac)~="string" or not at or at<now-4*86400 or at>now+5 then return end
  mac=mac:lower():gsub("[%.:]","")
  if #mac~=12 or not mac:match("^%x+$") then return end
  local key=mac..":"..kind..":"..id
  if seen[key] or #rows>=2500 then return end
  seen[key]=true;rows[#rows+1]={mac=mac,eventType=kind,eventTime=math.floor(at),eventKey=key,source=source}
 end
 for _,path in ipairs({"/tmp/log/hostapd.log.old","/tmp/log/hostapd.log"}) do
  if uptime then
   for line in read(path,1048576):gmatch("[^\r\n]+") do
    local elapsed,kind,mac=line:match("^([%d%.]+):.-AP%-STA%-(%u+) ([%x:]+)")
    if kind=="CONNECTED" or kind=="DISCONNECTED" then
     add(mac,kind=="CONNECTED" and 1 or 2,now-uptime+tonumber(elapsed),boot..":"..elapsed,"hostapd")
    end
   end
  end
 end
 for _,directory in ipairs({"/tmp/ap_log/all/sta","/tmp/ap_log/global/all/sta","/tmp/ap_log/all/wiresta","/tmp/ap_log/global/all/wiresta"}) do
  local names={};local iterator=fs.dir(directory)
  if iterator then for name in iterator do if name:match("^[%w_%-]+$") then names[#names+1]=name end end end
  table.sort(names)
  for i=math.max(1,#names-15),#names do
   for line in read(directory.."/"..names[i],200000):gmatch("[^\r\n]+") do
    local v=json.decode(line)
    if type(v)=="table" then
     if v.log_type=="on" or v.log_type=="update" or v.log_type=="updata" or v.log_type=="off" then
      local at=stamp(v.online_time);add(v.mac,1,at,tostring(at),"native_sta_log")
      if v.log_type=="off" then at=stamp(v.offline_time);add(v.mac,2,at,tostring(at),"native_sta_log") end
     end
    end
   end
  end
 end
 -- The native current association time survives log rotation.
 local ok,native=pcall(function() return json.decode(require("dev_sta").fetch("get","user_list").data) end)
 if ok and type(native)=="table" and type(native.list)=="table" then
  for _,v in ipairs(native.list) do local at=stamp(v.onlinetime);add(v.mac,1,at,tostring(at),"native_user_list") end
 end
 return {observedAt=now,bootId=boot,events=rows}
end
local function rdp(ip)
 if type(ip)~="string" then return {error="invalid station IP"} end
 local octets={};for v in ip:gmatch("%d+") do octets[#octets+1]=tonumber(v) end
 if #octets~=4 or not ip:match("^%d+%.%d+%.%d+%.%d+$") then return {error="invalid station IP"} end
 for _,v in ipairs(octets) do if v>255 then return {error="invalid station IP"} end end
 local s=nixio.socket("inet","stream");if not s then return {error="socket unavailable"} end
 s:setblocking(false)
 local connected=s:connect(ip,3389)
 if not connected then
  local ready=nixio.poll({{fd=s,events=nixio.poll_flags("out")}},3000)
  connected=ready and ready>0 and s:getopt("socket","error")==0
 end
 local valid=false
 if connected then
  -- X.224 negotiation requests TLS/NLA only; no login or credential exchange.
  s:send(string.char(3,0,0,19,14,224,0,0,0,0,0,1,0,8,0,3,0,0,0))
  local ready=nixio.poll({{fd=s,events=nixio.poll_flags("in")}},2500)
  if ready and ready>0 then
   local reply=s:recv(64)
   valid=type(reply)=="string" and #reply>=11 and reply:byte(1)==3 and reply:byte(6)==208
  end
 end
 s:close();return {ip=ip,port=3389,open=valid,observedAt=os.time()}
end
function module_get(param)
 local r=json.decode(param or "{}") or {}
 if r.action=="history" then return json.encode(history()) end
 if r.action=="rdp" then return json.encode(rdp(r.ip)) end
 return json.encode({error="unknown station read"})
end
