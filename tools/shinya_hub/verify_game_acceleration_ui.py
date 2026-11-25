"""Verify the game-screen switch reads the projected firmware service state."""
import argparse
from pathlib import Path
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from patch_bundle import load
from verify_station_lookup import check_frame
from verify_terminal_ipv6_patch import check_read_caches

parser = argparse.ArgumentParser()
parser.add_argument('--bundle', type=Path, required=True)
args = parser.parse_args()
r = load(args.bundle.read_bytes())
fid = 25772
check_frame(r, fid)
check_read_caches(r, fid)
instructions = {i.original_pos: i for i in parse_hbc_bytecode(r.function_headers[fid], r)}
prop, literal, compare = (instructions[p] for p in (0x40a, 0x418, 0x41c))
assert prop.inst.name == 'GetById' and r.strings[prop.arg4] == 'enabled'
assert literal.inst.name == 'LoadConstString' and r.strings[literal.arg2] == 'true'
assert compare.inst.name == 'StrictEq'
print('PASS: game-screen switch reads global firmware-enabled state; frame and property caches valid')
