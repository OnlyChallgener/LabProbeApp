"""Enter an explicitly selected field without logging its credential value."""
import argparse
import os
import re
import subprocess
from pathlib import Path

ADB=Path('D:/Tools/labprobe-ui/android-sdk/platform-tools/adb.exe')
def adb(*args):
    return subprocess.run([str(ADB),'-s','127.0.0.1:5555',*args],check=True,
                          capture_output=True,timeout=25).stdout.decode('utf-8','replace')

def main():
    p=argparse.ArgumentParser();p.add_argument('environment');p.add_argument('x');p.add_argument('y')
    p.add_argument('--replace',action='store_true');a=p.parse_args()
    value=os.environ[a.environment]
    # adb shell input accepts a shell token. Restrict this helper to known
    # ASCII login values and never interpolate shell metacharacters.
    if not re.fullmatch(r'[A-Za-z0-9 .:/_-]+',value):raise ValueError('Unsupported input characters')
    ids=set(re.findall(r'mDisplayId=(\d+)',adb('shell','dumpsys','display')))-{'0'}
    if len(ids)!=1:raise ValueError('Expected one AppBao display')
    display=ids.pop();adb('shell','input','-d',display,'tap',a.x,a.y)
    if a.replace:
        adb('shell','input','-d',display,'keyevent','123')
        adb('shell','input','-d',display,'keycombination','113','29')
        adb('shell','input','-d',display,'keyevent','67')
    adb('shell','input','-d',display,'text',value.replace(' ','%s'))
    print('Selected field entered')

if __name__=='__main__':main()
