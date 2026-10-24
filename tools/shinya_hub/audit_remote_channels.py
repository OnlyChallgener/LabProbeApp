"""Official app URL metadata and anonymous endpoint checks; never export tokens."""
import json,re,subprocess,time
from pathlib import Path
from urllib.parse import urlsplit,parse_qs,urlunsplit
import requests

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'test/_analysis/oct08-shinya-hub/cloud-sync'
ADB='D:/Tools/labprobe-ui/android-sdk/platform-tools/adb.exe'
def adb(*args):
    p=subprocess.run([ADB,'-s','127.0.0.1:5555',*args],capture_output=True,timeout=25,check=True)
    return p.stdout.decode('utf-8','replace')
def public_url(url):
    p=urlsplit(url)
    return {'scheme':p.scheme,'host':p.hostname,'port':p.port,'path':p.path,
            'queryFieldNames':sorted(parse_qs(p.query)),'hasFragment':bool(p.fragment)}

def main():
    text=adb('logcat','-d','-t','2500','-s','ReactNativeJS:I','ActivityTaskManager:I')
    result={'capturedAt':time.time(),'urlMetadata':[],'anonymousChecks':[]}
    found=[]
    for line in text.splitlines():
        if not any(v in line for v in ('result','getTunnel','webview_shell','getRdp','checkStaRdp')):continue
        for match in re.findall(r'https?://[^\s\'\"<>)},]+',line):
            try:
                p=urlsplit(match)
                if p.hostname and (p.hostname.startswith('47.') or p.hostname.endswith('ruijie.com.cn') or p.hostname=='111.23.167.101'):
                    found.append(match)
            except ValueError:pass
    for url in dict.fromkeys(found):
        meta=public_url(url)
        result['urlMetadata'].append(meta)
        # A separate HTTP session has no App or browser cookies. Only fetch HTML.
        if meta['scheme']=='http' and meta['port']:
            s=requests.Session();s.trust_env=False
            try:
                r=s.get(urlunsplit((urlsplit(url).scheme,urlsplit(url).netloc,'/','','')),timeout=(3,6))
                result.setdefault('freshWebChecks',[]).append({'host':meta['host'],'port':meta['port'],'httpStatus':r.status_code,
                    'hasLoginForm':bool(re.search(r'login|password|密码',r.text,re.I)),
                    'htmlTitle':re.findall(r'<title>(.*?)</title>',r.text,re.I)[:1]})
            except Exception as e:result.setdefault('freshWebChecks',[]).append({'host':meta['host'],'port':meta['port'],'error':type(e).__name__})
    s=requests.Session();s.trust_env=False
    for path in ('/homewlan/homeAp/getTunnelUrl','/homewlan/remoteAccess/checkStaRdp','/homewlan/remoteAccess/getRdpTunnel'):
        try:
            r=s.post('https://shinyaapp.ruijie.com.cn'+path,json={},headers={'type':'shinyaApp'},timeout=(4,8))
            try:
                obj=r.json();code=obj.get('code');message=obj.get('msg') or obj.get('message')
            except ValueError:code=message=None
            result['anonymousChecks'].append({'path':path,'httpStatus':r.status_code,'code':code,
                'message':str(message)[:150] if message else None})
        except Exception as e:result['anonymousChecks'].append({'path':path,'error':type(e).__name__})
    OUT.mkdir(exist_ok=True,parents=True)
    (OUT/'official-remote-channels.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False),flush=True)

if __name__=='__main__':main()
