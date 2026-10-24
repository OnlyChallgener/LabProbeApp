"""Focused static references from the exact installed vendor Hermes artifact."""
import hashlib,io,json,struct
from pathlib import Path
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

path=Path('test/_analysis/oct13-official-game/official.bundle');raw=path.read_bytes()
digest=hashlib.sha256(raw).hexdigest()
assert digest=='fe8407a4f3f799c1867322ed546d6e8640f27b118d86eb5c99511a17446a930d'
r=HBCReader();r.read_whole_file(io.BytesIO(raw))
names={'optimizeChannelMode','setOptimizeChannelMode','changeChannelMode','openModeChange','/eSportAccelerate/setOptimizeChannelMode'}
ids={i:s for i,s in enumerate(r.strings) if s in names}
needles=[struct.pack('<H',i) if i<65536 else struct.pack('<I',i) for i in ids]
found=[]
for fid,h in enumerate(r.function_headers):
    if not any(n in raw[h.offset:h.offset+h.bytecodeSizeInBytes] for n in needles):continue
    matches=[]
    for instruction in parse_hbc_bytecode(h,r):
        args=[getattr(instruction,'arg'+str(n)) for n in range(1,9) if hasattr(instruction,'arg'+str(n))]
        opcode=instruction.inst.name
        sid=args[1] if 'String' in opcode and len(args)>1 else args[-1] if 'ById' in opcode and args else None
        if sid in ids:matches.append({'offset':instruction.original_pos,'opcode':opcode,'args':args,'text':ids[sid]})
    if matches:found.append({'fid':fid,'name':r.strings[h.functionName],'bytecodeOffset':h.offset,'matches':matches})
output={'artifact':str(path),'sha256':digest,'authority':'static-Hermes-bytecode','executionVerified':False,'functions':found}
Path('test/_analysis/oct13-official-game/rea-game/noise-mode-contract.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(found,ensure_ascii=False))
