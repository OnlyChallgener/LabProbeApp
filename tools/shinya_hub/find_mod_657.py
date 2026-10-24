from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[0]

insts = list(parse_hbc_bytecode(header, reader))
for idx, i in enumerate(insts):
    if i.inst.name == 'LoadConstInt' and getattr(i, 'arg2', None) == 657:
        # print previous and next instructions
        for j in insts[max(0, idx - 2): idx + 4]:
            print(f"  {hex(j.original_pos)}: {j}")
        break
