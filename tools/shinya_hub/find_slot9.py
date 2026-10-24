import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
header = reader.function_headers[33301]
insts = list(parse_hbc_bytecode(header, reader))

for inst in insts:
    name = inst.inst.name
    if 'Environment' in name:
        args = [getattr(inst, 'arg' + str(n+1)) for n in range(4) if hasattr(inst, 'arg' + str(n+1))]
        if len(args) >= 3 and args[0] == 8 and args[1] == 9:
            print(f'{inst.original_pos:5d}: {name:20s} {str(args):15s}')
        elif 'Store' in name and len(args) >= 3 and args[1] == 9:
            print(f'{inst.original_pos:5d}: {name:20s} {str(args):15s}')
