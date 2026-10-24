import sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
with open(bundle_path, 'rb') as f:
    bundle_data = f.read()

reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

target_strs = ['实时性能', 'getRssiLevel', 'getSignalStrengthIcon']
target_sids = {}
for sid, s in enumerate(reader.strings):
    for t in target_strs:
        if t in s:
            target_sids[sid] = (t, s)
            print(f'String {sid}: {s}')

for fid, header in enumerate(reader.function_headers):
    start = header.offset
    raw = bundle_data[start : start + header.bytecodeSizeInBytes]
    for sid, (t, s) in target_sids.items():
        sid_le16 = sid.to_bytes(2, 'little')
        if sid_le16 in raw:
            print(f'FID {fid} references string {sid} ({t})')
