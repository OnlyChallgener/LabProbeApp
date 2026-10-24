"""Bounded source inspection for the retained tool and game routes."""
import json,re
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

r=load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
for fid in (23925,23932,23371,23378,23933):
 print('\nFUNCTION',fid,'size',r.function_headers[fid].bytecodeSizeInBytes)
 for i in parse_hbc_bytecode(r.function_headers[fid],r):
  print(hex(i.original_pos),str(i))
print('\nGAME GATES')
for i in parse_hbc_bytecode(r.function_headers[23365],r):
 if 0xc60 <= i.original_pos <= 0xf30 or (i.inst.name.startswith('GetById') and i.arg4==44913):print(hex(i.original_pos),str(i))
p=Path('test/_analysis/oct10-priority/macc-public')
s=(p/'vue2Compat-DwKolcHI.js').read_text(encoding='utf8')
for needle in ('LOGIN_API','loginAccount','accountLogin','/login','TUNNEL_CUSTOM_CREATE_API','TUNNEL_CUSTOM_LIST_GET_API'):
 print('\nMACC',needle)
 for m in list(re.finditer(re.escape(needle),s))[:8]:print(s[max(0,m.start()-150):m.end()+330])
