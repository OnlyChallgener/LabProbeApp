from pathlib import Path
import hashlib
import json
import struct
from io import BytesIO

from patch_bundle import load, Reader, SOURCE_SHA
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops

source = Path("D:/Github/LabProbeApp/test/_analysis/oct08-shinya-hub/index.android.bundle")
output = Path("D:/Github/LabProbeApp/tools/shinya_hub/test_output_bundle.bundle")

original = source.read_bytes()
assert hashlib.sha256(original).hexdigest() == SOURCE_SHA
reader = load(original)
assert reader.header.version == 90
data = bytearray(original)
changes = []
changed_functions = set()

def instruction(fid, pos, name, expected, replacements):
    header = reader.function_headers[fid]
    inst = next(i for i in parse_hbc_bytecode(header, reader) if i.original_pos == pos)
    assert inst.inst.name == name, (fid, pos, inst)
    assert [getattr(inst, "arg" + str(n + 1)) for n in range(len(expected))] == expected
    start = header.offset + pos
    for byte_index, value in replacements.items():
        data[start + byte_index] = value
    changed_functions.add(fid)
    changes.append({"function": fid, "offset": pos, "action": name})

def replace_function(fid, code, reason):
    header = reader.function_headers[fid]
    assert len(code) <= header.bytecodeSizeInBytes
    assert not any(i.inst.name == "StartGenerator" for i in parse_hbc_bytecode(header, reader))
    data[header.offset:header.offset + header.bytecodeSizeInBytes] = code + bytes(
        [ops.Unreachable.opcode] * (header.bytecodeSizeInBytes - len(code)))
    changed_functions.add(fid)
    changes.append({"function": fid, "action": reason})

# 1. Login page: Pure official UI layout
# Password mode by default
instruction(24731, 0xa4, "LoadConstFalse", [21], {0: ops.LoadConstTrue.opcode})
# Boolean false for loading state (fixes Android 13 crash)
instruction(24731, 0x9f, "GetByIdShort", [5, 19, 1, 248], {1: 24})
instruction(24731, 0xa6, "Call2", [8, 5, 20, 21], {2: 24})
instruction(24731, 0xe3, "GetByIdShort", [8, 19, 1, 248],
            dict(enumerate([ops.LoadConstFalse.opcode, 21, ops.Mov.opcode, 8, 24])))
# Hide dead third-party login controls (SMS and WeChat login buttons) & consent overlay
for pos, expected in [(0x6b6, [13, 16, 4]), (0x7c6, [13, 6, 6]), (0xaa2, [13, 9, 7])]:
    instruction(24694, pos, "PutOwnByIndex", expected, {2: 3})
instruction(24694, 0xaf5, "PutOwnByIndex", [4, 0, 2], {2: 3})  # consent overlay

# Retain tools tab, remove dead banners/promotions
instruction(23554, 0x67f, "PutOwnByIndex", [4, 0, 1], {2: 3})  # assistant/tutorial promotion
instruction(23912, 0x2d0, "Call3", [20, 21, 3, 20, 18],
            dict(enumerate([ops.LoadConstNull.opcode, 20,
                            ops.LoadConstNull.opcode, 18,
                            ops.LoadConstNull.opcode, 20])))  # tools assistant banner
instruction(27915, 0x439, "PutOwnByIndex", [4, 8, 1], {2: 3})  # password reset button
instruction(27915, 0xd3, "NewArray", [7, 4], {2: 2})
for pos in (0x153, 0x193):
    instruction(27915, pos, "PutOwnByIndex", [7, 17, 2 if pos == 0x153 else 3],
                dict(enumerate([ops.LoadConstUndefined.opcode, 17,
                                ops.LoadConstUndefined.opcode, 17])))
instruction(27915, 0x56d, "PutOwnByIndex", [4, 0, 4], {2: 3})  # delete official account

# Tools menu cleanups (remove defunct questionnaire menu)
instruction(24024, 0x4f, "NewArray", [0, 5], {2: 3})
for pos, value, index in [(0x124, 6, 3), (0x157, 1, 4)]:
    instruction(24024, pos, "PutOwnByIndex", [0, value, index],
                dict(enumerate([ops.LoadConstUndefined.opcode, value,
                                ops.LoadConstUndefined.opcode, value])))
instruction(24019, 0xda, "NewArray", [0, 6], {2: 5})
instruction(24019, 0x2a6, "PutOwnByIndex", [0, 2, 3],
            dict(enumerate([ops.LoadConstUndefined.opcode, 2,
                            ops.LoadConstUndefined.opcode, 2])))
instruction(24019, 0x2e0, "PutOwnByIndex", [0, 4, 4], {3: 3})
instruction(24019, 0x32b, "PutOwnByIndex", [0, 1, 5], {3: 4})
instruction(24028, 0x77, "NewArray", [0, 6], {2: 5})
instruction(24028, 0x250, "PutOwnByIndex", [0, 2, 4],
            dict(enumerate([ops.LoadConstUndefined.opcode, 2,
                            ops.LoadConstUndefined.opcode, 2])))
instruction(24028, 0x2ca, "PutOwnByIndex", [0, 1, 5], {3: 4})

# Stability radar / curve / sta item guards
radar = bytearray([ops.GetEnvironment.opcode,0,1,
    ops.LoadFromEnvironment.opcode,0,0,0,ops.JmpFalse.opcode,0,0,
    ops.GetByIdShort.opcode,0,0,1,110,ops.JmpTrue.opcode,0,0])
empty=len(radar)
radar += bytes([ops.NewObject.opcode,0,ops.NewObject.opcode,1,
                ops.NewArray.opcode,2,0,0,ops.PutNewOwnById.opcode,1,2])+struct.pack('<H',24665)
radar += bytes([ops.PutNewOwnByIdShort.opcode,0,1,110,ops.NewObject.opcode,1,
                ops.PutNewOwnById.opcode,1,2])+struct.pack('<H',51721)
radar += bytes([ops.PutNewOwnById.opcode,0,1])+struct.pack('<H',35054)
returned=len(radar)
radar += bytes([ops.Ret.opcode,0])
radar[8]=empty-7;radar[16]=returned-15
replace_function(33205,bytes(radar),'render only observed HUB stability dimensions')

header=reader.function_headers[33165]
data[header.offset+0x248:header.offset+0x24f]=bytes([
    ops.GetByIdShort.opcode,14,29,1,110,ops.LoadConstUndefined.opcode,1])
changed_functions.add(33165)

total=bytes([ops.GetEnvironment.opcode,0,0,
             ops.LoadFromEnvironment.opcode,2,0,1,
             ops.LoadFromEnvironment.opcode,3,0,0,
             ops.LoadConstInt.opcode,4])+struct.pack('<i',1048576)
total+=bytes([ops.Div.opcode,2,2,4,ops.Div.opcode,3,3,4,
              ops.LoadConstUInt8.opcode,5,2,
              ops.GetById.opcode,6,2,1])+struct.pack('<H',29944)
total+=bytes([ops.Call2.opcode,2,6,2,5,ops.GetById.opcode,6,3,1])+struct.pack('<H',29944)
total+=bytes([ops.Call2.opcode,3,6,3,5,ops.NewObject.opcode,0,
              ops.PutNewOwnById.opcode,0,2])+struct.pack('<H',22139)
total+=bytes([ops.PutNewOwnById.opcode,0,3])+struct.pack('<H',23099)+bytes([ops.Ret.opcode,0])
replace_function(33135,total,'show measured daily totals in MB')
# Keep original unit string
instruction(33130,0x6bf,'LoadConstString',[46,724],{2:9021&255,3:9021>>8})

# StaItem bandOccupy guard
header = reader.function_headers[32927]
metric_guard = bytes([ops.GetById.opcode, 17, 14, 37]) + struct.pack('<H',44324)
metric_guard += bytes([ops.TypeOf.opcode, 35, 17, ops.LoadConstString.opcode, 28])
metric_guard += struct.pack('<H', reader.strings.index('number'))
metric_guard += bytes([ops.StrictEq.opcode, 17, 35, 28,
                      ops.LoadConstUndefined.opcode, 35, ops.Mov.opcode, 35, 35])
assert len(metric_guard) == 0x2e4 - 0x2ce
data[header.offset+0x2ce:header.offset+0x2e4] = metric_guard
changed_functions.add(32927)

return_null = bytes([ops.LoadConstNull.opcode, 0, ops.Ret.opcode, 0])
return_undefined = bytes([ops.LoadConstUndefined.opcode, 0, ops.Ret.opcode, 0])
for fid in (23246, 24155, 23365, 23571):
    replace_function(fid, return_null, "remove promotional banner")
for fid in (23043, 23048):
    replace_function(fid, return_undefined, "remove official questionnaire action")
replace_function(24006, bytes([ops.NewArray.opcode, 0, 0, 0, ops.Ret.opcode, 0]),
                 "remove tool questionnaire menu")
for fid in (24171,24172):
    replace_function(fid, bytes([ops.LoadConstFalse.opcode,0,ops.Ret.opcode,0]),
                     'remove cloud coverage and house simulation cards')

# Generator contract
header = reader.function_headers[23042]
assert next(iter(parse_hbc_bytecode(header, reader))).inst.name == "StartGenerator"
generator_noop = bytes([ops.StartGenerator.opcode, ops.ResumeGenerator.opcode, 0, 1,
                        ops.CompleteGenerator.opcode, ops.LoadConstUndefined.opcode, 0,
                        ops.Ret.opcode, 0])
data[header.offset:header.offset + header.bytecodeSizeInBytes] = generator_noop + bytes(
    [ops.Unreachable.opcode] * (header.bytecodeSizeInBytes - len(generator_noop)))
changed_functions.add(23042)

replace_function(23578, bytes([ops.NewArray.opcode, 0, 0, 0, ops.Ret.opcode, 0]), "no official app upgrade query")
replace_function(23580, return_undefined, "no official app upgrade action")
replace_function(23018, bytes([ops.NewArray.opcode, 0, 0, 0, ops.Ret.opcode, 0]), "no retired tutorial request")
replace_function(24124, return_undefined, "remove official child privacy link")

instruction(23339, 0xaf, "StoreNPToEnvironment", [0, 7, 4],
            dict(enumerate([ops.LoadConstFalse.opcode, 2,
                            ops.LoadConstUndefined.opcode, 4])))
completion = reader.function_headers[21973]
replace_function(21973, bytes([ops.LoadConstUndefined.opcode, 0]) +
                 original[completion.offset + 0xd3:completion.offset + completion.bytecodeSizeInBytes],
                 "finish project loading without cloud child cleanup")

# 2. String mapping:
# STRICT: All official strings remain 100% UNTOUCHED!
# Only retired URLs and empty banners freed.
# Add custom brands & NAS:
# 23531 (bilian) -> ugreen
# 33269 (whaley) -> fnos
# 20522 (必联) -> 绿联
# 20521 (微鲸) -> 飞牛
# 38972 (vr) -> nas
# 19225 (VR设备) -> NAS
labels = {
    23531: "ugreen",
    33269: "fnos",
    20522: "绿联",
    20521: "飞牛",
    38972: "nas",
    19225: "NAS",
    19052: "",
    19440: ""
}
labels.update({sid: '' for sid, s in enumerate(reader.strings) if s == '由小星助手V1.0提供服务'})

retired = {i for i, value in enumerate(reader.strings) if value.startswith(
    ("https://62ec64a915db5157d7b078ac555f0119@", "https://support.qq.com/product/318572/"))}
changed_strings = set(labels) | retired

used = bytearray(reader.header.stringStorageSize)
ranges = {}
for sid, entry in enumerate(reader.small_string_table):
    info = reader.overflow_string_table[entry.offset] if entry.length == 255 else entry
    length = info.length * (2 if entry.isUTF16 else 1)
    ranges[sid] = (info.offset, length)
    if sid not in changed_strings:
        used[info.offset:info.offset + length] = b"\x01" * length

free = bytearray(reader.header.stringStorageSize)
for sid in changed_strings:
    start, length = ranges[sid]
    for j in range(start, start + length):
        if not used[j]:
            free[j] = 1

def store_string(sid, value):
    utf16 = any(ord(c) > 127 for c in value)
    encoded = value.encode("utf-16-le" if utf16 else "ascii")
    length = len(encoded) // (2 if utf16 else 1)
    assert length < 255
    start = 0
    if encoded:
        count = 0
        for j, available in enumerate(free):
            count = count + 1 if available else 0
            if count >= len(encoded):
                start = j - len(encoded) + 1
                break
        else:
            raise ValueError(f"No exclusive string storage available for {repr(value)}")
        free[start:start + len(encoded)] = b"\x00" * len(encoded)
        data[reader.storage_offset + start:reader.storage_offset + start + len(encoded)] = encoded
    struct.pack_into("<I", data, reader.table_offset + 4 * sid, (length << 24) | (start << 1) | int(utf16))

for sid in retired:
    store_string(sid, "")
for sid, label in labels.items():
    store_string(sid, label)

data[-20:] = hashlib.sha1(data[:-20]).digest()
result = load(bytes(data))

for sid, value in enumerate(reader.strings):
    expected = labels.get(sid, "" if sid in retired else value)
    assert result.strings[sid] == expected, (sid, result.strings[sid], expected)

for fid, header in enumerate(reader.function_headers):
    if fid not in changed_functions:
        assert data[header.offset:header.offset + header.bytecodeSizeInBytes] == original[header.offset:header.offset + header.bytecodeSizeInBytes]
    else:
        list(parse_hbc_bytecode(result.function_headers[fid], result))

output.write_bytes(data)
print("SUCCESS: Bundle verified! 100% official UI preserved. NAS & brands mapped cleanly.")
