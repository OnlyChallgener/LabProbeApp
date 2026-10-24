import sys,importlib.util,unittest,copy
from pathlib import Path
from contextlib import contextmanager
sys.path.insert(0,'D:/Github/ShinyaHub')
spec=importlib.util.spec_from_file_location('shinya_hub.web_sync',Path('tools/shinya_hub/web_sync_source.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
from shinya_hub.devices import DeviceKey
class Store:
 def __init__(self):self.values={}
 def cached(self,*k):return self.values.get(k)
 def save(self,*args):self.values[tuple(args[:-1])]={'value':args[-1]}
class Registry:
 def __init__(self):self.names=[{'mac':'02:00:00:00:00:01','name':'Other'}];self.writes=[];self.ignore=False
 @contextmanager
 def device_operation(self,key):yield self
 def execute(self,c):
  if c.action=='get':return {'list':copy.deepcopy(self.names)}
  self.writes.append(c)
  if not self.ignore:
   for row in c.data['list']:
    old=next((v for v in self.names if v['mac']==row['mac']),None)
    if old:old.update(row)
    else:self.names.append(row)
  return {'code':0}
 resource=staticmethod(lambda c:'native.note')
class Tests(unittest.TestCase):
 def setUp(self):
  self.store=Store();self.r=Registry();self.sync=module.WebSync(self.store,self.r);self.key=DeviceKey('t','p','A')
  self.sync.desired=lambda k:[{'mac':'02:00:00:00:00:02','name':'NAS'}]
 def test_enable_writes_readbacks_and_retains_other_names(self):
  self.assertEqual('on',self.sync.set(self.key,'on'));self.assertEqual('on',self.sync.status(self.key))
  self.assertEqual('Other',self.r.names[0]['name']);self.assertEqual('NAS',self.r.names[1]['name'])
  self.assertEqual('off',self.sync.status(DeviceKey('t','q','B')))
 def test_disable_does_not_delete_native_names(self):
  self.sync.set(self.key,'off');self.assertFalse(self.r.writes);self.assertEqual(1,len(self.r.names))
 def test_failed_readback_does_not_enable(self):
  self.r.ignore=True
  with self.assertRaises(ValueError):self.sync.set(self.key,'on')
  self.assertEqual('off',self.sync.status(self.key))
 def test_native_character_filter(self):self.assertEqual('飞牛NAS_#-12',module.clean_name('飞牛 NAS_#-12（🙂）'))
if __name__=='__main__':unittest.main()
