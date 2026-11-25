"""Bounded, read-only ADB navigation checks against observed accessibility nodes.

No login, configuration submission, test traffic, or project creation/deletion.
Stops when a requested label is absent instead of guessing a tap location.
"""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path

ADB='D:/Program Files (x86)/pcsuite/adb_41/adb.exe'
PKG='com.labprobe.shinyahub'

def run(args):
    return subprocess.run([ADB,*args],capture_output=True,timeout=18,check=True).stdout.decode('utf8','replace')

def rect(node):return [int(v) for v in re.findall(r'\d+',node.get('bounds',''))]
def labels(root):return [n.get('text') or n.get('content-desc') or '' for n in root.iter('node')]

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    actions=[];initial_pid=run(['shell','pidof',PKG]).strip();t0=time.time()
    def display():
        activity=run(['shell','dumpsys','activity','activities'])
        for block in re.split(r'(?=Display #\d+)',activity):
            if 'topResumedActivity=' in block and PKG in block.split('topResumedActivity=',1)[1].split('\n',1)[0]:
                return re.search(r'Display #(\d+)',block).group(1)
        raise RuntimeError('Target app is not foreground; no input sent')
    def tree():
        run(['shell','uiautomator','dump','--compressed','/data/local/tmp/shinya-navigation-check.xml'])
        raw=run(['shell','cat','/data/local/tmp/shinya-navigation-check.xml'])
        root=ET.fromstring(raw)
        if not any(n.get('package')==PKG for n in root.iter('node')):raise RuntimeError('UI tree belongs to a different app')
        (a.output/('step-%02d.xml'%len(actions))).write_text(raw,encoding='utf8')
        text=labels(root)
        if any('发生了些错误' in s for s in text):raise RuntimeError('App error boundary visible')
        if not run(['shell','pidof',PKG]).strip()==initial_pid:raise RuntimeError('App process changed during navigation')
        return root
    def tap(label,bottom=False):
        root=tree();candidates=[]
        for n in root.iter('node'):
            text=n.get('text') or n.get('content-desc') or ''
            bounds=rect(n)
            if text==label and len(bounds)==4 and (not bottom or bounds[1]>1500):candidates.append(bounds)
        if not candidates:raise RuntimeError('Required label absent: '+label)
        x0,y0,x1,y1=min(candidates,key=lambda b:(b[2]-b[0])*(b[3]-b[1]))
        run(['shell','input','-d',display(),'tap',str((x0+x1)//2),str((y0+y1)//2)])
        actions.append(label);time.sleep(.35)
    result={'initialPid':initial_pid,'steps':actions,'passed':False}
    try:
        root=tree()
        # A detail page has no bottom navigation. Back only, never submit.
        for _ in range(3):
            if any(s=='工具' for s in labels(root)):break
            run(['shell','input','-d',display(),'keyevent','4']);actions.append('back');root=tree()
        for _ in range(3):
            for label in ('工具','我的','游戏','首页'):tap(label,True)
        tap('我的',True);root=tree()
        rows={n.get('text'):rect(n) for n in root.iter('node') if n.get('text') in ('MACC 账号','分享管理','关于星耀家 HUB')}
        if 'MACC 账号' not in rows:raise RuntimeError('MACC row missing')
        result['menuBounds']=rows
        if '分享管理' in rows and abs(rows['MACC 账号'][0]-rows['分享管理'][0])>3:raise RuntimeError('MACC text is not aligned with share row')
        tap('工具',True);tree();result['passed']=True
    except Exception as error:result['error']=str(error)
    result['elapsedSeconds']=round(time.time()-t0,2)
    (a.output/'navigation-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(result,ensure_ascii=True))
    if not result['passed']:raise SystemExit(2)

if __name__=='__main__':main()
