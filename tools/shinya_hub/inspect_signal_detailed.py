from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

header = reader.function_headers[33301]
instructions = list(parse_hbc_bytecode(header, reader))
for idx in range(70, min(180, len(instructions))):
    inst = instructions[idx]
    desc = f'[{idx:3d}] {inst.original_pos:#06x}: {inst.inst.name:20s}'
    n = 1
    while hasattr(inst, f'arg{n}'):
        val = getattr(inst, f'arg{n}')
        desc += f' {val}'
        if isinstance(val, int) and 0 <= val < len(reader.strings):
            s = reader.strings[val]
            if len(s) < 40 and not s.isascii():
                desc += f'("{s}")'
            elif len(s) < 30:
                desc += f'("{s}")'
        n += 1
    print(desc)
