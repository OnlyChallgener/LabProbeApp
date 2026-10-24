"""Run deterministic Lua checks and the captured two-model fixtures, no config writes."""
import json
from pathlib import Path
from remote_transport import connect_router,run

source=Path('tools/shinya_hub/router_sync/wifi_rates.lua').read_text(encoding='utf8')
fixtures=json.loads(Path('test/_analysis/oct10-pages/rate-tables.json').read_text(encoding='utf8'))
TEST=r'''
local M=assert(loadstring(SOURCE))()
local json=require('dkjson')
local header='ADDR AID CHAN TXRATE RXRATE RSSI MAXRATE ASSOCTIME CHUTIL FLNOSIE PSM IFNAME SSID'
local one='30:52:23:29:43:56 1 1 9M 54.5M -50 100M 100 20 -90 0 ra2 SSID with spaces'
local p=M.parse(header..'\n'..one)
assert(p['305223294356/ra2'].up==54.5)
assert(p['305223294356/ra2'].down==9)
assert(M.parse(header..'\n'..one..'\n'..one..'\n'..one)['305223294356/ra2']==false)
assert(next(M.parse('invalid header\n'..one))==nil)
assert(next(M.parse(string.rep('x',65537)))==nil)
local rows={list={
 {mac='30:52:23:29:43:56',sn='owned',connectType='wireless',ifname='ra2',rxrate='9M'},
 {mac='30:52:23:29:43:56',sn='foreign',connectType='wireless',ifname='ra2'},
 {mac='30:52:23:29:43:56',sn='owned',connectType='wireless',ifname='rai0'},
 {mac='30:52:23:29:43:56',sn='owned',connectType='wired',ifname='ra2'},
}}
M.enrich(rows,'owned',function(radio)return p end)
assert(rows.list[1].txrate=='54.5M' and rows.list[1].rxrate=='9M')
for i=2,4 do assert(rows.list[i].txrate==nil) end
assert(#rows.list==4)
local fixture=assert(json.decode(FIXTURE))
local count=#fixture.users.list
local calls=0
M.enrich(fixture.users,fixture.sn,function(radio)
 calls=calls+1
 return M.parse(fixture.tables[radio] or '')
end)
assert(calls<=2 and #fixture.users.list==count)
local wireless,matched=0,0
for _,r in ipairs(fixture.users.list) do
 if r.connectType=='wireless' and r.sn==fixture.sn then
  wireless=wireless+1
  if r.txrate then
   matched=matched+1
   assert(r.phyRateSn==fixture.sn and r.phyRateSource=='wlanconfig.RXRATE')
  end
 end
end
assert(wireless>0 and matched==wireless)
print(assert(json.encode({checks='passed',wireless=wireless,matched=matched,radioReads=calls})))
'''
for family,fixture in fixtures.items():
 c=connect_router(family)
 try:
  code='local SOURCE=[==['+source+']==]\nlocal FIXTURE=[==['+json.dumps(fixture)+']==]\n'+TEST
  result=run(c,'lua -',stdin=code.encode(),timeout=15)
  if isinstance(result,bytes):result=result.decode()
  result=json.loads(result)
  print(json.dumps({'family':family,**result}),flush=True)
 finally:c.close()
