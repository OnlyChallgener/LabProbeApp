-- An intervening native/other-App user replacement removes the marker and
-- cancels this action. Read the current user; never replay a stale whole user.
package.path="/usr/lib/lua/?.lua;"..package.path
local uid,cutoff=arg[1],tonumber(arg[2])
if type(uid)~="string" or #uid~=32 or not uid:match("^%x+$") or not cutoff then return end
local u=require("luci.model.uci").cursor()
if u:get("child_guard",uid)~="user" or tonumber(u:get("child_guard",uid,"shinya_block_at"))~=cutoff
  or os.time()<cutoff or u:get("child_guard","config","rdpi_enable")~="1" then return end
u:set("child_guard",uid,"block","-1")
u:set("child_guard",uid,"pause","0")
u:set("child_guard",uid,"reload","1")
u:delete("child_guard",uid,"shinya_block_at")
u:commit("child_guard")
os.execute("lua /usr/lib/lua/child_guard_reload.lua")
