import io
import sys
from pathlib import Path
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

r=HBCReader()
r.read_whole_file(io.BytesIO(Path('test/_analysis/oct13-official-game/official.bundle').read_bytes()))
for spec in sys.argv[1:]:
    parts=spec.split(':'); fid=int(parts[0]); start=int(parts[1]) if len(parts)>1 else 0
    limit=int(parts[2]) if len(parts)>2 else 1000
    header=r.function_headers[fid]
    print('FID',fid,'name',r.strings[header.functionName],'size',header.bytecodeSizeInBytes)
    for i in list(parse_hbc_bytecode(header,r))[start:start+limit]:
        args=[getattr(i,'arg'+str(n)) for n in range(1,9) if hasattr(i,'arg'+str(n))]
        sid=None
        if 'String' in i.inst.name and len(args)>1:sid=args[1]
        elif 'ById' in i.inst.name:
            sid=args[-1]
        text=repr(r.strings[sid]) if isinstance(sid,int) and sid<len(r.strings) else ''
        if 'WithBuffer' in i.inst.name:
            text=repr(i)
        print(hex(i.original_pos),i.inst.name,args,text)
