"""Install own read-only push services; never reconfigure vendor cloud connections."""
import argparse,json,os,shlex,sys,time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect,run,write
from remote_transport import connect_nas

ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/'tools/shinya_hub/router_sync'
REMOTE='/volume1/docker/shinya-hub'
IMAGE='shinya-hub:oct09-mqtt-v15'
OUT=ROOT/'test/_analysis/oct08-shinya-hub/cloud-sync'
SNS={'be72':'G1TD8RX039025','be50':'G1TQ88T702111'}

def nas():
    return connect_nas()

def prepare():
    c=nas();pw=os.environ['SHINYA_NAS_PASSWORD']
    try:
        run(c,'mkdir -p '+REMOTE+'/mqtt',sudo_password=pw)
        run(c,'chown '+shlex.quote(os.environ['SHINYA_NAS_USER'])+' '+REMOTE+'/mqtt',sudo_password=pw)
        write(c,REMOTE+'/mqtt/prepare.py',(SRC/'prepare_nas.py').read_bytes(),mode='600')
        cmd='docker run --rm --network none --entrypoint python -v '+REMOTE+'/config:/config -v '+REMOTE+'/mqtt:/mqtt '+IMAGE+' /mqtt/prepare.py'
        stream,out,err=c.exec_command('sudo -S -p \'\' '+cmd,timeout=45)
        stream.write(pw+'\n');stream.flush();stream.channel.shutdown_write()
        raw=out.read();failure=err.read().decode('utf-8','replace');code=out.channel.recv_exit_status()
        if code:
            # This helper never prints credentials, only its own static traceback.
            safe=[s for s in failure.splitlines() if s.startswith(('  File','AttributeError:','TypeError:','ValueError:','PermissionError:','ModuleNotFoundError:','ImportError:'))]
            print(json.dumps({'stage':'prepare-error','diagnostics':safe[-8:]}),flush=True)
            raise RuntimeError('NAS certificate preparation failed')
        result=json.loads(raw)
        run(c,'docker run --rm --network none --entrypoint mosquitto_passwd -v '+REMOTE+'/mqtt:/mqtt eclipse-mosquitto:2 -U /mqtt/passwords',sudo_password=pw)
        # Broker owns just its own files. The private CA signing key stays root-only.
        files=' '.join(REMOTE+'/mqtt/'+n for n in ('mosquitto.conf','passwords','acl','ca.crt','server.crt','server.key'))
        run(c,"sh -c 'chmod 750 "+REMOTE+"/mqtt; chown 1883:1883 "+REMOTE+"/mqtt; chown 1883:1883 "+files+"; chmod 640 "+files+"'",sudo_password=pw)
        for name in ('shinya-mqtt','shinya-hub-push'):
            # Names are fixed owned services. Preserve all other containers.
            run(c,"sh -c 'docker rm -f "+name+" >/dev/null 2>&1 || true'",sudo_password=pw)
        run(c,'docker run -d --name shinya-mqtt --restart unless-stopped --network host -v '+REMOTE+'/mqtt:/mosquitto/config:ro eclipse-mosquitto:2',sudo_password=pw)
        run(c,'docker run -d --name shinya-hub-push --restart unless-stopped --no-healthcheck --network host --entrypoint python -v '+REMOTE+'/config:/config:ro -v '+REMOTE+'/data:/data '+IMAGE+' -m shinya_hub.mqtt_ingest',sudo_password=pw)
        print(json.dumps({'stage':'nas-sync-started',**result}),flush=True)
    finally:c.close()

def materials():
    c=nas()
    try:
        code="from pathlib import Path\nfrom shinya_hub.vault import ConfigVault\nimport json\nc=ConfigVault(Path('/config')).load()['mqttSync']\nprint(json.dumps(c['agents']))"
        credentials=json.loads(run(c,'docker exec -i shinya-hub python -',stdin=code.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        ca=run(c,'cat '+REMOTE+'/config/mqtt-ca.crt',sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
        return credentials,ca
    finally:c.close()

def install(family,credentials,ca):
    sn=SNS[family];prefix='SHINYA_'+family.upper()
    gateway=None;transport='provided-ssh'
    try:
        c=connect(os.environ[prefix+'_HOST'],int(os.environ[prefix+'_PORT']),os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'])
    except Exception:
        gateway=nas();address='192.168.5.1' if family=='be72' else '100.66.1.1'
        try:
            channel=gateway.get_transport().open_channel('direct-tcpip',(address,54133),('127.0.0.1',0),timeout=8)
            c=connect(address,54133,os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'],sock=channel)
            transport='nas-local-ssh'
        except Exception:
            gateway.close();raise
    try:
        native=json.loads(run(c,'dev_sta get -m sysinfo'))
        if native.get('serial_num')!=sn:raise ValueError('SSH endpoint is a different router')
        official=json.loads(run(c,"ubus -t 3 call shinya_mqlink status '{}'"))['status']
        run(c,'mkdir -p /etc/shinya-agent/client')
        for source,dest,mode in [('collector.lua','/etc/shinya-agent/collector.lua','600'),('wifi_rates.lua','/etc/shinya-agent/wifi_rates.lua','600'),('publish.sh','/etc/shinya-agent/publish.sh','700'),('init.sh','/etc/init.d/shinya-agent','755')]:
            write(c,dest,(SRC/source).read_bytes(),mode=mode)
        write(c,'/etc/shinya-agent/config.json',json.dumps({'sn':sn,'interval':5,'outputPath':'/tmp/shinya-agent/latest.json'}).encode(),mode='600')
        write(c,'/etc/shinya-agent/sn',sn.encode(),mode='600')
        write(c,'/etc/shinya-agent/ca.crt',ca,mode='600')
        agent=credentials[sn];address='192.168.5.46' if family=='be72' else '100.66.1.4'
        cfg='\n'.join(['-h '+address,'-p 58545','-u '+agent['username'],'-P '+agent['password'],'--cafile /etc/shinya-agent/ca.crt','--tls-version tlsv1.2'])+'\n'
        write(c,'/etc/shinya-agent/client/mosquitto_pub',cfg.encode(),mode='600')
        run(c,'chmod 700 /etc/shinya-agent /etc/shinya-agent/client')
        run(c,'/etc/init.d/shinya-agent enable')
        run(c,'/etc/init.d/shinya-agent restart')
        status={'family':family,'sn':sn,'installed':True,'transport':transport,'brokerAddress':address,'intervalSeconds':5,'officialBefore':official}
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/(family+'-agent-install.json')).write_text(json.dumps(status,indent=2),encoding='utf-8')
        return status
    finally:
        c.close()
        if gateway:gateway.close()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--prepare',action='store_true');parser.add_argument('--install',action='store_true');args=parser.parse_args()
    if args.prepare:prepare()
    if args.install:
        credentials,ca=materials()
        def attempt(family):
            try:return install(family,credentials,ca)
            except Exception as e:return {'family':family,'installed':False,'error':type(e).__name__}
        with ThreadPoolExecutor(max_workers=2) as pool:
            for r in pool.map(attempt,SNS):print(json.dumps(r),flush=True)

if __name__=='__main__':main()
