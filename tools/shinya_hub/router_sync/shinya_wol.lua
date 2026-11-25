-- Send a WOL packet only on the router's LAN bridge. No user-supplied shell
-- arguments other than a strictly validated, unicast MAC are accepted.
package.path = '/usr/lib/lua/?.lua;' .. package.path
module('shinya_wol', package.seeall)
local json = require('dkjson')

function module_get(param)
  local tool = io.open('/usr/bin/etherwake', 'r')
  local bridge = io.open('/sys/class/net/br-lan/ifindex', 'r')
  local available = tool ~= nil and bridge ~= nil
  if tool then tool:close() end
  if bridge then bridge:close() end
  return json.encode({available=available})
end

function module_set(param)
  local ok, request = pcall(json.decode, param or '{}')
  if not ok or type(request) ~= 'table' or request.action ~= 'wake' then
    return json.encode({sent=false, error='invalid request'})
  end
  local mac = request.mac
  if type(mac) ~= 'string' or not mac:match('^%x%x:%x%x:%x%x:%x%x:%x%x:%x%x$')
      or tonumber(mac:sub(1,2),16) % 2 ~= 0 or mac:lower() == '00:00:00:00:00:00' then
    return json.encode({sent=false, error='invalid MAC'})
  end
  local tool = io.open('/usr/bin/etherwake', 'r')
  local bridge = io.open('/sys/class/net/br-lan/ifindex', 'r')
  if not tool or not bridge then
    if tool then tool:close() end
    if bridge then bridge:close() end
    return json.encode({sent=false, error='LAN wake unavailable'})
  end
  tool:close(); bridge:close()
  local result = os.execute('/usr/bin/etherwake -b -i br-lan ' .. mac .. ' >/dev/null 2>&1')
  if result == 0 then return json.encode({sent=true, action='wake'}) end
  return json.encode({sent=false, action='wake', error='wake send failed'})
end
