import json
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

# Let's inspect FID 22182 and 22183
for fid in [22182, 22183]:
    header = reader.function_headers[fid]
    print(f"\n================ FID {fid} ({index[fid].get('name')}) ================")
    insts = list(parse_hbc_bytecode(header, reader))
    for i in insts:
        # print instructions that set properties or load strings/objects
        name = i.inst.name
        if 'Put' in name or 'Get' in name or 'String' in name or 'Object' in name or 'Array' in name:
            line_str = str(i)
            # filter out common noisy lines
            print(f"  {hex(i.original_pos)}: {line_str}")
