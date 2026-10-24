"""Owned-project live checks; every temporary station setting is restored."""
import json,socket,time
from datetime import datetime,timedelta
from pathlib import Path
from zoneinfo import ZoneInfo
from local_api import account,post

OUT=Path('test/_analysis/oct11-device-controls');PROJECT='900000001';SN='G1TD8RX039025';MAC='C6019A7D27FF'
def cmd(s,action,data=None):
 value='dev_config '+action+' -m flowctrl_udp'
 if data is not None:value+=" '"+json.dumps(data,separators=(',',':'))+"'"
 result=post(s,'api/device/command/sendCmd',{'projectId':PROJECT,'apSn':SN,'cmd':value,'type':2 if action=='get' else 1})
 if isinstance(result,str):return json.loads(result)
 if isinstance(result,dict) and result.get('cmdId'):
  for _ in range(12):
   time.sleep(.5);value=post(s,'api/device/command/getCmdResult',{'projectId':PROJECT,'cmdId':result['cmdId']})
   if value is not None:return json.loads(value) if isinstance(value,str) else value
 raise RuntimeError('Native rule response unavailable')
def main():
 proof={}
 with account() as s:
  rows=post(s,'apMonitor/getProjectList2',{})['dataList']
  proof['projects']=[{k:p[k] for k in ('projectId','totalCount','staCount','onlineStaCount')} for p in rows]
  assert all(p['totalCount']==1 and p['onlineStaCount']>0 for p in rows)
  print('project counts',proof['projects'],flush=True)
  body={'projectId':PROJECT,'apSn':SN,'staMac':MAC}
  before=post(s,'eSportAccelerate/getStaOnly5GList',body)
  enabled=any(v.get('staMac')==MAC for v in before)
  try:
   changed=post(s,'eSportAccelerate/setOnly5GAndSendCmd',{**body,'isBlack':int(not enabled)})
   assert changed.get('verified') is True
   rows=post(s,'eSportAccelerate/getStaOnly5GList',body)
   assert any(v.get('staMac')==MAC for v in rows)!=enabled
   proof['only5g']={'nativeVerified':True}
  finally:
   restored=post(s,'eSportAccelerate/setOnly5GAndSendCmd',{**body,'isBlack':int(enabled)})
   assert restored.get('verified') is True
   proof.setdefault('only5g',{})['restored']=True
  print('only5g verified and restored',flush=True)
  baseline=cmd(s,'get');rows=baseline['list'];target=next(v for v in rows if v['ipRange']=='192.168.5.43-192.168.5.43')
  try:
   for rate in ('1000','2500'):
    cmd(s,'update',{'list':[{**target,'upRate':rate,'downRate':rate}]})
    value=cmd(s,'get');current=next(v for v in value['list'] if v['ipRange']==target['ipRange'])
    assert current['upRate']==current['downRate']==rate
   proof['rate']={'verifiedKbps':[1000,2500]}
  finally:
   cmd(s,'update',{'list':[target]})
   assert cmd(s,'get')['list']==baseline['list']
   proof.setdefault('rate',{})['restoredKbps']=int(target['upRate'])
  print('rate writes verified and restored',flush=True)
  day=(datetime.now(ZoneInfo('Asia/Shanghai'))-timedelta(days=2)).strftime('%Y-%m-%d')
  history=post(s,'device/station/getStaUpDownList',{**body,'sn':SN,'day':day})['rows']
  assert history and history==sorted(history,key=lambda row:row['eventTime'],reverse=True)
  proof['history']=history;print('native history rows',len(history),flush=True)
  remote={'projectId':PROJECT,'apSn':SN,'ip':'192.168.5.201','staIp':'192.168.5.201'}
  assert post(s,'remoteAccess/checkStaRdp',remote) is True
  tunnel=post(s,'remoteAccess/getRdpTunnel',remote)
  with socket.create_connection((tunnel['ip'],int(tunnel['port'])),timeout=5) as connection:
   connection.settimeout(5);connection.sendall(bytes.fromhex('030000130ee000000000000100080003000000'));packet=connection.recv(64)
  assert len(packet)>=11 and packet[0]==3 and packet[5]==208
  proof['rdp']={**tunnel,'negotiationConfirmed':True};print('RDP relay negotiation confirmed',tunnel['validSeconds'],'seconds remaining',flush=True)
 (OUT/'live-result.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')
if __name__=='__main__':main()
