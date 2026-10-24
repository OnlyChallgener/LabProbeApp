import sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

path = r'test/_analysis/oct08-shinya-hub/index.android.bundle'
r = HBCReader()
r.read_whole_file(open(path, 'rb'))

def disasm(fid, start=0, limit=100):
    h = r.function_headers[fid]
    print(f"=== FID {fid} (size={h.bytecodeSizeInBytes}, params={h.paramCount}) ===")
    insts = list(parse_hbc_bytecode(h, r))
    for i in insts[start:start+limit]:
        args = [getattr(i, 'arg' + str(n+1)) for n in range(4) if hasattr(i, 'arg' + str(n+1))]
        s = ''
        if 'String' in i.inst.name and len(args) > 1 and isinstance(args[1], int) and args[1] < len(r.strings):
            s = f'"{r.strings[args[1]]}"'
        elif 'ById' in i.inst.name and len(args) > 3 and isinstance(args[3], int) and args[3] < len(r.strings):
            s = f'"{r.strings[args[3]]}"'
        print(f'{hex(i.original_pos)}: {i.inst.name} {args} {s}')

def search_env(fid, env_id, slot):
    h = r.function_headers[fid]
    insts = list(parse_hbc_bytecode(h, r))
    for i, inst in enumerate(insts):
        args = [getattr(inst, f'arg{n}') for n in range(1, 5) if hasattr(inst, f'arg{n}')]
        if 'Environment' in inst.inst.name and len(args) >= 3:
            if args[1] == env_id and args[2] == slot:
                print(f"{hex(inst.original_pos)} (inst {i}): {inst.inst.name} {args}")
            elif args[0] == env_id and args[1] == slot:
                print(f"{hex(inst.original_pos)} (inst {i}): {inst.inst.name} {args}")

def trace_reg(fid, reg):
    h = r.function_headers[fid]
    insts = list(parse_hbc_bytecode(h, r))
    for i, inst in enumerate(insts):
        args = [getattr(inst, f'arg{n}') for n in range(1, 5) if hasattr(inst, f'arg{n}')]
        if reg in args:
            print(f"{hex(inst.original_pos)} (inst {i}): {inst.inst.name} {args}")

def search_string(fid, string_name):
    h = r.function_headers[fid]
    insts = list(parse_hbc_bytecode(h, r))
    for i, inst in enumerate(insts):
        for n in range(1, 5):
            if hasattr(inst, f'arg{n}'):
                arg = getattr(inst, f'arg{n}')
                if isinstance(arg, int) and arg < len(r.strings) and r.strings[arg] == string_name:
                    print(f"{hex(inst.original_pos)} (inst {i}): {inst.inst.name} {[getattr(inst, f'arg{k}') for k in range(1, 5) if hasattr(inst, f'arg{k}')]}")

if __name__ == '__main__':
    if sys.argv[1] == 'search':
        search_env(int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]))
    elif sys.argv[1] == 'trace':
        trace_reg(int(sys.argv[2]), int(sys.argv[3]))
    elif sys.argv[1] == 'str':
        search_string(int(sys.argv[2]), sys.argv[3])
    else:
        for a in sys.argv[1:]:
            parts = a.split(':')
            fid = int(parts[0])
            start = int(parts[1]) if len(parts) > 1 else 0
            limit = int(parts[2]) if len(parts) > 2 else 100
            disasm(fid, start, limit)





