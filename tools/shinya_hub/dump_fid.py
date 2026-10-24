import sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
r = HBCReader(); r.read_whole_file(open(path, 'rb'))

def fmt(i):
    s = f'{i.original_pos:#06x}: {i.inst.name:22s}'
    n = 1
    while hasattr(i, f'arg{n}'):
        v = getattr(i, f'arg{n}')
        s += f' {v}'
        if isinstance(v, int) and (('ById' in i.inst.name and n >= 3) or ('String' in i.inst.name and n == 2)) and v < len(r.strings):
            t = r.strings[v]
            s += f'("{t[:30]}")' if t.isascii() else f'("{t[:12]}"/{[hex(ord(c)) for c in t[:6]]})'
        n += 1
    return s

def dump(fid, closures=False):
    h = r.function_headers[fid]
    insts = list(parse_hbc_bytecode(h, r))
    print(f'=== FID {fid} ({len(insts)}) ===')
    kids = []
    for i in insts:
        print('  ' + fmt(i))
        if 'Closure' in i.inst.name:
            kids.append(i.arg3)
    return kids

if __name__ == '__main__':
    for a in sys.argv[1:]:
        dump(int(a))
