-- Scoped child guard reader. It never changes the RDPI feature library.
package.path = "/usr/lib/lua/?.lua;" .. package.path
module("shinya_child", package.seeall)
local json = require("dkjson")
local function arr(v)
  if type(v) == "table" then return v end
  if v then return {v} end
  return {}
end
local function config()
  local u = require("luci.model.uci").cursor()
  local users = {}
  u:foreach("child_guard", "user", function(s)
    local row = {uid=s[".name"],name=s.name,macs=arr(s.mac),block=tonumber(s.block) or 0,
                 pause=tonumber(s.pause) or 0,delayedBlock=tonumber(s.shinya_block_at) or 0,reload=s.reload,policies={}}
    for _,pid in ipairs(arr(s.policy)) do
      local p = u:get_all("child_guard",pid)
      if p then
        local tr = {}
        for _,t in ipairs(arr(u:get("child_guard",p.tr,"time"))) do
          local day,a,b = t:match("^(%a+)%-(%d%d:%d%d)%-(%d%d:%d%d)$")
          if day then tr[day]=tr[day] or {};table.insert(tr[day],{a,b}) end
        end
        table.insert(row.policies,{id=pid,type=p.type,tr=tr,app=arr(p.app),
          app_fbt_list=arr(p.app_fbt_list),url_fbt_list=arr(p.url_fbt_list),url_fbt_enable=p.url_fbt_enable})
      end
    end
    table.insert(users,row)
  end)
  return {users=users,observedAt=os.time(),rdpiEnabled=u:get("child_guard","config","rdpi_enable")=="1"}
end
local function runtime(req)
  local u = require("luci.model.uci").cursor()
  if type(req.uid)~="string" or not req.uid:match("^%x+$") or #req.uid~=32 then
    return {error="invalid uid"}
  end
  local conn=require("ubus").connect()
  if not conn then return {error="runtime unavailable"} end
  local all=conn:call("sniffer.user","show",{}) or {}
  local policies=conn:call("sniffer.policy","show",{}) or {}
  conn:close()
  local user
  for _,r in ipairs(all.user or {}) do if r.uid==req.uid then user=r end end
  local sets={}
  for _,name in ipairs({"child_guard_block","child_guard_skip","child_guard_ban","child_guard_ip6_block"}) do
    local f=io.popen("ipset list "..name.." 2>/dev/null")
    local members={}
    if f then
      for m in f:read("*a"):gmatch("[^\r\n]+") do
        if #m==17 and m:match("^%x%x:%x%x:%x%x:%x%x:%x%x:%x%x$") then members[m:lower()]=true end
      end
      f:close()
    end
    sets[name]=members
  end
  return {user=user,policies=policies.policy or {},sets=sets,
    rdpiEnabled=u:get("child_guard","config","rdpi_enable")=="1"}
end
local function minutes(req)
  local f=io.open("/tmp/labprobe_minute_store.json","r")
  if not f then return {error="minute collector unavailable"} end
  local raw=f:read("*a");f:close()
  local src=json.decode(raw)
  if not src or tonumber(src.version or 0)<3 then return {error="unsupported minute ledger"} end
  local out={version=src.version,generatedAt=src.lastSample,appMinutes={},deviceMinutes={},traffic={}}
  local since=math.max(0,tonumber(req.sinceEpoch) or 0)
  local closed=math.floor(tonumber(src.lastSample or 0)/60)*60
  local day=req.date
  if day and (type(day)~="string" or not day:match("^%d%d%d%d%-%d%d%-%d%d$")) then
    return {error="invalid day"}
  end
  for _,kind in ipairs({"appMinutes","deviceMinutes"}) do
    for _,r in ipairs(src[kind] or {}) do
      if not day or r.date==day then
        local row={mac=r.mac,date=r.date,app=r.app,minutes={},up={},down={},win={},flow={}}
        for i,m in ipairs(r.minutes or {}) do
          if m>=since and m<closed then
            table.insert(row.minutes,m)
            for _,field in ipairs({"up","down","win","flow"}) do
              table.insert(row[field],(r[field] or {})[i] or 0)
            end
          end
        end
        if #row.minutes>0 then table.insert(out[kind],row) end
      end
    end
  end
  for _,r in ipairs(src.traffic or {}) do
    if not day or r.date==day then table.insert(out.traffic,r) end
  end
  return out
end
function module_get(param)
  local req=json.decode(param or "{}") or {}
  if req.action=="usage" then return json.encode(minutes(req)) end
  if req.action=="runtime" then return json.encode(runtime(req)) end
  if req.action=="config" or not req.action then return json.encode(config()) end
  return json.encode({error="unknown child reader action"})
end
