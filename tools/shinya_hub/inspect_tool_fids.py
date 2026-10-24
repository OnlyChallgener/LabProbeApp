import sys
sys.stdout.reconfigure(encoding='utf-8')
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
with open(bundle_path, 'rb') as f:
    bundle_data = f.read()

reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

def inspect_fid(fid):
    header = reader.function_headers[fid]
    raw = bundle_data[header.offset : header.offset + header.bytecodeSizeInBytes]
    # Find all 16-bit string IDs referenced in raw
    found_sids = []
    for sid in range(len(reader.strings)):
        sid_le16 = sid.to_bytes(2, 'little')
        if sid_le16 in raw:
            s = reader.strings[sid]
            if len(s) > 1 and not s.isdigit():
                found_sids.append(s)
    print(f"\n=== FID {fid} Strings ({len(found_sids)}) ===")
    for s in found_sids[:25]:
        print("  ", repr(s))

for fid in [25223, 27930, 24000, 25415, 33554]:
    inspect_fid(fid)
