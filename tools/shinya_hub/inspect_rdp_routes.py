from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
r=load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
targets={i:s for i,s in enumerate(r.strings) if 'getRdpTunnel' in s or 'checkStaRdp' in s or s in ('remotectrl','startTime')}
print(targets)
found=[]
for fid,h in enumerate(r.function_headers):
 hits=[]
 for i in parse_hbc_bytecode(h,r):
  if i.inst.name in ('LoadConstString','GetById','GetByIdShort','PutNewOwnById','PutNewOwnByIdShort') and (i.arg2 if i.inst.name=='LoadConstString' else i.arg4 if i.inst.name.startswith('GetById') else i.arg3) in targets:hits.append(i)
 if hits:found.append(fid);print('FUNC',fid,h.bytecodeSizeInBytes,[(hex(i.original_pos),str(i)) for i in hits])
for fid in found:
 if r.function_headers[fid].bytecodeSizeInBytes>1800:continue
 print('\nFUNCTION',fid)
 for i in parse_hbc_bytecode(r.function_headers[fid],r):print(hex(i.original_pos),str(i))
