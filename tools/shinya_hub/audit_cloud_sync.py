"""Read-only cloud transport audit. Persist public config and protocol metadata only."""
import argparse
import hashlib
import json
import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'test/_analysis/oct08-shinya-hub/cloud-sync'
FIRMWARE=ROOT/'test/_analysis/extract/rootfs'
PUBLIC={'enable','addr','port','controller','dnsurl','authurl','embdurl','server','upload_period','tls','keepAlivePeroid','keepAlivePeriod',
        'reconnPeriod','reconnStep','reconnMaxPeriod','retransStep','retransMaxPeriod',
        'tlscafil','tlsenable','tlsEnable','ssl','ver','version','qos','maxBufSize'}

def clean(value):
    if isinstance(value,dict):
        return {k:('[redacted]' if re.search('pass|secret|token|credential|private|certificate',k,re.I)
                   else clean(v)) for k,v in value.items()}
    if isinstance(value,list):return [clean(v) for v in value]
    if isinstance(value,str) and value.startswith(('https://','http://')):
        parts=urlsplit(value)
        return urlunsplit((parts.scheme,parts.hostname or '',parts.path,'',''))
    return value

def config(text):
    out={};private=[]
    for line in text.splitlines():
        m=re.match(r'\s*option\s+(\S+)\s+[\'"]?(.*?)[\'"]?\s*$',line)
        if not m:continue
        key,value=m.groups()
        if key in PUBLIC:out[key]=clean(value)
        else:private.append(key)
    return {'public':out,'otherFieldNames':private}

def metadata(path):
    data=path.read_bytes()
    strings=[(m.start(),m.group().decode('ascii')) for m in re.finditer(rb'[ -~]{5,}',data)]
    wanted=[]
    pattern=r'mosquitto|MQTT|mqtt|mqlink|topic|publish|subscribe|telemetry|heartbeat|report|upload|notify|ntf|sync|keepalive|uf_cli|ubus|SSL_|TLS|http_send|sta_log|radiolog|sniffer'
    for offset,s in strings:
        if len(s)>300 or not re.search(pattern,s,re.I):continue
        if re.search('BEGIN .*KEY|password=|passwd=|token=|secret=',s,re.I):continue
        wanted.append({'offset':hex(offset),'text':s})
    return {'file':path.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
            'elfClass':data[4] if data.startswith(b'\x7fELF') else None,'strings':wanted}

def local():
    result={'configs':{},'binaries':[]}
    for name in ('shinya_mqlink','mqlink','mqlink_reg','stalog'):
        path=FIRMWARE/'etc/config'/name
        if path.exists():result['configs'][name]=config(path.read_text(encoding='utf-8',errors='replace'))
    for name in ('shinya_mqlink.elf','mqlink.elf','stalog.elf'):
        path=FIRMWARE/'usr/sbin'/name
        if path.exists():result['binaries'].append(metadata(path))
    return result

def remote(family):
    sys.path.insert(0,str(ROOT.parent/'labprobe-hub/scripts'))
    from eweb_hub_remote import connect
    from time import time
    prefix='SHINYA_'+family.upper()
    client=None;gateway=None
    result={'family':family,'capturedAt':time(),'commands':{},'configs':{},'binaries':[]}
    try:
        try:
            client=connect(os.environ[prefix+'_HOST'],int(os.environ[prefix+'_PORT']),
                           os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'])
            result['transport']='provided-ssh'
        except Exception:
            gateway=connect(os.environ['SHINYA_NAS_HOST'],int(os.environ['SHINYA_NAS_PORT']),
                            os.environ['SHINYA_NAS_USER'],os.environ['SHINYA_NAS_PASSWORD'])
            local_ip='192.168.5.1' if family=='be72' else '100.66.1.1'
            channel=gateway.get_transport().open_channel('direct-tcpip',(local_ip,22),('127.0.0.1',0),timeout=10)
            client=connect(local_ip,22,os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'],sock=channel)
            result['transport']='nas-local-ssh'
        def execute(command):
            _,stdout,stderr=client.exec_command(command,timeout=15)
            payload=stdout.read(200000).decode('utf-8','replace')
            code=stdout.channel.recv_exit_status()
            try:payload=clean(json.loads(payload))
            except json.JSONDecodeError:pass
            return {'exitCode':code,'output':payload}
        for label,command in {
            'processNames':"ps | awk '{print $1, $5}' | grep -E 'mqlink|stalog|radiolog|mqtt|sniffer'",
            'ubusSchemas':"ubus -v list shinya_mqlink; ubus -v list mqlink",
            'shinyaStatus':"ubus -t 3 call shinya_mqlink status '{}'",
            'mqlinkStatus':"ubus -t 3 call mqlink status '{}'",
            'cloudConnections':"netstat -ntp 2>/dev/null | grep -E 'mqlink|stalog|radiolog'",
            'modulePaths':"find /usr/lib /usr/sbin /usr/local /etc/shinya_mqlink.d -iname '*mqlink*' -o -iname '*radiolog*' -o -iname '*stalog*' 2>/dev/null",
            'mqttTools':"command -v lua mosquitto_pub mosquitto_sub timeout dev_sta; mosquitto_pub --help 2>/dev/null | head -n 6",
        }.items():result['commands'][label]=execute(command)
        def read_file(path):
            _,stdout,_=client.exec_command('cat '+path,timeout=15)
            data=stdout.read()
            if stdout.channel.recv_exit_status():raise OSError('File unavailable')
            return data
        for name in ('shinya_mqlink','mqlink','mqlink_reg','shinya_mqlink_reg','stalog','radioLog'):
            try:
                result['configs'][name]=config(read_file('/etc/config/'+name).decode('utf-8','replace'))
            except OSError:pass
        for source in ('/usr/sbin/shinya_mqlink.elf','/usr/sbin/mqlink.elf','/usr/sbin/stalog.elf','/usr/lib/ufplugins/mqlink_plugin.so'):
            path=OUT/(family+'-'+Path(source).name)
            try:
                path.write_bytes(read_file(source))
                result['binaries'].append(metadata(path))
            except OSError:pass
    except Exception as e:result['error']=type(e).__name__
    finally:
        if client:client.close()
        if gateway:gateway.close()
    return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--remote',action='store_true')
    parser.add_argument('--family',choices=('be72','be50'));args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    static=local();(OUT/'firmware-static.json').write_text(json.dumps(static,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'staticBinaries':[(b['file'],b['bytes'],len(b['strings'])) for b in static['binaries']],
                      'staticConfigs':static['configs']},ensure_ascii=False),flush=True)
    if args.remote:
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(remote,(args.family,) if args.family else ('be72','be50')))
        for row in results:
            payload=json.dumps(row,ensure_ascii=False,indent=2)
            (OUT/(row['family']+'-attempt-'+str(int(row['capturedAt']))+'.json')).write_text(payload,encoding='utf-8')
            if row['commands']:
                (OUT/(row['family']+'-live.json')).write_text(payload,encoding='utf-8')
            print(json.dumps({k:v for k,v in row.items() if k!='binaries'},ensure_ascii=False),flush=True)

if __name__=='__main__':main()
