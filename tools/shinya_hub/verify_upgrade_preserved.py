"""Assert the original upgrade page and its per-channel red dot stay intact."""
import sys
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from patch_bundle import load

source = Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
patched = Path(sys.argv[1] if len(sys.argv) > 1 else
               'test/_h3b/1791544164/decoded/assets/index.android.bundle').read_bytes()
before, after = load(source), load(patched)
for fid in (32270, 32274, 32275, 32276, 32277, 32297, 32298, 24043, 24047, 24049,
            24051, 24053, 24054, 24070, 24075, 24077):
    h = before.function_headers[fid]
    assert source[h.offset:h.offset + h.bytecodeSizeInBytes] == \
        patched[h.offset:h.offset + h.bytecodeSizeInBytes], fid
assert before.strings[20086] == after.strings[20086]

# F24054 feeds isNeedUpgrade (the tool entry red dot). It must keep comparing
# each row's channel code with the project's upDateVersionCode, so a beta
# project never dots an official-channel update.
header = after.function_headers[24054]
steps = [(item.original_pos, item.inst.name)
         for item in parse_hbc_bytecode(header, after)]
assert [name for _, name in steps] == ['LoadParam', 'LoadConstNull', 'LoadConstUndefined',
    'JEqual', 'GetByIdShort', 'AddEmptyString', 'GetEnvironment', 'LoadFromEnvironment',
    'GetById', 'AddEmptyString', 'StrictEq', 'Ret'], steps
print('Original upgrade renderer, manager, per-channel filters and red dot intact.')
