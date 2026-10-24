from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

header = reader.function_headers[33333]
instructions = list(parse_hbc_bytecode(header, reader))

print("Strings accessed via GetById or LoadConstString in FID 33333:")
accessed = set()
for inst in instructions:
    n = 1
    while hasattr(inst, f'arg{n}'):
        v = getattr(inst, f'arg{n}')
        if isinstance(v, int) and 0 <= v < len(reader.strings):
            s = reader.strings[v]
            if any(k in s for k in ['/','get','query','Sta','sta','Detail','detail']):
                accessed.add(s)
        n += 1

for s in sorted(accessed):
    print(s)
