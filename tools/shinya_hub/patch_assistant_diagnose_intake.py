"""Normalize diagnose messageDetail at the diagnose_loading intake (F35735).

The official cloud sends `messageDetail` as a JSON string of the detail
OBJECT (overallScore/diagnosisTime/alias/apModel/steps[...]); the retained
card row pipeline consumes the rows ARRAY string. This patch normalizes
r11 (the raw messageDetail the wrapper forwards) in place after it is
loaded: array strings pass through byte-identical, object strings become
their rows JSON. Both contract shapes then render the same card.

The raw messageDetail stays untouched on the store path (F35732 keeps the
original field) so the official diagnoseResult fields keep reaching the
result page (F33242) exactly as the official reader expects.

Anchoring is content-based: the insertion point is the instruction that
loads responseMessageType into r9 (immediately after the messageDetail
load into r11). No original instruction is replaced.
"""
import ctypes

from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code

FID = 35735


class C(Code):
    def __init__(self, reader, cache=0):
        super().__init__(reader)
        self.cache = cache

    def get(self, dst, obj, text):
        self.cache += 1
        self.emit('GetById', dst, obj, self.cache, self.sid(text))


def find_anchor(reader):
    """Return the offset of the r9 load that follows the messageDetail load.

    Matched by content so earlier body rewrites cannot silently shift us;
    the failure message carries every load in the body for diagnosis.
    """
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    loads = []
    seen_detail = False
    for ins in parse_hbc_bytecode(reader.function_headers[FID], reader):
        if ins.inst.name != 'LoadFromEnvironment':
            continue
        args = [getattr(ins, 'arg' + str(n)) for n in (1, 2, 3)]
        loads.append((ins.original_pos, args))
        if seen_detail and args and args[0] == 9:
            return ins.original_pos
        if args == [11, 0, 3]:
            seen_detail = True
    for pos, args in loads:
        if args and args[0] == 9:
            return pos
    raise AssertionError('diagnose intake anchor not found; loads=' + repr(loads))


def additions(reader, data, changed, changes):
    c = C(reader, reader.function_headers[FID].highestReadCacheIndex + 1)
    # r11 already holds the raw messageDetail here; r17 keeps it for
    # passthrough while r11 becomes the normalized rows JSON string.
    c.emit('Mov', 17, 11)
    c.emit('LoadConstUndefined', 18)
    c.emit('TypeOf', 13, 11)
    c.string(14, 'string')
    not_string = c.branch('JStrictNotEqualLong', 13, 14)
    # parsed = JSON.parse(raw)
    c.emit('GetGlobalObject', 13)
    c.get(14, 13, 'JSON')
    c.get(15, 14, 'parse')
    c.emit('Call2', 15, 15, 11, 18)
    c.emit('Mov', 11, 15)
    # Array payloads are the legacy hub shape: forward the raw string.
    c.emit('GetGlobalObject', 13)
    c.get(14, 13, 'Array')
    c.get(15, 14, 'isArray')
    c.emit('Call2', 15, 15, 11, 18)
    is_array = c.branch('JmpTrueLong', 15)
    raw_passthrough = c.branch('JmpLong')
    # Object payloads: rows = parsed.steps || parsed.diagnoseDetail || []
    c.finish(is_array)
    c.get(16, 11, 'steps')
    have_rows = c.branch('JmpTrueLong', 16)
    c.get(16, 11, 'diagnoseDetail')
    have_rows2 = c.branch('JmpTrueLong', 16)
    c.emit('NewArray', 16, 0)
    c.finish(have_rows)
    c.finish(have_rows2)
    # r11 = JSON.stringify(rows)
    c.emit('GetGlobalObject', 13)
    c.get(14, 13, 'JSON')
    c.get(15, 14, 'stringify')
    c.emit('Call2', 11, 15, 16, 18)
    done = c.branch('JmpLong')
    c.finish(raw_passthrough)
    c.emit('Mov', 11, 17)
    c.finish(done)
    c.finish(not_string)

    anchor = find_anchor(reader)
    body = rewrite(reader, FID, {anchor: bytes(c.raw)}, {})[0]

    result, placed = insert_bodies(reader, data, [body])
    small = reader.get_small_func_header_reader()
    meta = small.from_buffer(result, reader.functions_offset + FID * ctypes.sizeof(small))
    assert not meta.overflowed
    meta.offset, meta.bytecodeSizeInBytes = placed[0]
    meta.frameSize = max(meta.frameSize, 24)
    meta.highestReadCacheIndex = c.cache
    changed.add(FID)
    del meta
    changes.append({'function': FID,
                    'action': 'diagnose intake accepts official object messageDetail and legacy steps array'})
    return result
