import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('access','D:/Github/labprobe-hub/.tmp/zerotier_access.py')
access=importlib.util.module_from_spec(spec);spec.loader.exec_module(access)
client=access.be72()
try:
 data=access.run(client,'cat /usr/local/lua/dev_sta/speedtest.lua',timeout=15)
 path=Path('D:/Github/LabProbeApp/test/be50-speedtest-20261024/be72-speedtest-module.lua')
 path.write_bytes(data)
 print('bytes',len(data),'path',path)
finally:client.close()
