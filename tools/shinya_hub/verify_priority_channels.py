"""Exercise real channel APIs without saving temporary access credentials."""
import json, re, time, warnings, socket
from pathlib import Path
from urllib.parse import urlsplit,parse_qs
import requests
from urllib3.exceptions import InsecureRequestWarning
from local_api import account,BASE,post

proof={'observedAt':time.time(),'projects':[]}
def request(session,path,body=None,method='POST'):
    with warnings.catch_warnings():
        warnings.simplefilter('ignore',InsecureRequestWarning)
        response=session.request(method,BASE+path,json=body if method=='POST' else None,
            timeout=55)
    return response.json()

with account() as session:
    for project in post(session,'apMonitor/getProjectList2',{})['dataList']:
        pid=project['projectId'];sn=project['apList'][0]['sn']
        row={'projectId':pid,'sn':sn,'childGuardVer':project['apList'][0].get('childGuardVer')}
        selection=request(session,'childProtection/getStaListWithMainMac/'+pid,method='GET')
        row['childSelection']={'code':selection.get('code'),'guardedMacCount':len(selection.get('data') or [])}
        began=time.monotonic();web=request(session,'homeAp/getTunnelUrl',{'projectId':pid,'sn':sn})
        row['web']={'code':web.get('code'),'seconds':round(time.monotonic()-began,2)}
        if web.get('code')==0 and isinstance(web.get('data'),str):
            url=web['data'];parts=urlsplit(url)
            row['web'].update(host=parts.hostname,port=parts.port,queryFieldNames=sorted(parse_qs(parts.query)))
            if parts.scheme in ('http','https') and parts.hostname:
                browser=requests.Session();browser.trust_env=False
                try:
                    response=browser.get(url,timeout=(4,10),allow_redirects=False)
                    row['web'].update(htmlStatus=response.status_code,htmlBytes=len(response.content),
                        htmlTitle=re.findall(r'<title>(.*?)</title>',response.text,re.I)[:1])
                except requests.RequestException as error:row['web']['htmlError']=type(error).__name__
                finally:browser.close()
        stations=post(session,'homeAp/getStaList2',{'projectId':pid})['sta_list']
        target=next((s for s in stations if s.get('ipaddr')=='192.168.5.201' and s.get('state')==1),None)
        if target:
            check=request(session,'remoteAccess/checkStaRdp',{'projectId':pid,'ip':target['ipaddr']})
            row['rdpCheck']={'code':check.get('code'),'result':check.get('data')}
            tunnel=request(session,'remoteAccess/getRdpTunnel',{'projectId':pid,'staIp':target['ipaddr']})
            row['rdpTunnel']={'code':tunnel.get('code')}
            data=tunnel.get('data')
            if tunnel.get('code')==0 and isinstance(data,dict) and data.get('ip') and data.get('port'):
                try:
                    with socket.create_connection((str(data['ip']),int(data['port'])),timeout=5) as conn:
                        conn.sendall(bytes.fromhex('030000130ee000000000000100080003000000'))
                        packet=conn.recv(256)
                    row['rdpTunnel'].update(negotiationBytes=len(packet),validTPKT=packet[:2]==b'\x03\x00')
                except (OSError,ValueError) as error:row['rdpTunnel']['protocolError']=type(error).__name__
        proof['projects'].append(row)
        print(json.dumps(row,ensure_ascii=False),flush=True)
    first,second=proof['projects']
    foreign=request(session,'homeAp/getTunnelUrl',{'projectId':first['projectId'],'sn':second['sn']})
    proof['crossProjectRejected']=foreign.get('code')==404
Path('test/_analysis/oct10-priority/channels-live.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')
