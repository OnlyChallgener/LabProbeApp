import sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
data = open(path, 'rb').read()
r = HBCReader(); r.read_whole_file(open(path, 'rb'))
for name in sys.argv[1:]:
    sid = r.strings.index(name)
    for fid, h in enumerate(r.function_headers):
        raw = data[h.offset:h.offset + h.bytecodeSizeInBytes]
        if sid.to_bytes(2, 'little') not in raw:
            continue
        for i in parse_hbc_bytecode(h, r):
            if i.inst.name.startswith('PutNewOwn') or i.inst.name.startswith('PutById'):
                args = [getattr(i, f'arg{n}') for n in range(1, 5) if hasattr(i, f'arg{n}')]
                if sid in args[2:]:
                    print(name, 'written in FID', fid, hex(i.original_pos), i.inst.name)
