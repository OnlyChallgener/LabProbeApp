"""Device control overlay with installed/source hash guards and rollback."""
import hashlib,json,os,shlex,sys,time
from pathlib import Path
from remote_transport import connect_nas,run
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write

REMOTE='/volume1/docker/shinya-hub'
FILES=Path('test/_analysis/oct11-device-controls/deployment/shinya_hub')
BASELINE=Path('test/_analysis/oct11-device-controls/baseline')
RESULT=Path('test/_analysis/oct11-device-controls/deployment-result.json')
def main():
    c=connect_nas();pw=os.environ['SHINYA_NAS_PASSWORD'];tag=str(int(time.time()))
    stage=REMOTE+'/controls-deploy-'+tag
    previous='shinya-hub-before-controls-'+tag;image='shinya-hub:controls-'+tag
    swapped=False
    try:
        baseline=BASELINE
        for file in (p for p in FILES.rglob('*') if p.is_file()):
            name=file.relative_to(FILES).as_posix()
            if not (baseline/name).exists():continue
            source_path=REMOTE+'/src/shinya_hub/'+name
            source=run(c,'if test -f '+source_path+'; then cat '+source_path+'; else printf __ABSENT_FILE__; fi',timeout=15)
            installed_path='/opt/shinya-hub/shinya_hub/'+name
            live=run(c,'docker exec shinya-hub sh -c '+shlex.quote('if test -f '+installed_path+'; then cat '+installed_path+'; else printf __ABSENT_FILE__; fi'),sudo_password=pw,timeout=15)
            source_snapshot=baseline/('source-'+name)
            expected=(source_snapshot if source_snapshot.exists() else baseline/name).read_bytes()
            if source!=expected or live!=(baseline/name).read_bytes():
                raise RuntimeError('Concurrent source/installed change in '+name+'; deployment not started')
        base=json.loads(run(c,'docker inspect shinya-hub --format \'{{json .Config.Image}}\'',sudo_password=pw))
        location=run(c,"docker exec shinya-hub python -c 'import shinya_hub;print(shinya_hub.__path__[0])'",sudo_password=pw).decode().strip()
        if location not in ('/app/shinya_hub','/opt/shinya-hub/shinya_hub'):
            raise RuntimeError('Unexpected package location')
        dirs={stage,stage+'/shinya_hub',stage+'/shinya_hub/vendor'}
        dirs.update(stage+'/shinya_hub/'+p.relative_to(FILES).as_posix()
                    for p in FILES.rglob('*') if p.is_dir())
        run(c,'mkdir -p '+' '.join(shlex.quote(p) for p in dirs),sudo_password=pw)
        run(c,'chown '+shlex.quote(os.environ['SHINYA_NAS_USER'])+' '+' '.join(shlex.quote(p) for p in dirs),sudo_password=pw)
        for f in FILES.rglob('*'):
            if f.is_file():write(c,stage+'/shinya_hub/'+f.relative_to(FILES).as_posix(),f.read_bytes())
        write(c,stage+'/Dockerfile',('FROM '+base+'\nCOPY shinya_hub/ '+location+'/\n').encode())
        # NAS's BuildKit metadata lookup can stall on registry connectivity.
        # Create from the already installed image and copy the reviewed overlay;
        # this produces the same filesystem change without pulling a base image.
        temporary='shinya-controls-stage-'+tag
        run(c,'docker create --name '+temporary+' --network none '+base,sudo_password=pw,timeout=90)
        try:
            run(c,'docker cp '+stage+'/shinya_hub/. '+temporary+':'+location+'/',sudo_password=pw,timeout=45)
            run(c,'docker commit '+temporary+' '+image,sudo_password=pw,timeout=75)
        finally:run(c,'docker rm '+temporary,sudo_password=pw)
        print(json.dumps({'stage':'built','image':image,'base':base}),flush=True)
        smoke='''import sys,traceback\ntry:\n from pathlib import Path\n from shinya_hub.store import ProjectStore\n from shinya_hub.api import create_app,Principal,token_digest\n s=ProjectStore(Path('/data/shinya.sqlite'))\n a=create_app(s,None,{token_digest('overlay-smoke'):Principal('nas-admin','smoke')})\n r=a.test_client().post('/homewlan/apMonitor/getProjectList2',headers={'Authorization':'Bearer overlay-smoke'},json={}).get_json()\n assert r['code']==0 and len(r['data']['dataList'])==len(s.projects('nas-admin')),r['msg']\n assert all(p['totalCount']==len(p['apList']) and p['onlineStaCount']==p['staOnlineNum'] for p in r['data']['dataList'])\n print('project count contract smoke passed')\nexcept Exception:\n print(traceback.format_exc(),flush=True)\n sys.exit(1)\n'''
        run(c,'docker run --rm -i --network none --entrypoint python -v '+REMOTE+'/data:/data '+image+' -',stdin=smoke.encode(),sudo_password=pw,timeout=30)
        run(c,'docker stop --time 25 shinya-hub',sudo_password=pw,timeout=45)
        run(c,'docker rename shinya-hub '+previous,sudo_password=pw);swapped=True
        run(c,'docker run -d --name shinya-hub --restart unless-stopped --network host -v '+REMOTE+'/config:/config:ro -v '+REMOTE+'/data:/data '+image,sudo_password=pw,timeout=30)
        code='''import requests,time,json\ns=requests.Session();s.trust_env=False\nfor n in range(30):\n try:\n  v=s.get('http://127.0.0.1:58543/healthz',timeout=2).json()\n  assert v.get('service')=='shinya-hub' and v.get('ok') is True\n  print(json.dumps(v));break\n except Exception:\n  if n==29:raise RuntimeError('health failed')\n  time.sleep(.5)\n'''
        run(c,'docker exec -i shinya-hub python -',stdin=code.encode(),sudo_password=pw,timeout=40)
        run(c,'docker exec -i shinya-hub python -',stdin=smoke.encode(),sudo_password=pw,timeout=30)
        # Persist only these reviewed source changes after the new image passes.
        run(c,'cp -a '+REMOTE+'/src/shinya_hub '+stage+'/source-before',sudo_password=pw)
        run(c,'cp -a '+stage+'/shinya_hub/. '+REMOTE+'/src/shinya_hub/',sudo_password=pw)
        print(json.dumps({'stage':'healthy','previous':previous,'image':image}),flush=True)
        RESULT.write_text(json.dumps({'previous':previous,'image':image,'stage':stage}),encoding='utf8')
    except Exception:
        if swapped:
            try:run(c,'docker rm -f shinya-hub',sudo_password=pw)
            except Exception:pass
            run(c,'docker rename '+previous+' shinya-hub',sudo_password=pw)
            run(c,'docker start shinya-hub',sudo_password=pw)
            print(json.dumps({'stage':'rolled-back'}),flush=True)
        raise
    finally:c.close()
if __name__=='__main__':main()
