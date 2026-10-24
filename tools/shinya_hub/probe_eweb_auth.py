"""Read-only native query without SID, cookies or tokens, over authorized SSH."""
import json
import os
import re
import sys
import requests
from pathlib import Path

sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect,run


def main():
    family=sys.argv[1] if len(sys.argv)>1 else 'be72'
    prefix='SHINYA_'+family.upper()
    client=connect(os.environ[prefix+'_HOST'],int(os.environ[prefix+'_PORT']),
                   os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'])
    try:
        identity=json.loads(run(client,'dev_sta get -m sysinfo'))['serial_num']
        expected={'be72':'G1TD8RX039025','be50':'G1TQ88T702111'}[family]
        if identity!=expected:raise ValueError('Wrong authorized router')
        channel=client.get_transport().open_channel('direct-tcpip',('127.0.0.1',80),('127.0.0.1',0),timeout=5)
        channel.settimeout(8)
        body=json.dumps({'method':'devSta.get','params':{'module':'sysinfo','noParse':False,
                  'async':None,'remoteIp':False,'device':'pc'}}).encode()
        request=('POST /cgi-bin/luci/api/cmd HTTP/1.0\r\nHost: 127.0.0.1\r\n'
                  'Content-Type: application/json\r\nConnection: close\r\nContent-Length: '+str(len(body))+'\r\n\r\n').encode()+body
        channel.sendall(request)
        raw=b''
        while len(raw)<32768:
            part=channel.recv(4096)
            if not part:break
            raw+=part
        channel.close()
        header,_,data=raw.partition(b'\r\n\r\n')
        result={'family':family,'path':'/cgi-bin/luci/api/cmd','hasCredentials':False,
                'containsExpectedSerial':expected.encode() in data,
                'httpStatusLine':header.split(b'\r\n',1)[0].decode('ascii','replace')}
        try:
            value=json.loads(data)
            result.update({'responseKeys':sorted(value),'code':value.get('code'),
                           'errorPresent':bool(value.get('error')),
                           'dataType':type(value.get('data')).__name__,
                           'message':str(value.get('msg') or value.get('message') or value.get('error') or '')[:120]})
        except (ValueError,TypeError):
            result['responseKind']='not-json'
        # Only a presently verified reverse-forward to this router's eWeb.
        commands=run(client,"sh -c 'ps w | grep -E \"[s]sh -R|[s]sh -vR\"'").decode()
        forwards=re.findall(r'ssh\s+(?:-v)?-R\s+(\d+):127\.0\.0\.1:80\b([^\n]*)',commands)
        for port,tail in forwards:
            host=re.search(r'\S+@(47\.104\.(?:22\.0|155\.247))\s+-p\s+\d+',tail)
            if not host:continue
            session=requests.Session();session.trust_env=False
            response=session.post('http://'+host[1]+':'+port+'/cgi-bin/luci/api/cmd',data=body,
                headers={'Content-Type':'application/json'},timeout=(3,8))
            public={'httpStatus':response.status_code,'containsExpectedSerial':expected.encode() in response.content}
            try:
                value=response.json();public.update({'code':value.get('code'),'errorPresent':bool(value.get('error')),
                    'message':str(value.get('msg') or value.get('message') or value.get('error') or '')[:120]})
            except ValueError:public['responseKind']='not-json'
            result['freshPublicTunnelQuery']=public
            break
        target=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/cloud-sync'/(family+'-eweb-noauth.json')
        target.write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(json.dumps(result),flush=True)
    finally:
        client.close()


if __name__=='__main__':main()
