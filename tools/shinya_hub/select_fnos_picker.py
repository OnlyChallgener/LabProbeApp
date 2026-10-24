"""Select NAS/fnOS in the currently open, observed original type picker."""
import json,re,time,xml.etree.ElementTree as E
from pathlib import Path
from adb_feature_audit import adb
ids=set(re.findall(r'mDisplayId=(\d+)',adb('shell','dumpsys','display')))-{'0'}
assert len(ids)==1
view=ids.pop()
def tap(x,y):adb('shell','input','-d',view,'tap',str(x),str(y));time.sleep(.3)
def nodes():
 adb('shell','uiautomator','dump','/sdcard/shinya-picker.xml')
 raw=adb('shell','cat','/sdcard/shinya-picker.xml')
 return [n.attrib for n in E.fromstring(raw).iter('node')]
def rect(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
assert any(n.get('text')=='NAS' for n in nodes())
tap(350,838);tap(300,729)
for index in range(24):
 rows=nodes()
 assert any(n.get('text')=='选择设备类型' for n in rows),'Picker closed unexpectedly'
 chosen=next((n for n in rows if n.get('text')=='飞牛' and len(b:=rect(n))==4 and 760<b[1]<1570),None)
 if chosen:
  b=rect(chosen);tap((b[0]+b[2])//2,(b[1]+b[3])//2)
  print(json.dumps({'selected':'NAS/fnOS','scrolls':index}),flush=True)
  break
 adb('shell','input','-d',view,'swipe','510','1480','510','810','300');time.sleep(.3)
else:raise RuntimeError('fnOS brand not found in observed picker')
