import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

analysis = Path('test/_analysis/oct08-shinya-hub')
reader = load((analysis/'index.android.bundle').read_bytes())

for fid in [21904, 27827, 35586, 33333]:
    header = reader.function_headers[fid]
    insts = parse_hbc_bytecode(header, reader)
    print(f'=== FID {fid} ===')
    for inst in insts:
        name = inst.inst.name
        args = [getattr(inst, 'arg' + str(n+1)) for n in range(4) if hasattr(inst, 'arg' + str(n+1))]
        desc = ''
        if 'String' in name and len(args) > 1:
            desc = f'"{reader.strings[args[1]]}"'
        elif 'ById' in name and len(args) > 2:
            desc = f'"{reader.strings[args[2]]}"'
        if any(w in desc for w in ['StaInfo', 'sta', 'mac', 'navigate', 'params', 'filter', 'route']):
            print(f'  {inst.original_pos}: {name} {args} -> {desc}')
