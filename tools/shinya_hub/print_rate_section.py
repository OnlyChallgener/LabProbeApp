from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

reader = HBCReader()
reader.read_whole_file(open(r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle', 'rb'))

header = reader.function_headers[33333]
instructions = list(parse_hbc_bytecode(header, reader))

for j in range(2365, 2445):
    it = instructions[j]
    desc = f'[{j:4d}] {it.original_pos:#06x}: {it.inst.name:20s}'
    m = 1
    while hasattr(it, f'arg{m}'):
        v = getattr(it, f'arg{m}')
        desc += f' {v}'
        if isinstance(v, int) and 0 <= v < len(reader.strings):
            ss = reader.strings[v]
            if len(ss) < 30:
                desc += f'("{ss}")'
        m += 1
    print(desc)
