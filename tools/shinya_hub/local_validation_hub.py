"""Temporary loopback-only HUB using the verified real BE72 eWeb transport.

Not a NAS deployment. No official account tokens or feature-library writes.
"""
import atexit,json,os,sys,threading,time
from pathlib import Path
from werkzeug.serving import make_server,WSGIRequestHandler
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.api import create_app
from shinya_hub.auth import AccountStore
from shinya_hub.commands import UFCommand
from shinya_hub.devices import DeviceKey,DeviceDriver,DeviceRegistry
from shinya_hub.store import ProjectStore
from shinya_hub.assets import PublicIconStore
from shinya_hub.catalog import AppCatalog
from shinya_hub.telemetry import USER_LIST,WAN_FLOW

root=Path('test/_analysis/oct10-local-validation').resolve()
root.mkdir(parents=True,exist_ok=True)
key=DeviceKey('local-qa','hub:G1TD8RX039025','G1TD8RX039025')
driver=DeviceDriver('http://111.23.167.101:12597',os.environ['SHINYA_BE72_PASSWORD'],'be72',expected_sn=key.sn)
identity=driver.execute(UFCommand('dev_sta','get','sysinfo',None))
assert identity.get('serial_num')==key.sn
store=ProjectStore(root/'shinya.sqlite')
store.add_project(key.tenant,key.project,'BE72-PRO(行者)')
store.bind_device(key.tenant,key.project,key.sn,{'model':'BE72-PRO','name':'BE72','aliasName':'BE72','isMain':True,'forwardMode':'ROUTER'})
registry=DeviceRegistry(store,workers=2,max_pending=16)
registry.register(key,driver)
accounts=AccountStore(root/'accounts.sqlite')
if not accounts.has_accounts():accounts.create_account(os.environ['SHINYA_ADMIN_ACCOUNT'],os.environ['SHINYA_ADMIN_PASSWORD'],key.tenant,'Demon',can_write=True)
app=create_app(store,registry,{},enabled_writes={('ac_config','devLed')},icons=PublicIconStore(root/'icons'),catalog=AppCatalog(),accounts=accounts,allow_legacy_tokens=False)
commands=[UFCommand('dev_sta','get','sysinfo',None),USER_LIST,WAN_FLOW,*[UFCommand(a,'get',b,None) for a,b in [('dev_sta','overview'),('dev_sta','radioInfo'),('dev_cap','gateway'),('dev_cap','eweb'),('ac_config','wireless'),('ac_config','timeReboot'),('ac_config','devLed'),('dev_sta','ap_list')]]]
for command in commands:
 try:
  registry.refresh(key,command).result(timeout=12)
 except Exception as error: print(json.dumps({'warmModule':command.module,'error':type(error).__name__}),flush=True)
stop=threading.Event()
def collect():
 while not stop.wait(5):
  for command in commands:
   registry.cached_read(key,command,ttl=5 if command in (USER_LIST,WAN_FLOW) else 30 if command.module in ('sysinfo','overview') else 120)
threading.Thread(target=collect,daemon=True).start()
class Quiet(WSGIRequestHandler):
 def log_message(self,*args):pass
server=make_server('127.0.0.1',15943,app,threaded=True,request_handler=Quiet)
print(json.dumps({'ready':True,'loopbackPort':15943,'verifiedSerial':key.sn,'source':'real BE72 firmware','nasDeployment':False}),flush=True)
try:server.serve_forever()
finally:
 stop.set();app.extensions['shinya_diagnosis'].close();registry.close();server.server_close()
