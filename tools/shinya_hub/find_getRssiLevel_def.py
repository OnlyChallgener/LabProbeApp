from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

sid = reader.strings.index('getRssiLevel')
print('getRssiLevel string ID:', sid)

with open(bundle_path, 'rb') as f:
    bundle_data = f.read()

for fid, h in enumerate(reader.function_headers):
    raw = bundle_data[h.offset : h.offset + h.bytecodeSizeInBytes]
    if sid.to_bytes(2, 'little') in raw:
        instructions = list(parse_hbc_bytecode(h, reader))
        # check if it puts getRssiLevel or defines it
        for inst in instructions:
            if inst.inst.name in ['PutNewOwnById', 'PutById', 'PutByIdShort']:
                for a in [getattr(inst, f'arg{i}') for i in range(1, 6) if hasattr(inst, f'arg{i}')]:
                    if a == sid:
                        print(f'FID {fid} DEFINES getRssiLevel at {inst.original_pos:#06x}')
