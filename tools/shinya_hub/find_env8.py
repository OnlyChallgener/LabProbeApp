from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

reader = HBCReader()
reader.read_whole_file(open(r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle', 'rb'))

header = reader.function_headers[33333]
instructions = list(parse_hbc_bytecode(header, reader))

for idx, inst in enumerate(instructions):
    if 'Environment' in inst.inst.name:
        n = 1
        desc = f'[{idx:4d}] {inst.original_pos:#06x}: {inst.inst.name:20s}'
        while hasattr(inst, f'arg{n}'):
            val = getattr(inst, f'arg{n}')
            desc += f' {val}'
            n += 1
        if '8 2' in desc or '8 1' in desc or '8 0' in desc or 'StoreToEnvironment' in desc:
            print(desc)
