from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[0]

insts = list(parse_hbc_bytecode(header, reader))
for idx, i in enumerate(insts):
    if getattr(i, 'function_id', None) == 22183 or getattr(i, 'arg3', None) == 22183:
        # Instruction after it is LoadConstInt (moduleId) and then NewArrayWithBuffer (deps)
        print("22183 registration:", i)
        for j in insts[idx: idx + 4]:
            print(" ", j)
        break
