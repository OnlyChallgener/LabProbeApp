"""Small ADB inspection helper: bounded text and local screenshots."""
import subprocess,sys,xml.etree.ElementTree as E
from pathlib import Path
ADB='D:/Tools/labprobe-ui/android-sdk/platform-tools/adb.exe'
OUT=Path('test/_analysis/oct11-children')
def adb(*args):return subprocess.check_output([ADB,'-s','127.0.0.1:5555',*args])
def dump(label='current'):
    adb('shell','uiautomator','dump','/sdcard/child-ui.xml')
    adb('pull','/sdcard/child-ui.xml',str(OUT/(label+'.xml')))
    root=E.parse(OUT/(label+'.xml'))
    for n in root.iter('node'):
        text=n.get('text') or n.get('content-desc')
        if text and n.get('password')!='true':print(text[:100],n.get('bounds'),n.get('package'))
    (OUT/(label+'.png')).write_bytes(adb('exec-out','screencap','-d','4619827551948147201','-p'))
if __name__=='__main__':dump(sys.argv[1] if len(sys.argv)>1 else 'current')
