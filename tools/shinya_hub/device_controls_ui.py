"""Inspect either App's observed display, with screenshots and bounded UI text."""
import argparse, subprocess, xml.etree.ElementTree as E
from pathlib import Path

ADB='D:/Tools/labprobe-ui/android-sdk/platform-tools/adb.exe'
OUT=Path('test/_analysis/oct11-device-controls')
SURFACES={'official':('4','4619827124781842690','cn.com.ruijie.shinya2'),
          'hub':('5','4619827551948147201','com.labprobe.shinyahub')}
def adb(*args):return subprocess.check_output([ADB,'-s','127.0.0.1:5555',*args])
def dump(surface,label):
    OUT.mkdir(parents=True,exist_ok=True)
    display,physical,package=SURFACES[surface]
    adb('shell','uiautomator','dump','/sdcard/device-controls-ui.xml')
    adb('pull','/sdcard/device-controls-ui.xml',str(OUT/(label+'.xml')))
    for n in E.parse(OUT/(label+'.xml')).iter('node'):
        value=n.get('text') or n.get('content-desc')
        if n.get('package')==package and value and n.get('password')!='true':
            print(value[:110],n.get('bounds'))
    (OUT/(label+'.png')).write_bytes(adb('exec-out','screencap','-d',physical,'-p'))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('surface',choices=SURFACES);p.add_argument('label')
    args=p.parse_args();dump(args.surface,args.label)
