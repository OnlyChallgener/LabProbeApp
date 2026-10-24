import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
header = reader.function_headers[7220]
insts = list(parse_hbc_bytecode(header, reader))
screens = []
for i, inst in enumerate(insts):
    if inst.inst.name == 'PutNewOwnByIdShort' and inst.arg3 == 163: # "name"
        prev = insts[i-1]
        if prev.inst.name == 'LoadConstString':
            screens.append(reader.strings[prev.arg2])
print("Screens in FID 7220:", screens)
