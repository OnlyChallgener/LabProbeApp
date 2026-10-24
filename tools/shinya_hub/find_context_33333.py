from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

header = reader.function_headers[33333]
instructions = list(parse_hbc_bytecode(header, reader))

# find instructions referencing '实时性能' or string 20287
for idx, inst in enumerate(instructions):
    found = False
    n = 1
    while hasattr(inst, f'arg{n}'):
        val = getattr(inst, f'arg{n}')
        if isinstance(val, int) and 0 <= val < len(reader.strings):
            s = reader.strings[val]
            if '实时性能' in s or 'getRssiLevel' in s or 'getSignalStrengthIcon' in s or 'dbm' in s.lower() or 'mbps' in s.lower():
                print(f'Match at idx {idx}: {inst.original_pos:#06x} {inst.inst.name} -> {s}')
                found = True
        n += 1
    if found:
        # print range around idx
        start_idx = max(0, idx - 20)
        end_idx = min(len(instructions), idx + 40)
        print(f'--- Context around {idx} ---')
        for j in range(start_idx, end_idx):
            it = instructions[j]
            desc = f'[{j:4d}] {it.original_pos:#06x}: {it.inst.name:20s}'
            m = 1
            while hasattr(it, f'arg{m}'):
                v = getattr(it, f'arg{m}')
                desc += f' {v}'
                if isinstance(v, int) and 0 <= v < len(reader.strings):
                    ss = reader.strings[v]
                    if len(ss) < 30:
                        desc += f'("{ss}")'
                m += 1
            print(desc)
        print('---------------------------')
