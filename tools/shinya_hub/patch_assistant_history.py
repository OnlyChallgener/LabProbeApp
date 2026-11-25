"""Retain original AsyncStorage chat, isolated by native HUB/account/project.

Merge STRINGS before allocation; call additions with a fresh current reader.
This module restores the baseline loader's real cache branch, including its
exception handler. It does not touch socket callbacks, message renderers or
the existing manual clear action. Native assistantHistoryScope is synchronous.
"""
import ctypes
import hashlib
import struct
from pathlib import Path

from hbc_grow import insert_bodies
from patch_bundle import SOURCE_SHA, load
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code

STRINGS = {30092: 'assistantHistoryScope'}
FIDS = (35695, 35705, 35708, 35721, 35722, 35728)
BASELINE = Path(__file__).resolve().parents[2] / 'test/_analysis/oct08-shinya-hub/index.android.bundle'


class C(Code):
    def __init__(self, reader, cache=0):
        super().__init__(reader); self.cache = cache; self.writes = {}; self.write_base = 0

    def sid(self, text):
        return 30092 if text == 'assistantHistoryScope' else super().sid(text)

    def get(self, dst, obj, text):
        self.cache += 1
        self.emit('GetById', dst, obj, self.cache, self.sid(text))

    def set(self, obj, value, text):
        # Private properties are added to a stable React useRef object.
        sid = self.sid(text)
        cache = self.writes.setdefault(sid, self.write_base + len(self.writes) + 1)
        self.emit('PutById', obj, value, cache, sid)


def _rewrite(reader, fid, insertions, replacements):
    h = reader.function_headers[fid]
    exception = h.hasExceptionHandler
    try:
        h.hasExceptionHandler = 0
        return rewrite(reader, fid, insertions, replacements)
    finally:
        h.hasExceptionHandler = exception


def plans(reader, original):
    result = {}
    # Run this hook unconditionally, preserving hook order across scope changes.
    # r60+ are newly allocated; original r0..r58 remain intact.
    c = C(reader, 66)
    c.emit('LoadConstUndefined', 60)
    c.get(61, 1, 'useRef'); c.emit('Call2', 62, 61, 60, 60)
    c.emit('StoreToEnvironment', 17, 35, 62)
    c.emit('LoadFromEnvironment', 63, 18, 6)
    c.get(63, 63, 'NativeModules'); c.get(63, 63, 'sta')
    c.get(64, 63, 'assistantHistoryScope')
    c.emit('LoadFromEnvironment', 65, 17, 23)
    c.emit('Call2', 66, 64, 63, 65)
    c.emit('StoreToEnvironment', 17, 36, 66)
    # Account and project both contribute to the opaque key. A changed identity
    # closes the old socket effect and hydrates its own independent snapshot.
    deps = C(reader); deps.emit('PutOwnByIndex', 5, 66, 0)
    result[35695] = ({0x26c: bytes(c.raw), 0x27a: bytes(deps.raw)},
                     {0x276: ('NewArray', [5, 0], _op(reader, 'NewArray', 5, 1))}, c.cache)

    # Capture immutable expected scope in this effect's environment. The stable
    # ref identifies which effect owns hydration; old async loads cannot win.
    c = C(reader)
    c.emit('GetEnvironment', 4, 0)
    c.emit('LoadFromEnvironment', 5, 4, 35)
    c.emit('LoadFromEnvironment', 6, 4, 36)
    c.emit('StoreToEnvironment', 0, 2, 5)
    c.emit('StoreToEnvironment', 0, 3, 6)
    c.set(5, 6, 'scope'); c.emit('LoadConstFalse', 7); c.set(5, 7, 'ready')
    c.emit('NewObject', 7); c.set(5, 7, 'current')
    c.emit('StoreToEnvironment', 0, 4, 7)
    c.emit('LoadFromEnvironment', 8, 4, 10)
    c.emit('NewArray', 9, 0); c.emit('LoadConstUndefined', 7)
    c.emit('Call2', 8, 8, 7, 9)
    result[35705] = ({0x2: bytes(c.raw)}, {}, c.cache)

    # Always rebuild from the hashed original: removes the old unconditional
    # fresh-start patch without disturbing any other function in current input.
    c = C(reader, 8)
    c.emit('GetEnvironment', 9, 2)  # effect environment
    c.emit('LoadFromEnvironment', 10, 9, 2)
    c.emit('LoadFromEnvironment', 6, 9, 3)
    empty = c.branch('JmpTrue', 6)
    c.emit('CompleteGenerator'); c.emit('Ret', 1); c.finish(empty)
    # getMessageByAccountId now receives the isolated native key.
    # A resumed Promise must still belong to the active effect before any
    # parsed history or preset card can alter the current page.
    guard = C(reader, c.cache)
    guard.emit('GetEnvironment', 9, 2)
    guard.emit('LoadFromEnvironment', 10, 9, 2)
    guard.emit('LoadFromEnvironment', 11, 9, 3)
    guard.get(12, 10, 'scope')
    active = guard.branch('JStrictEqual', 12, 11)
    guard.emit('CompleteGenerator'); guard.emit('Ret', 1); guard.finish(active)
    guard.emit('LoadFromEnvironment', 11, 9, 4)
    guard.get(12, 10, 'current')
    active = guard.branch('JStrictEqual', 12, 11)
    guard.emit('CompleteGenerator'); guard.emit('Ret', 1); guard.finish(active)
    ready = C(reader, guard.cache)
    ready.emit('LoadConstTrue', 12); ready.set(10, 12, 'ready')
    # Original account lookup remains harmless but is bypassed at its final
    # value: captured scope is reloaded immediately before the storage call.
    key = C(reader); key.emit('GetEnvironment', 9, 2)
    key.emit('LoadFromEnvironment', 6, 9, 3)
    result[35708] = ({0x10: bytes(c.raw), 0x49: bytes(key.raw),
                      0x58: bytes(guard.raw), 0x95: bytes(ready.raw)}, {}, guard.cache)

    # Cleanup invalidates only its own generation, then executes original
    # socket/timer cleanup byte-for-byte. Old cleanup cannot cancel a new scope.
    c = C(reader, 3)
    c.emit('GetEnvironment', 4, 0)
    c.emit('LoadFromEnvironment', 5, 4, 2)
    c.emit('LoadFromEnvironment', 6, 4, 4)
    c.get(7, 5, 'current'); skip = c.branch('JStrictNotEqual', 7, 6)
    c.emit('LoadConstUndefined', 7); c.set(5, 7, 'scope')
    c.emit('LoadConstFalse', 7); c.set(5, 7, 'ready'); c.finish(skip)
    result[35721] = ({0: bytes(c.raw)}, {}, c.cache)

    # Save only a successfully hydrated, still-current snapshot. No Date,
    # pagination cap, array slicing or retention expiry is introduced.
    c = C(reader, 6)
    c.emit('GetEnvironment', 6, 0)
    c.emit('LoadFromEnvironment', 7, 6, 35)
    c.emit('LoadFromEnvironment', 8, 6, 36)
    c.get(9, 7, 'ready'); blocked = [c.branch('JmpFalseLong', 9)]
    c.get(9, 7, 'scope'); blocked.append(c.branch('JStrictNotEqualLong', 9, 8))
    blocked.append(c.branch('JmpFalseLong', 8))
    # Existing save function body uses r0..r5, so private guard registers survive.
    # Restore account arg to expected scope at the existing call boundary.
    body, _, _ = rewrite(reader, 35722, {0x4c: _op(reader, 'Mov', 3, 8)}, {})
    c.raw.extend(body)
    for branch in blocked: c.finish(branch)
    c.emit('LoadConstUndefined', 0); c.emit('Ret', 0)
    result[35722] = (bytes(c.raw), c.cache)
    # A user may clear while the storage Promise is pending. Invalidate only
    # that load generation so it cannot restore the just-deleted messages.
    c = C(reader, reader.function_headers[35728].highestReadCacheIndex)
    c.write_base = reader.function_headers[35728].highestWriteCacheIndex
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    clear = {i.original_pos:i for i in parse_hbc_bytecode(reader.function_headers[35728],reader)}
    site=clear[0x2c9]
    assert site.inst.name=='LoadFromEnvironment' and (site.arg1,site.arg2,site.arg3)==(8,5,10)
    c.emit('LoadFromEnvironment', 60, 5, 35)
    c.emit('LoadFromEnvironment', 62, 5, 36)
    c.get(63, 60, 'scope')
    active = c.branch('JStrictEqual', 62, 63)
    c.emit('LoadConstUndefined', 61); c.emit('Ret', 61); c.finish(active)
    c.emit('NewObject', 61); c.set(60, 61, 'current')
    c.emit('LoadConstTrue', 61); c.set(60, 61, 'ready')
    result[35728] = ({0x2c9: bytes(c.raw)}, {}, c.cache)
    return result


def _op(reader, name, *args):
    c = C(reader); c.emit(name, *args); return bytes(c.raw)


def additions(reader, data, changed, changes):
    baseline = BASELINE.read_bytes()
    assert hashlib.sha256(baseline).hexdigest() == SOURCE_SHA
    source = load(baseline)
    assert reader.header.version == 90
    for fid in FIDS:
        if fid!=35728:
            assert reader.function_headers[fid].bytecodeSizeInBytes == source.function_headers[fid].bytecodeSizeInBytes, fid
    bodies = []; info = {}
    for fid, plan in plans(reader, source).items():
        owner = source if fid == 35708 else reader
        if fid == 35722:
            body, cache = plan; location = None
        else:
            ins, rep, cache = plan
            body, location, _ = _rewrite(owner, fid, ins, rep)
        bodies.append(body)
        info[fid] = (len(body), location, cache)
    # Exception offsets refer to the relocated function, and must follow its
    # grown instruction boundaries. Keep the original catch path and range.
    joined_size = sum(map(len, bodies))
    pad = (- (reader.header.debugInfoOffset + joined_size)) % 4
    tables = []; table_fids = []
    for fid in FIDS:
        owner = source if fid == 35708 else reader
        if owner.function_headers[fid].hasExceptionHandler:
            location = info[fid][1]; handlers = owner.function_id_to_exc_handlers[fid]
            tables.append(struct.pack('<I',len(handlers)) + b''.join(
                struct.pack('<III',location[h.start],location[h.end],location[h.target]) for h in handlers))
            table_fids.append(fid)
    result, placed = insert_bodies(reader, data, bodies + [bytes(pad)] + tables)
    table_offsets = dict(zip(table_fids,(p[0] for p in placed[len(FIDS)+1:])))
    small = reader.get_small_func_header_reader()
    for fid, (offset, size) in zip(FIDS, placed):
        meta = small.from_buffer(result, reader.functions_offset + fid * ctypes.sizeof(small))
        assert not meta.overflowed, fid
        meta.offset = offset; meta.bytecodeSizeInBytes = size
        meta.highestReadCacheIndex = info[fid][2]
        meta.highestWriteCacheIndex = (meta.highestWriteCacheIndex + 2 if fid==35728
                                       else max(meta.highestWriteCacheIndex, 3))
        meta.frameSize = max(meta.frameSize, 80 if fid in (35695,35728) else 32)
        if fid == 35695: meta.environmentSize = 37
        if fid == 35705: meta.environmentSize = 5
        if fid in table_offsets: meta.infoOffset = table_offsets[fid]
        changed.add(fid)
        changes.append({'function': fid, 'action': 'scope and hydrate original persistent assistant chat', 'appendedBytes': size})
        del meta
    return result
