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
    if i.inst.name == 'LoadConstInt' and getattr(i, 'arg2', None) == 714:
        buf_inst = insts[idx + 1]
        print(f"714 array inst: {buf_inst}")
        break
