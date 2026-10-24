from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

reader = HBCReader()
reader.read_whole_file(open(r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle', 'rb'))

def dump(fid):
    h = reader.function_headers[fid]
    instructions = list(parse_hbc_bytecode(h, reader))
    print(f'=== FID {fid} ({len(instructions)} insts) ===')
    for idx, inst in enumerate(instructions):
        desc = f'[{idx:3d}] {inst.original_pos:#06x}: {inst.inst.name:20s}'
        n = 1
        while hasattr(inst, f'arg{n}'):
            val = getattr(inst, f'arg{n}')
            desc += f' {val}'
            if isinstance(val, int) and 0 <= val < len(reader.strings):
                s = reader.strings[val]
                if len(s) < 40:
                    desc += f'("{s}")'
            n += 1
        print(f'  {desc}')

dump(22143)
print("\n--- Also dumping FID 22136 (getSignalStrengthIcon) ---")
dump(22136)
