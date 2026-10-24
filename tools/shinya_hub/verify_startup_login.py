"""Execute both shipped startup getters; neither may read stale JS storage."""
import sys
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
r=load(Path(sys.argv[1]).read_bytes())
for fid,method in ((8364,'getToken'),(8369,'getExpireTime')):
    for verified in (None,'verified-native-result'):
        calls=[];promise=object();regs={}
        def native():calls.append(verified);return promise
        def require(n):
            assert n==18
            return {'NativeModules':{'sta':{method:native}}}
        for x in parse_hbc_bytecode(r.function_headers[fid],r):
            n=x.inst.name;a=[getattr(x,'arg'+str(i)) for i in range(1,6) if hasattr(x,'arg'+str(i))]
            if n=='GetGlobalObject':regs[a[0]]={'__r':require}
            elif n in ('GetById','GetByIdShort'):regs[a[0]]=regs[a[1]][r.strings[a[3]]]
            elif n=='LoadConstUndefined':regs[a[0]]=None
            elif n=='LoadConstUInt8':regs[a[0]]=a[1]
            elif n=='Mov':regs[a[0]]=regs[a[1]]
            elif n=='Call2':regs[a[0]]=regs[a[1]](regs[a[3]])
            elif n=='Call1':regs[a[0]]=regs[a[1]]()
            elif n=='Ret':assert regs[a[0]] is promise;break
            else:raise AssertionError(n)
        assert calls==[verified]
print('Startup token and expiry both await native results; stale AsyncStorage cannot decide the restored login')
