"""Fill the authorized HUB account in the private test app; never print secrets."""
import os,re,time,xml.etree.ElementTree as ET
from adb_feature_audit import adb
inventory=adb('shell','dumpsys','display')
ids=set(re.findall(r'mDisplayId=(\d+)',inventory))-{'0'}
assert len(ids)==1
display=ids.pop()
adb('shell','uiautomator','dump','/sdcard/qa-login.xml')
raw=adb('shell','cat','/sdcard/qa-login.xml')
nodes=[n.attrib for n in ET.fromstring(raw).iter('node')]
assert any(n.get('package')=='com.labprobe.shinyahub' for n in nodes)
assert any(n.get('text')=='星耀家 Hub' for n in nodes)
def tap(bounds):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',bounds))
 adb('shell','input','-d',display,'tap',str((x1+x2)//2),str((y1+y2)//2))
edit=[n for n in nodes if n.get('class')=='android.widget.EditText']
assert len(edit)==3
assert edit[0]['text']=='https://127.0.0.1:58544/'
assert edit[2].get('password')=='true','Password field must remain masked'
# Account is already populated. Fill only the empty, masked password.
if edit[2].get('text')=='密码':
 tap(edit[2]['bounds'])
 adb('shell','input','-d',display,'text',os.environ['SHINYA_ADMIN_PASSWORD'])
 adb('shell','input','-d',display,'keyevent','4')
login=next(n for n in nodes if n.get('text')=='登录')
tap(login['bounds'])
print('Authorized HUB login submitted with password masked')
