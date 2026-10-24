import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
header = reader.function_headers[7220]
insts = list(parse_hbc_bytecode(header, reader))
for inst in insts:
    if 770 <= inst.original_pos <= 785:
        name = inst.inst.name
        args = [getattr(inst, 'arg' + str(n+1)) for n in range(4) if hasattr(inst, 'arg' + str(n+1))]
        desc = ''
        if 'String' in name and len(args) > 1:
            desc = f'"{reader.strings[args[1]]}"'
        elif 'ById' in name and len(args) > 2:
            desc = f'"{reader.strings[args[2]]}"'
        print(f'{inst.original_pos:5d}: {name:20s} {str(args):15s} {desc}')
