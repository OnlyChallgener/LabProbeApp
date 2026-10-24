import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
header = reader.function_headers[33301]
insts = list(parse_hbc_bytecode(header, reader))

for i, inst in enumerate(insts):
    # Check if inst loads [8, 2] from environment
    if inst.inst.name == 'LoadFromEnvironment' and inst.arg2 == 8 and inst.arg3 == 2:
        reg = inst.arg1
        # look at the next few instructions using reg
        sub = []
        for j in range(i+1, min(i+10, len(insts))):
            next_inst = insts[j]
            name = next_inst.inst.name
            args = [getattr(next_inst, 'arg' + str(n+1)) for n in range(4) if hasattr(next_inst, 'arg' + str(n+1))]
            desc = ''
            if 'String' in name and len(args) > 1:
                desc = f'"{reader.strings[args[1]]}"'
            elif 'ByIdShort' in name and len(args) > 3:
                desc = f'"{reader.strings[args[3]]}"'
            elif 'ById' in name and len(args) > 3:
                desc = f'"{reader.strings[args[3]]}"'
            sub.append(f'{next_inst.original_pos}: {name} {args} {desc}')
        print(f"LoadFromEnvironment [8, 2] into r{reg} at pos {inst.original_pos}:\n  " + "\n  ".join(sub[:5]))
