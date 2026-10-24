"""Runs inside the NAS image: own TLS identity and per-router broker credentials."""
import datetime,json,os,secrets,sys
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from ipaddress import ip_address
sys.path.insert(0,'/opt/shinya-hub')
from shinya_hub.vault import ConfigVault

os.umask(0o077)
vault=ConfigVault(Path('/config'));config=vault.load();root=Path('/mqtt')
root.mkdir(exist_ok=True)
if not (root/'ca.key').exists():
    now=datetime.datetime.now(datetime.timezone.utc)
    ca_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'ShinyaHub private router channel')])
    ca=x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(ca_key.public_key())
    ca=ca.serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=1))
    ca=ca.not_valid_after(now+datetime.timedelta(days=3650)).add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True)
    ca=ca.add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),critical=True).sign(ca_key,hashes.SHA256())
    server_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'ShinyaHub MQTT')])
    server=x509.CertificateBuilder().subject_name(name).issuer_name(subject).public_key(server_key.public_key())
    server=server.serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=1))
    server=server.not_valid_after(now+datetime.timedelta(days=730)).add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
    sans=[x509.DNSName('localhost')]+[x509.IPAddress(ip_address(v)) for v in ('127.0.0.1','192.168.5.46','100.66.1.4')]
    server=server.add_extension(x509.SubjectAlternativeName(sans),critical=False)
    server=server.add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]),critical=False).sign(ca_key,hashes.SHA256())
    for file,key in [('ca.key',ca_key),('server.key',server_key)]:
        (root/file).write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    (root/'ca.crt').write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (root/'server.crt').write_bytes(server.public_bytes(serialization.Encoding.PEM))
settings=config.setdefault('mqttSync',{'host':'127.0.0.1','port':58545,'username':'hub-worker','password':secrets.token_urlsafe(32),'agents':{}})
agents=settings.setdefault('agents',{})
for device in config['devices']:
    sn=device['sn']
    agents.setdefault(sn,{'username':'router-'+sn,'password':secrets.token_urlsafe(32)})
vault.save(config)
(Path('/config')/'mqtt-ca.crt').write_bytes((root/'ca.crt').read_bytes())
credentials=[(settings['username'],settings['password'])]+[(a['username'],a['password']) for a in agents.values()]
# The deployment wrapper converts this file to Mosquitto's salted hashes.
(root/'passwords').write_text('\n'.join(u+':'+p for u,p in credentials)+'\n')
acl=['user '+settings['username'],'topic read shinya/v1/+/up']
for sn,agent in agents.items():
    acl+=['','user '+agent['username'],'topic write shinya/v1/'+sn+'/up']
(root/'acl').write_text('\n'.join(acl)+'\n')
(root/'mosquitto.conf').write_text('''listener 58545 0.0.0.0
protocol mqtt
allow_anonymous false
password_file /mosquitto/config/passwords
acl_file /mosquitto/config/acl
cafile /mosquitto/config/ca.crt
certfile /mosquitto/config/server.crt
keyfile /mosquitto/config/server.key
tls_version tlsv1.2
max_packet_size 1048576
max_queued_messages 100
max_inflight_messages 10
persistence false
log_dest stdout
log_type error
log_type warning
''')
print(json.dumps({'configuredRouters':len(config['devices']),'port':58545,'authentication':'per-router','tls':True}))
