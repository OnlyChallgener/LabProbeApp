"""插一行「MACC 账号」到我的页菜单，点击打开原生 MACC 面板。

全部为原地等长修改，不重排函数体：
- 23571（我的页菜单组件）：条件块改为无条件执行（关于行不再显示新版本红点），
  块内空位重写为 MACC 行构建，items[1] 插入并把其余行索引后移一位。
- 23573（行 onPress 闭包）：整体替换为新函数体，标题匹配时直接调原生桥。
字符串槽 21058（原"获取验证码失败"，仅在已清空的退役函数里引用）改写为「MACC 账号」，
通过 STRINGS 导出给 patch_bundle 的 labels 机制处理编码。
"""
import struct

from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
from hbc_grow import insert_bodies
from patch_terminal_ipv6 import Code
from patch_measured_status import rewrite

MACC_SID = 21058
METHOD_SID = 24357  # 原'getMsgCodeWithSign'（标识符类、仅被已清空函数引用），改写为桥接方法名
ACCOUNT_SVG_SID = 1551
ACCOUNT_SVG = '<svg viewBox="0 0 24 24"><path d="M12 3a4 4 0 100 8a4 4 0 000-8M4 22v-3a8 8 0 0116 0v3" fill="none" stroke="#444"/></svg>'
assert len(ACCOUNT_SVG)<255
STRINGS = {MACC_SID: 'MACC 账号', METHOD_SID: 'openMaccAccount', ACCOUNT_SVG_SID: ACCOUNT_SVG}

MENU_FID = 23571
PRESS_FID = 23573

# 23571 条件块空位（原新版本红点构建区）与数组索引位置
BLOCK_START, BLOCK_END = 0x101, 0x1AC
JMPFALSE_POS = 0xFB
ARRAY_SIZE_POS = 0x86
SHIFT_PUTS = ((0xB5, 1), (0xDE, 2))  # PutOwnByIndex 起始偏移与现索引（0x1D2 单独处理）


def additions(reader, data, changed, changes):
    # ---- 我的页菜单：插入 MACC 行 ----
    header = reader.function_headers[MENU_FID]
    body = bytearray(data[header.offset:header.offset + header.bytecodeSizeInBytes])

    assert body[JMPFALSE_POS] == ops.JmpFalseLong.opcode
    assert struct.unpack_from('<i', body, JMPFALSE_POS + 1)[0] == BLOCK_END - JMPFALSE_POS
    # A zero relative offset jumps to this instruction forever when false.
    # Always enter the replacement block, skipping the one padding byte.
    jump = Code(reader)
    jump.emit('JmpLong', BLOCK_START - JMPFALSE_POS)
    body[JMPFALSE_POS:BLOCK_START] = jump.raw + bytes([ops.Unreachable.opcode])

    assert body[ARRAY_SIZE_POS - 2] == ops.NewArray.opcode
    # 主补丁此前会把菜单行裁到 2 行（满意度/意见反馈移除）；原始包是 4 行。
    size = struct.unpack_from('<H', body, ARRAY_SIZE_POS)[0]
    assert size in (2, 4), size
    struct.pack_into('<H', body, ARRAY_SIZE_POS, size + 1)

    builder = Code(reader)
    builder.emit('LoadConstNull', 5)                       # 关于行的 value 置空
    builder.emit('NewObjectWithBufferLong', 18, 3, 2, 36649, 73436)  # 行模板（键/值缓冲同其他行）
    builder.emit('LoadConstString', 19, MACC_SID)
    builder.emit('PutNewOwnByIdShort', 18, 19, 240)        # item.title = 'MACC 账号'
    builder.emit('LoadConstString', 19, reader.strings.index('account_share'))
    builder.emit('LoadConstString', 20, reader.strings.index('icon'))
    builder.emit('PutByVal', 18, 20, 19)                  # existing outline account icon
    builder.emit('LoadConstString', 19, METHOD_SID)
    builder.emit('LoadConstString', 20, 208)
    builder.emit('PutByVal', 18, 20, 19)                  # unique React row key/type
    builder.emit('PutOwnByIndex', 2, 18, 1)                # items[1] = MACC 行
    builder.emit('JmpLong', BLOCK_END - BLOCK_START - len(builder.raw))
    block = bytes(builder.raw)
    region = BLOCK_END - BLOCK_START
    assert len(block) <= region
    body[BLOCK_START:BLOCK_END] = block + bytes([ops.Unreachable.opcode]) * (region - len(block))

    for pos, current in SHIFT_PUTS:
        if body[pos] == ops.LoadConstUndefined.opcode:
            continue  # 主补丁已把该行改成空操作
        assert body[pos] == ops.PutOwnByIndex.opcode, hex(pos)
        assert body[pos + 3] == current, (hex(pos), body[pos + 3])
        body[pos + 3] = current + 1
    about = 0x1D2
    assert body[about] == ops.PutOwnByIndex.opcode
    assert body[about + 3] in (1, 3), body[about + 3]
    body[about + 3] += 1

    data[header.offset:header.offset + header.bytecodeSizeInBytes] = body
    changed.add(MENU_FID)
    changes.append({'function': MENU_FID, 'action': 'insert MACC row after share management'})

    # ---- 行点击：map environment slot0=item; parent slot0=dispatcher ----
    press = reader.function_headers[PRESS_FID]
    code = Code(reader)
    code.emit('LoadConstUndefined', 3)
    code.emit('GetEnvironment', 1, 0)
    code.emit('LoadFromEnvironment', 4, 1, 0)      # r4 = item
    exits = [code.branch('JmpFalseLong', 4)]
    code.emit('GetByIdShort', 5, 4, 1, 208)        # stable item.type
    code.emit('LoadConstString', 6, METHOD_SID)
    fallback = code.branch('JStrictNotEqualLong', 5, 6)
    code.emit('GetEnvironment', 7, 2)             # original menu module
    code.emit('LoadFromEnvironment', 8, 7, 3)     # React Native dependency
    code.emit('GetById', 8, 8, 2, reader.strings.index('NativeModules'))
    exits.append(code.branch('JmpFalseLong', 8))
    code.emit('GetById', 8, 8, 3, reader.strings.index('sta'))
    exits.append(code.branch('JmpFalseLong', 8))
    code.emit('GetById', 9, 8, 4, METHOD_SID)
    code.emit('TypeOf', 6, 9)
    code.emit('LoadConstString', 7, reader.strings.index('function'))
    exits.append(code.branch('JStrictNotEqualLong', 6, 7))
    code.emit('Call1', 0, 9, 8)
    exits.append(code.branch('JmpLong'))
    code.finish(fallback)
    code.emit('GetEnvironment', 1, 1)
    code.emit('LoadFromEnvironment', 2, 1, 0)
    exits.append(code.branch('JmpFalseLong', 2))
    code.emit('Call2', 0, 2, 3, 5)                 # original dispatcher(item.type)
    for exit_branch in exits:
        code.finish(exit_branch)
    code.emit('LoadConstUndefined', 0)
    code.emit('Ret', 0)

    # The original account_share switch arm was repurposed by the STUN
    # icon patch. Add a separate arm, preserving that live STUN entry.
    icon = Code(reader)
    icon.string(1, 'account_share')
    not_account = icon.branch('JStrictNotEqualLong', 1, 2)
    icon.emit('LoadFromEnvironment', 2, 0, 0)
    icon.emit('LoadFromEnvironment', 3, 0, 1)
    icon.number(1, 126); icon.emit('GetByVal', 1, 3, 1)
    icon.emit('Call2', 1, 2, 4, 1); icon.get(5, 1, 'jsx')
    icon.emit('LoadFromEnvironment', 1, 0, 22); icon.get(3, 1, 'default')
    icon.emit('GetGlobalObject', 1); icon.get(7, 1, 'Object'); icon.get(2, 7, 'assign')
    icon.emit('NewObject', 1); icon.emit('Call3', 2, 2, 7, 1, 6)
    icon.string(1, 'L2'); icon.emit('Call4', 1, 5, 4, 3, 2, 1); icon.emit('Ret', 1)
    icon.finish(not_account)
    icon_body, _, _ = rewrite(reader, 17938, {0xf1: bytes(icon.raw)}, {})
    result, placed = insert_bodies(reader, data, [code.raw, icon_body])
    small = reader.get_small_func_header_reader()
    import ctypes
    h = small.from_buffer(result, reader.functions_offset + PRESS_FID * ctypes.sizeof(small))
    assert not h.overflowed
    h.offset, h.bytecodeSizeInBytes = placed[0]
    h.highestReadCacheIndex = max(h.highestReadCacheIndex, 4)
    h.frameSize = max(h.frameSize, 18)  # explicit calls: registers + Hermes call slots
    assert h.frameSize >= 10 and h.paramCount == 1, (h.frameSize, h.paramCount)
    del h
    h = small.from_buffer(result, reader.functions_offset + 17938 * ctypes.sizeof(small))
    if h.overflowed:
        h = reader.get_large_func_header_reader().from_buffer(result, (h.infoOffset << 16) | h.offset)
    h.offset, h.bytecodeSizeInBytes = placed[1]
    del h
    changed.add(17938)
    changed.add(PRESS_FID)
    changes.append({'function': PRESS_FID, 'action': 'MACC row press opens the native panel'})
    return result
