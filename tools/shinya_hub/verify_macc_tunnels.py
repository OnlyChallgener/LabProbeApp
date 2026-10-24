"""Allocate only owned-project test tunnels; close only newly created tests."""
import sys,json,time,socket
from pathlib import Path
from probe_macc_channels import api

proof={'observedAt':time.time(),'tests':[]}
for sn,group,host,port,service in [('G1TD8RX039025',18850348,'192.168.5.201',3389,'UNKOWN'),('G1TQ88T702111',17953001,'127.0.0.1',54133,'SSH')]:
 device=api('/maint/device/'+sn)
 if device.get('serialNumber')!=sn or device.get('groupId')!=group:raise RuntimeError('Router/project identity mismatch')
 result=api('/tunnel/custom_terminal/create?client_source=web3','POST',{'groupId':group,'remoteHost':host,'remotePort':port,'remoteService':service,'operation_source':'penetrate_custom_eweb'})
 row={'sn':sn,'groupId':group,'service':service,'createCode':result.get('code'),'createFields':sorted(result)};value=result.get('data') or {};new=result.get('code')==0
 tunnel=value.get('id') or value.get('tunnelId')
 if new and tunnel:
  for _ in range(8):
   state=api('/tunnel/get_list','POST',{'tunnelId':tunnel});rows=state.get('list') or []
   value=next((r for r in rows if str(r.get('id') or r.get('tunnelId'))==str(tunnel)),rows[0] if len(rows)==1 else {})
   if value.get('tunnelStatus') in ('ESTABLISHED','CLOSED'):break
   time.sleep(2)
 row.update(responseFields=sorted(value),status=value.get('tunnelStatus'),validHours=value.get('vaildTime'),finishTime=value.get('finishTime'))
 if value.get('tunnelStatus')=='ESTABLISHED':
  try:
   with socket.create_connection((value['localHost'],int(value['localPort'])),timeout=5) as conn:
    if service=='UNKOWN':conn.sendall(bytes.fromhex('030000130ee000000000000100080003000000'))
    packet=conn.recv(256)
   row.update(protocolBytes=len(packet),protocolValid=packet[:2]==b'\x03\x00' if service=='UNKOWN' else packet.startswith(b'SSH-'))
  except Exception as e:row['protocolError']=type(e).__name__
 # Existing channel returned as 22008 belongs to the user's active session.
 # Never close it; close only this script's successful fresh allocation.
 if new and tunnel:
  closed=api('/tunnel/destroy','POST',{'tunnelId':tunnel});row['newTestClosed']=closed.get('code')==0
 proof['tests'].append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
Path('test/_analysis/oct10-priority/macc-tunnels-live.json').write_text(json.dumps(proof,indent=2),encoding='utf8')
