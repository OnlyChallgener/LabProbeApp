"""Authenticated QA through the existing loopback TLS relay, with cert pinning."""
from contextlib import contextmanager
import os
import requests
from requests.adapters import HTTPAdapter
from urllib3.exceptions import InsecureRequestWarning
import warnings

PIN='7f9db9ae131250059dd9a0a307ed659759cbb7a39cf08c2b3fb361544c3811f6'
BASE='https://127.0.0.1:15845/api/v1/'

class PinnedAdapter(HTTPAdapter):
    def init_poolmanager(self,*args,**kwargs):
        kwargs['assert_fingerprint']=PIN
        return super().init_poolmanager(*args,**kwargs)

def post(session,path,body):
    with warnings.catch_warnings():
        warnings.simplefilter('ignore',InsecureRequestWarning)
        value=session.post(BASE+path,json=body,timeout=15).json()
    if value.get('code')!=0:raise RuntimeError(path+': '+str(value.get('code'))+' '+str(value.get('msg','')))
    return value['data']

@contextmanager
def account():
    session=requests.Session();session.trust_env=False;session.verify=False
    session.mount('https://127.0.0.1:15845/',PinnedAdapter())
    login=post(session,'hub/account/login',{'account':os.environ['SHINYA_ADMIN_ACCOUNT'],
                                         'password':os.environ['SHINYA_ADMIN_PASSWORD']})
    session.headers['Authorization']='Bearer '+login['token']
    try:yield session
    finally:
        try:post(session,'hub/account/logout',{'refreshToken':login['refreshToken']})
        finally:session.close()
