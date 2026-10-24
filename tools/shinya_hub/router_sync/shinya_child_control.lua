-- Only schedules a verified existing child's delayed manual ban. Native
-- child_guard remains responsible for enforcement and IPv4/IPv6 rules.
package.path="/usr/lib/lua/?.lua;"..package.path
module("shinya_child_control",package.seeall)
local json=require("dkjson")
function module_set(param)
  local r=json.decode(param or "{}") or {}
  local uid=r.uid
  if type(uid)~="string" or #uid~=32 or not uid:match("^%x+$") then
    return json.encode({error="invalid uid"})
  end
  local u=require("luci.model.uci").cursor()
  if u:get("child_guard",uid)~="user" or u:get("child_guard","config","rdpi_enable")~="1" then
    return json.encode({error="child runtime unavailable"})
  end
  local cutoff=tonumber(r.cutoff) or 0
  local timers=require("runat_op")
  local task="shinya_"..uid.."_delay"
  if cutoff==0 then
    timers.runat_del(task,0)
    u:delete("child_guard",uid,"shinya_block_at")
  elseif cutoff>os.time() and cutoff<=os.time()+86400 then
    local cmd="lua /usr/local/lua/shinya_child_delayed.lua "..uid.." "..math.floor(cutoff)
    if not timers.runat_add(task,cmd,math.floor(cutoff),3) then
      return json.encode({error="native timer queue full"})
    end
    u:set("child_guard",uid,"shinya_block_at",tostring(math.floor(cutoff)))
  else return json.encode({error="invalid cutoff"}) end
  u:commit("child_guard")
  return json.encode({saved=true})
end
