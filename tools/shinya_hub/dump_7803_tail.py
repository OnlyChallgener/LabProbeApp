from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[7803]

insts = list(parse_hbc_bytecode(header, reader))
for i in insts[40:]:
    s_info = ""
    for arg in ['arg1', 'arg2', 'arg3', 'arg4', 'string_id']:
        val = getattr(i, arg, None)
        if isinstance(val, int) and val < len(reader.strings):
            s_info += f" [{val}:{reader.strings[val]}]"
    print(f"  {hex(i.original_pos)}: {i.inst.name} {s_info}")
