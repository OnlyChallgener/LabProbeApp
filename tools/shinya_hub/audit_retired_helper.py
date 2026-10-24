from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
r=load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
ids={i for i,s in enumerate(r.strings) if s in ('Helper','HelperScreen')}
print('Helper strings',[(i,r.strings[i]) for i in ids])
for fid,h in enumerate(r.function_headers):
 found=[]
 for ins in parse_hbc_bytecode(h,r):
  if ins.inst.name in ('LoadConstString','LoadConstStringLongIndex') and ins.arg2 in ids:found.append((ins.original_pos-h.offset,ins.arg2))
 if found:print(fid,r.strings[h.functionName],found)
