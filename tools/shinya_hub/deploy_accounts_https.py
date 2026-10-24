"""Enable the authorized admin account and a separate, pinned HUB TLS endpoint.

Credentials arrive through the caller's environment and SSH stdin. Never print
configuration, credentials or session tokens. Existing NAS TLS files are unused.
"""
import hashlib
import json
import os
import shlex
import sys
import time
from pathlib import Path
from remote_transport import connect_nas,run
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write

SOURCE=Path('D:/Github/ShinyaHub')
REMOTE='/volume1/docker/shinya-hub'
IMAGE='shinya-hub:oct10-account-v18'
PROXY='nginx@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236'

NGINX='''worker_processes 2;
error_log /dev/stderr warn;
events { worker_connections 512; }
http {
    access_log off;
    server_tokens off;
    client_max_body_size 64k;
    client_body_timeout 10s;
    keepalive_timeout 30s;
    upstream hub { server 127.0.0.1:58543; keepalive 16; }
    server {
        listen 58544 ssl;
        ssl_certificate /tls/hub.pem;
        ssl_certificate_key /tls/hub.key;
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_session_cache shared:hub_tls:4m;
        ssl_session_tickets off;
        add_header X-Content-Type-Options nosniff always;
        location / {
            proxy_pass http://hub;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-Proto https;
            proxy_connect_timeout 2s;
            proxy_read_timeout 25s;
            proxy_send_timeout 10s;
            proxy_buffers 8 16k;
        }
    }
}
'''

def main():
    password=os.environ['SHINYA_NAS_PASSWORD']
    admin=os.environ['SHINYA_ADMIN_ACCOUNT']
    admin_password=os.environ['SHINYA_ADMIN_PASSWORD']
    client=connect_nas();previous='shinya-hub-previous-'+str(int(time.time()));swapped=False
    def command(value,**kw):return run(client,value,sudo_password=password,**kw)
    try:
        old=command("docker inspect --format '{{.Config.Image}}' shinya-hub").decode().strip()
        command('mkdir -p '+REMOTE+'/tls '+REMOTE+'/src')
        command('chmod 700 '+REMOTE+'/tls')
        bootstrap='credentials='+repr({'account':admin,'password':admin_password})+'\n'+r'''
import json,ipaddress,time,os
from pathlib import Path
from datetime import datetime,timedelta,timezone
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from shinya_hub.auth import AccountStore
from shinya_hub.vault import ConfigVault
os.umask(0o077)
vault=ConfigVault(Path('/config'));settings=vault.load()
accounts=AccountStore(Path('/data/accounts.sqlite'))
tenants={p['tenant'] for p in settings['projects']}
assert len(tenants)==1, 'Initial admin needs an explicit tenant'
if not accounts.has_accounts():
    accounts.create_account(credentials['account'],credentials['password'],next(iter(tenants)),'Demon',True)
else:
    identity=accounts.authenticate(accounts.login(credentials['account'],credentials['password'])['token'])
    assert identity.tenant in tenants and identity.can_write
backup=vault.value_path.with_name('devices.before-accounts-'+str(int(time.time()))+'.enc.json')
backup.write_bytes(vault.value_path.read_bytes());backup.chmod(0o600)
settings['accountLoginEnabled']=True
settings['allowLegacyTokens']=False
vault.save(settings)
directory=Path('/tls');key_file=directory/'hub.key';cert_file=directory/'hub.pem'
assert key_file.exists()==cert_file.exists(), 'Incomplete existing certificate'
if not key_file.exists():
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Demon HUB')])
    now=datetime.now(timezone.utc)
    cert=(x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=5))
        .not_valid_after(now+timedelta(days=825)).add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'),x509.DNSName('shinya-hub.local'),
            x509.IPAddress(ipaddress.ip_address('127.0.0.1')),x509.IPAddress(ipaddress.ip_address('192.168.5.46'))]),critical=False)
        .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]),critical=False)
        .sign(key,hashes.SHA256()))
    key_file.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
key_file.chmod(0o600);cert_file.chmod(0o644)
cert=x509.load_pem_x509_certificate(cert_file.read_bytes())
result={'hubId':accounts.hub_id,'certificateSha256':cert.fingerprint(hashes.SHA256()).hex(),'httpsPort':58544}
(directory/'pairing.json').write_text(json.dumps(result,indent=2));(directory/'pairing.json').chmod(0o600)
print(json.dumps(result))
'''
        pairing=json.loads(command('docker run --rm -i --network none --entrypoint python -v '+REMOTE+'/config:/config -v '+REMOTE+'/data:/data -v '+REMOTE+'/tls:/tls '+shlex.quote(old)+' -',stdin=bootstrap.encode(),timeout=45))
        print(json.dumps({'stage':'admin-and-dedicated-certificate-ready',**pairing}),flush=True)
        sources=[p for p in (SOURCE/'shinya_hub').rglob('*') if p.is_file() and p.suffix in {'.py','.json'}]
        dirs={REMOTE+'/src',*(REMOTE+'/src/'+p.parent.relative_to(SOURCE).as_posix() for p in sources)}
        command('mkdir -p '+' '.join(shlex.quote(p) for p in sorted(dirs)))
        command('chown '+shlex.quote(os.environ['SHINYA_NAS_USER'])+' '+' '.join(shlex.quote(p) for p in sorted(dirs)))
        for source in sources:write(client,REMOTE+'/src/'+source.relative_to(SOURCE).as_posix(),source.read_bytes())
        write(client,REMOTE+'/src/Dockerfile',(SOURCE/'Dockerfile').read_bytes())
        command('docker build -t '+IMAGE+' '+REMOTE+'/src',timeout=240)
        print(json.dumps({'stage':'image-built','image':IMAGE}),flush=True)
        command('docker stop --time 25 shinya-hub',timeout=45);command('docker rename shinya-hub '+previous);swapped=True
        command('docker run -d --name shinya-hub --restart unless-stopped --network host -v '+REMOTE+'/config:/config:ro -v '+REMOTE+'/data:/data '+IMAGE,timeout=45)
        # Nginx only terminates TLS; it never starts another router collector.
        command('chown '+shlex.quote(os.environ['SHINYA_NAS_USER'])+' '+REMOTE+'/tls')
        write(client,REMOTE+'/tls/nginx.conf',NGINX.encode())
        mounts=' --network host -v '+REMOTE+'/tls/nginx.conf:/etc/nginx/nginx.conf:ro -v '+REMOTE+'/tls:/tls:ro '
        command('docker run --rm'+mounts+PROXY+' nginx -t',timeout=30)
        existing_proxy=command("docker ps -a --filter name='^/shinya-hub-https$' --format '{{.Names}}'").strip()
        if existing_proxy:
            command('docker start shinya-hub-https',timeout=30)
            command('docker exec shinya-hub-https nginx -t',timeout=30)
            command('docker exec shinya-hub-https nginx -s reload',timeout=30)
        else:
            command('docker run -d --name shinya-hub-https --restart unless-stopped'+mounts+PROXY,timeout=30)
        verify='credentials='+repr({'account':admin,'password':admin_password})+'\n'+r'''
import requests,time,json
s=requests.Session();s.trust_env=False
for attempt in range(20):
    try:
        h=s.get('https://127.0.0.1:58544/healthz',verify='/tls/hub.pem',timeout=2).json()
        assert h['accountLogin'] and h['hubId'];break
    except Exception:
        if attempt==19:raise
        time.sleep(.5)
base='https://127.0.0.1:58544/api/v1/'
def post(path,body,token=None):return s.post(base+path,json=body,headers={'Authorization':'Bearer '+token} if token else {},verify='/tls/hub.pem',timeout=12).json()
assert post('hub/account/login',{'account':credentials['account'],'password':'invalid-test-password'})['code']==401
login=post('hub/account/login',credentials);assert login['code']==0, 'Admin login failed'
session=login['data'];projects=post('apMonitor/getProjectList2',{},session['token']);assert projects['code']==0
assert post('hub/account/logout',{'refreshToken':session['refreshToken']})['code']==0
assert post('base/homeUser/info',{},session['token'])['code']==401
print(json.dumps({'health':h,'projectCount':len(projects['data']['dataList']),'wrongPasswordRejected':True,'logoutRevoked':True,'tlsVerified':True}))
'''
        proof=json.loads(command('docker run --rm -i --network host --entrypoint python -v '+REMOTE+'/tls:/tls:ro '+IMAGE+' -',stdin=verify.encode(),timeout=45))
        report={'image':IMAGE,'previousContainer':previous,'pairing':pairing,'verification':proof}
        dest=Path('test/_analysis/oct10-accounts/deployment.json');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(json.dumps(report,indent=2),encoding='utf-8')
        print(json.dumps({'stage':'accounts-and-https-verified',**proof}),flush=True)
    except Exception:
        if swapped:
            command('docker rm -f shinya-hub')
            command('docker rename '+previous+' shinya-hub');command('docker start shinya-hub')
            print(json.dumps({'stage':'service-image-rolled-back','accountConfigKept':True}),flush=True)
        raise
    finally:client.close()

if __name__=='__main__':main()
