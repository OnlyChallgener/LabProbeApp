import sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
data = open(path, 'rb').read()
r = HBCReader(); r.read_whole_file(open(path, 'rb'))
targets = sys.argv[1:]
sids = {r.strings.index(t): t for t in targets if t in r.strings}
for fid, h in enumerate(r.function_headers):
    raw = data[h.offset:h.offset + h.bytecodeSizeInBytes]
    if not any(s.to_bytes(2, 'little') in raw for s in sids):
        continue
    insts = list(parse_hbc_bytecode(h, r))
    hit = False
    props = []
    for i in insts:
        n = 1
        while hasattr(i, f'arg{n}'):
            v = getattr(i, f'arg{n}')
            if n == 4 and 'ById' in i.inst.name and isinstance(v, int) and v < len(r.strings):
                props.append(r.strings[v])
                if v in sids: hit = True
            if 'String' in i.inst.name and n == 2 and isinstance(v, int) and v < len(r.strings):
                if v in sids: hit = True
                props.append('"' + r.strings[v][:40] + '"')
            n += 1
    if hit:
        print(f'== FID {fid}: ' + ', '.join(dict.fromkeys(props)))
