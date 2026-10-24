from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
r = HBCReader(); r.read_whole_file(open(path, 'rb'))
h = r.function_headers[20694]
insts = list(parse_hbc_bytecode(h, r))
for idx, i in enumerate(insts):
    if 'Store' in i.inst.name and hasattr(i, 'arg2') and i.arg2 == 42:
        print('Store to slot 42 at', idx, hex(i.original_pos))
        for j in range(max(0, idx-10), idx+1):
            s = f'{insts[j].original_pos:#06x}: {insts[j].inst.name}'
            for n in range(1, 4):
                if hasattr(insts[j], f'arg{n}'):
                    v = getattr(insts[j], f'arg{n}')
                    s += f' {v}'
                    if isinstance(v, int) and v < len(r.strings):
                        s += f'("{r.strings[v]}")'
            print('  ', s)
