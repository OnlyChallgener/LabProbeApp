import sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

for fid in [33301, 33300, 33302, 33303, 33304, 32927]:
    if fid < len(reader.function_headers):
        header = reader.function_headers[fid]
        instructions = list(parse_hbc_bytecode(header, reader))
        print(f'=== FID {fid} (inst count: {len(instructions)}) ===')
        for idx, inst in enumerate(instructions):
            desc = f'{inst.original_pos:#06x}: {inst.inst.name}'
            n = 1
            while hasattr(inst, f'arg{n}'):
                val = getattr(inst, f'arg{n}')
                desc += f' {val}'
                if isinstance(val, int) and 0 <= val < len(reader.strings):
                    s = reader.strings[val]
                    if len(s) < 50:
                        desc += f'("{s}")'
                n += 1
            if any(s in desc for s in ['rssi', 'dbm', '弱', '优', '中', 'signal', 'Signal', 'Score', 'score', 'bad', 'Bad', 'rate', 'Rate', 'Mbps']):
                print(f'  [{idx}] {desc}')
