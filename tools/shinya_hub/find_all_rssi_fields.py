from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

fields = ['instantRssiScore', 'rssiScore', 'isBadSignal', 'badSignalDuration', 'lastBadSignalTime', 'rssiAvg', 'rssi']
with open(bundle_path, 'rb') as f:
    bundle_data = f.read()

for field in fields:
    if field in reader.strings:
        sid = reader.strings.index(field)
        sid_le16 = sid.to_bytes(2, 'little')
        matching_fids = []
        for fid, h in enumerate(reader.function_headers):
            raw = bundle_data[h.offset : h.offset + h.bytecodeSizeInBytes]
            if sid_le16 in raw:
                # check if accessed as property
                instructions = list(parse_hbc_bytecode(h, reader))
                for inst in instructions:
                    for a in [getattr(inst, f'arg{i}') for i in range(1, 6) if hasattr(inst, f'arg{i}')]:
                        if a == sid:
                            matching_fids.append((fid, inst.original_pos, inst.inst.name))
        print(f"=== Field '{field}' (sid {sid}): found in {len(matching_fids)} instructions ===")
        for fid, pos, name in matching_fids[:10]:
            print(f"  FID {fid} at {pos:#06x} ({name})")
