"""Read-only diagnostics of the deployed private child service; never print tokens."""
import os,sys,json
from pathlib import Path
from remote_transport import connect_nas,run

CODE=r'''
import sys,json,traceback,requests
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.api import create_app,Principal,token_digest
settings=ConfigVault(Path('/config')).load()
store=ProjectStore(Path('/data/shinya.sqlite'))
projects=store.projects('nas-admin')
print(json.dumps({'projects':[{'projectId':p['projectId'],'sns':[d['sn'] for d in p['apList']]} for p in projects]}),flush=True)
app=create_app(store,None,{token_digest('local-debug'):Principal('nas-admin','debug',False)})
def trace(frame,event,arg):
    if event=='exception' and frame.f_code.co_filename.endswith('/api.py'):
        typ,value,tb=arg
        if typ not in (StopIteration,GeneratorExit):
            print(json.dumps({'exception':typ.__name__,'line':frame.f_lineno,'message':str(value)[:180]}),flush=True)
    return trace
sys.settrace(trace)
response=app.test_client().post('/homewlan/apMonitor/getProjectList2',headers={'Authorization':'Bearer local-debug'},json={}).get_json()
sys.settrace(None)
print(json.dumps({'isolatedCode':response['code'],'msg':response['msg'],'count':len((response.get('data') or {}).get('dataList',[]))}),flush=True)
s=requests.Session();s.trust_env=False
base='http://127.0.0.1:58543/'
v=s.post(base+'api/v1/base/homeUser/accountPasswordLogin',json={'account':ACCOUNT,'password':PASSWORD},timeout=8).json()
s.headers['Authorization']='Bearer '+(v['data'].get('token') or v['data'].get('accessToken'))
for path,body in [('homewlan/apMonitor/getProjectList2',{}),('api/v1/netguard/staGuard/getProtectStaListPage',{'mainApSn':'G1TD8RX039025'})]:
    v=s.post(base+path,json=body,timeout=25).json()
    print(json.dumps({'path':path,'code':v['code'],'msg':v['msg'],'dataType':type(v.get('data')).__name__}),flush=True)
'''

def main():
    code=CODE.replace('ACCOUNT',repr(os.environ['SHINYA_ADMIN_ACCOUNT'])).replace('PASSWORD',repr(os.environ['SHINYA_ADMIN_PASSWORD']))
    code='try:\n'+''.join('    '+line+'\n' for line in code.splitlines())+'except Exception:\n    import traceback\n    print(traceback.format_exc(),flush=True)\n'
    c=connect_nas()
    try:
        out=run(c,'docker exec -i shinya-hub python -',stdin=code.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=80)
        print(out.decode(),flush=True)
    finally:c.close()
if __name__=='__main__':main()
