"""Authorized login/refresh and optional short-lived access tests. No token export."""
import argparse
import json
import hashlib
import os
import sys
import time
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
from urllib.parse import urlsplit,parse_qs

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.vault import ConfigVault

BASE='https://shinyaapp.ruijie.com.cn'
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'test/_analysis/oct08-shinya-hub/cloud-sync/official-account-check.json'


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--channels',action='store_true');args=parser.parse_args()
    session=requests.Session();session.trust_env=False
    session.mount('https://',HTTPAdapter(max_retries=Retry(total=2,connect=2,read=0,status=0,
                                                          backoff_factor=.2,allowed_methods={'GET','POST'})))
    headers={'type':'shinyaApp','Accept':'application/json'}
    result={'capturedAt':time.time(),'scope':'authorized-account-session-and-short-lived-tunnel-tests' if args.channels
            else 'authorized-account-login-read-and-refresh-only', 'checks':[]}
    vault=ConfigVault(ROOT/'test/_keys/shinya-official-account')
    account_hash=hashlib.sha256(os.environ['SHINYA_OFFICIAL_ACCOUNT'].encode()).hexdigest()
    def post(path,data,method='POST'):
        try:
            response=session.request(method,BASE+path,json=data if method=='POST' else None,
                                     headers=headers,timeout=(5,12))
        except requests.RequestException as exc:
            result['checks'].append({'path':path,'error':type(exc).__name__})
            return {}
        try:value=response.json()
        except ValueError:value={}
        check={'path':path,'httpStatus':response.status_code,'code':value.get('code')}
        message=str(value.get('msg') or value.get('message') or '')
        # Successful WEB responses put an encrypted login credential in msg.
        # Keep only plain error descriptions, never response URLs or JSON.
        if value.get('code')!=0 and not message.lstrip().startswith(('{','[')) and '://' not in message:
            check['message']=message[:120]
        result['checks'].append(check)
        return value
    try:cached=vault.load()
    except FileNotFoundError:cached={}
    data=cached if cached.get('accountHash')==account_hash and cached.get('expireTime',0)>time.time()*1000 else None
    if data and data.get('token'):
        result['sessionSource']='encrypted-local-test-session'
    else:
        login=post('/api/v1/base/homeUser/accountPasswordLogin',{
            'account':os.environ['SHINYA_OFFICIAL_ACCOUNT'],'password':os.environ['SHINYA_OFFICIAL_PASSWORD']})
        data=login.get('data');result['sessionSource']='official-password-api'
    token=data.get('token') if isinstance(data,dict) else None
    result['tokenReturned']=isinstance(token,str) and bool(token)
    if result['tokenReturned']:
        vault.save({'accountHash':account_hash,'token':token,'expireTime':data.get('expireTime',0)})
        result['expiresAt']=data.get('expireTime')
        headers['Authorization']='Bearer '+token
        info=post('/api/v1/base/homeUser/info',{},method='GET')
        result['accountInfoAccepted']=info.get('code')==0
        listing=post('/homewlan/apMonitor/getProjectList2',{})
        projects=listing.get('data',{})
        if isinstance(projects,dict):projects=projects.get('dataList',[])
        result['projectListAccepted']=listing.get('code')==0
        if isinstance(projects,list):
            result['projects']=[{'projectId':p.get('projectId') or p.get('id') or p.get('buildingId'),
                 'fields':sorted(p),'name':p.get('projectName') or p.get('name'),
                 'apCount':p.get('totalAp'),'mainApSn':p.get('mainApSn'),
                 'deviceSerials':[p.get('mainApSn')] if p.get('mainApSn') else []}
                 for p in projects if isinstance(p,dict) and ('BE72' in str(p.get('name')) or 'BE50' in str(p.get('name')))]
            for project in result['projects']:
                headers['projectId']=str(project['projectId'])
                devices=post('/homewlan/homeAp/getDeviceInfoList2',{'projectId':project['projectId'],
                              'day':datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d')})
                native=devices.get('data')
                project['deviceListAccepted']=devices.get('code')==0
                if isinstance(native,dict):
                    project['deviceResultFields']=sorted(native)
                    rows=native.get('apList') or native.get('dataList') or []
                elif isinstance(native,list):rows=native
                else:rows=[]
                if isinstance(rows,list):
                    project['devices']=[{k:row.get(k) for k in ('sn','apSn','serial_num','name','model','isMain')
                                        if k in row} for row in rows if isinstance(row,dict)]
                    project['deviceFields']=sorted({k for row in rows if isinstance(row,dict) for k in row})
                    project['deviceSerials']=[row.get('sn') or row.get('apSn') or row.get('serial_num')
                                              for row in rows if isinstance(row,dict)]
                    physical={'18850348':'G1TD8RX039025','17953001':'G1TQ88T702111'}.get(str(project['projectId']))
                    if physical in project['deviceSerials']:project['verifiedSerial']=physical
        refresh=post('/api/v1/base/homeUser/refreshToken',{})
        data=refresh.get('data')
        new=data.get('token') if isinstance(data,dict) else None
        result['refreshAccepted']=refresh.get('code')==0 and isinstance(new,str) and bool(new)
        if result['refreshAccepted']:
            vault.save({'accountHash':account_hash,'token':new,'expireTime':data.get('expireTime',0)})
            headers['Authorization']='Bearer '+new
            result['refreshedTokenAccepted']=post('/homewlan/apMonitor/getProjectList2',{}).get('code')==0
            if args.channels:
                selected=next((p for p in result.get('projects',[]) if str(p.get('projectId'))=='18850348'),None)
                if not selected or not selected.get('verifiedSerial'):
                    result['channelSelectionError']='The account did not return the authorized BE72 project and serial'
                    save_result(result)
                    return
                project=str(selected['projectId']);headers['projectId']=project
                web=post('/homewlan/homeAp/getTunnelUrl',{'projectId':project,'sn':selected['verifiedSerial']})
                url=web.get('data')
                if isinstance(url,str):
                    parts=urlsplit(url);result['webTunnel']={'scheme':parts.scheme,'host':parts.hostname,
                        'port':parts.port,'queryFieldNames':sorted(parse_qs(parts.query)),
                        'hasNonRootPath':parts.path not in ('','/')}
                check=post('/homewlan/remoteAccess/checkStaRdp',{'projectId':project,'ip':'192.168.5.201'})
                result['rdpCheckAccepted']=check.get('code')==0
                tunnel=post('/homewlan/remoteAccess/getRdpTunnel',{'projectId':project,'staIp':'192.168.5.201'})
                data=tunnel.get('data')
                result['rdpTunnelAccepted']=tunnel.get('code')==0
                if isinstance(data,dict):
                    result['rdpTunnel']={k:data.get(k) for k in ('ip','port','startTime')}
    save_result(result)


def save_result(result):
    OUT.parent.mkdir(parents=True,exist_ok=True)
    text=json.dumps(result,ensure_ascii=False,indent=2)
    OUT.with_name('official-account-attempt-'+str(int(result['capturedAt']))+'.json').write_text(text,encoding='utf-8')
    if result['tokenReturned']:OUT.write_text(text,encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False),flush=True)


if __name__=='__main__':main()
