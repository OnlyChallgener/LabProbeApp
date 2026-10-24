"""Append a self-expiring IPv6 value without altering the original IP row.

Integration: additions(reader, original, replace_function, data), then merge
STRINGS into the caller's labels. Native HubStationModule watch events refresh
only the selected terminal; the local React text owns expiry and wrapping.
"""
import ctypes
import json
import struct
from pathlib import Path

from hermes_dec.parsers.hbc_opcodes import hbc90 as ops

# All belong exclusively to the removed promotional banner tree. The caller
# merges these with its existing labels and checks for conflicting values.
STRINGS = {45195: 'ipv6Public', 43523: 'ipv6DisplayUntil', 40823: 'IPv6地址',
           21182: 'startIpv6Watch', 20660: 'stopIpv6Watch',
           21230: 'HubStationIpv6', 37924: 'watchId', 20659: 'copyIpv6'}
IDS = dict(zip(('address', 'deadline', 'label', 'start', 'stop', 'event', 'watch', 'copy'), STRINGS))


class Code:
    def __init__(self, reader):
        self.reader = reader
        self.raw = bytearray()
        self.long_branches = set()

    def emit(self, name, *args):
        op = getattr(ops, name)
        operand = op.structure()
        assert len(args) == len(operand._fields_)
        for (field, _), value in zip(operand._fields_, args):
            setattr(operand, field, value)
        self.raw.append(op.opcode)
        self.raw.extend(bytes(operand))

    def sid(self, text):
        return self.reader.strings.index(text)

    def get(self, dst, obj, text):
        self.emit('GetById', dst, obj, 1, self.sid(text))

    def put(self, obj, value, text):
        self.emit('PutNewOwnById', obj, value, self.sid(text))

    def string(self, reg, text):
        self.emit('LoadConstString', reg, self.sid(text))

    def number(self, reg, value):
        self.emit('LoadConstInt', reg, value)

    def branch(self, name, *args):
        position = len(self.raw)
        self.emit(name, 0, *args)
        if name.endswith('Long'):
            self.long_branches.add(position)
        return position

    def finish(self, position):
        delta = len(self.raw) - position
        if position in self.long_branches:
            struct.pack_into('<i', self.raw, position + 1, delta)
            return
        assert -128 <= delta <= 127
        self.raw[position + 1] = delta & 255

    def now(self, dst, glob=18, owner=19, fn=20):
        self.emit('GetGlobalObject', glob)
        self.get(owner, glob, 'Date')
        self.get(fn, owner, 'now')
        self.emit('Call1', dst, fn, owner)


def additions(reader, original, replace, data, ids=None):
    ids = IDS if ids is None else ids
    assert set(ids) == {'address', 'deadline', 'label', 'start', 'stop', 'event', 'watch', 'copy'}
    index = json.loads((Path(__file__).resolve().parents[2] /
                        'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'))
    by_id = {item['id']: item for item in index}
    retired = set()
    pending = [23246, 23454]
    while pending:
        fid = pending.pop()
        if fid not in retired:
            retired.add(fid)
            pending.extend(by_id[fid]['f'])
    for sid in ids.values():
        assert all(item['id'] in retired for item in index if sid in item['s']), sid
    # Only this retired banner owns the helpers; never commandeer live callbacks.
    for helper in (23247, 23248, 23249, 23250, 23251):
        owners = [f['id'] for f in index if helper in f['f']]
        assert owners == ([23250] if helper == 23251 else [23246]), (helper, owners)
    source = reader.function_headers[33330]
    assert source.bytecodeSizeInBytes == 1665 and source.frameSize == 38
    original_body = original[source.offset:source.offset + source.bytecodeSizeInBytes]
    # Save original rows builder as 23248 by header alias. This leaves its
    # internal branch offsets, lexical depths and original IPv4 instructions intact.
    # 33330 becomes a small wrapper over that untouched body.
    w = Code(reader)
    w.emit('GetEnvironment', 0, 0)  # original station-detail environment
    w.emit('GetEnvironment', 1, 1)  # original module environment
    w.emit('CreateClosure', 2, 0, 23248)
    w.emit('LoadConstUndefined', 3)
    w.emit('Call1', 4, 2, 3)
    w.emit('LoadFromEnvironment', 5, 0, 2)  # sta
    w.emit('LoadFromEnvironment', 6, 1, 4)  # React
    w.get(6, 6, 'createElement')
    w.emit('CreateClosure', 7, 1, 23250)  # actual component; hooks run outside useMemo
    w.emit('NewObject', 8)
    w.emit('LoadConstNull', 9)
    skip = w.branch('JEqual', 5, 9)
    w.emit('LoadConstString', 10, ids['address'])
    w.emit('GetByVal', 10, 5, 10)
    w.put(8, 10, 'address')
    w.emit('LoadConstString', 10, ids['deadline'])
    w.emit('GetByVal', 10, 5, 10)
    w.put(8, 10, 'endTime')
    for field in ('projectId', 'sn', 'mac'):
        w.get(10, 5, field)
        w.put(8, 10, field)
    w.get(10,5,'projectId')
    w.emit('AddEmptyString',10,10)
    w.string(11, ':')
    w.emit('Add',10,10,11)
    w.get(12,5,'sn')
    w.emit('Add',10,10,12)
    w.emit('Add',10,10,11)
    w.get(12,5,'mac')
    w.emit('Add',10,10,12)
    w.emit('LoadConstString', 11, ids['watch'])
    w.emit('PutByVal', 8, 11, 10)
    w.put(8,10,'key')
    w.finish(skip)
    w.emit('LoadFromEnvironment', 10, 0, 9)
    w.get(10, 10, 'infoItemText')
    w.put(8, 10, 'style')
    w.emit('Call3', 8, 6, 3, 7, 8)
    w.emit('NewObject', 9)
    w.string(10, 'ip')
    w.put(9, 10, 'type')
    w.emit('LoadConstString', 10, ids['label'])
    w.put(9, 10, 'label')
    w.put(9, 8, 'value')
    w.get(10, 4, 'splice')
    w.number(11, 3)
    w.number(12, 0)
    w.emit('Call4', 13, 10, 4, 11, 12, 9)
    w.emit('Ret', 4)

    # Component props are {address,endTime,style}. A state tick only triggers a
    # local rerender on expiration. Address is never copied into state/cache.
    c = Code(reader)
    c.emit('CreateEnvironment', 0)
    c.emit('LoadParam', 1, 1)
    c.emit('GetEnvironment', 2, 0)  # module environment supplied by wrapper
    c.emit('LoadFromEnvironment', 3, 2, 4)
    c.emit('LoadFromEnvironment', 4, 2, 5)
    c.get(5, 3, 'useState')
    c.emit('LoadConstUndefined', 6)
    c.emit('Call2', 5, 5, 6, 1)
    c.number(7, 0)
    c.emit('GetByVal', 22, 5, 7)
    c.number(7, 1)
    c.emit('GetByVal', 5, 5, 7)
    c.emit('StoreToEnvironment', 0, 0, 5)
    c.emit('StoreToEnvironment', 0, 1, 22)
    c.emit('StoreToEnvironment', 0, 2, 1)
    c.emit('LoadConstString', 7, ids['watch'])
    c.emit('GetByVal', 7, 1, 7)
    c.get(5, 3, 'useEffect')
    c.emit('CreateClosure', 8, 0, 23247)
    c.emit('NewArray', 9, 1)
    c.emit('PutOwnByIndex', 9, 7, 0)
    c.emit('Call3', 5, 5, 6, 8, 9)
    c.string(10, '--')
    c.get(7, 22, 'endTime')
    c.number(8, 1000)
    c.emit('Mul', 7, 7, 8)
    c.now(11)
    c.emit('Greater', 12, 7, 11)
    expired = c.branch('JmpFalse', 12)
    c.get(11, 22, 'address')
    empty = c.branch('JmpFalse', 11)
    c.emit('Mov', 10, 11)
    c.finish(expired)
    c.finish(empty)
    c.emit('NewObject', 11)
    c.number(12, 1)
    c.put(11, 12, 'flexShrink')
    c.string(12, 'right')
    c.put(11, 12, 'textAlign')
    c.string(12, '100%')
    c.put(11, 12, 'maxWidth')
    c.get(12, 1, 'style')
    c.emit('NewArray', 13, 2)
    c.emit('PutOwnByIndex', 13, 12, 0)
    c.emit('PutOwnByIndex', 13, 11, 1)
    c.emit('NewObject', 14)
    c.put(14, 13, 'style')
    c.emit('LoadConstTrue', 12)
    c.put(14, 12, 'selectable')
    c.put(14, 10, 'children')
    # A Text onPress receives a SyntheticEvent. Never bind a native method
    # directly: that would serialize the event's cyclic React object graph.
    c.emit('StoreToEnvironment',0,8,10)
    c.emit('CreateClosure',17,0,23463)
    c.put(14,17,'onPress')
    c.get(15, 3, 'createElement')
    c.get(16, 4, 'Text')
    c.emit('Call3', 17, 15, 6, 16, 14)
    c.emit('Ret', 17)

    effect = Code(reader)
    effect.emit('GetEnvironment', 0, 0)
    effect.emit('LoadConstFalse', 1)
    effect.emit('StoreToEnvironment', 0, 7, 1)
    effect.emit('GetEnvironment', 1, 1)
    effect.emit('LoadFromEnvironment', 2, 1, 5)  # RN
    effect.get(3, 2, 'NativeModules')
    effect.get(3, 3, 'sta')
    effect.emit('StoreToEnvironment', 0, 5, 3)
    effect.get(4, 2, 'DeviceEventEmitter')
    effect.get(5, 4, 'addListener')
    effect.emit('LoadConstString', 6, ids['event'])
    effect.emit('CreateClosure', 7, 0, 23249)
    effect.emit('StoreToEnvironment', 0, 6, 7)
    effect.emit('Call3', 8, 5, 4, 6, 7)
    effect.emit('StoreToEnvironment', 0, 4, 8)
    effect.emit('LoadConstUndefined', 9)
    effect.emit('Call1', 8, 7, 9)  # arm initial deadline without a network request
    effect.emit('LoadFromEnvironment', 10, 0, 2)
    effect.emit('LoadConstString', 11, ids['start'])
    effect.emit('GetByVal', 11, 3, 11)
    effect.get(12, 10, 'projectId')
    effect.get(13, 10, 'sn')
    effect.get(14, 10, 'mac')
    effect.emit('LoadConstString', 15, ids['watch'])
    effect.emit('GetByVal', 15, 10, 15)
    effect.emit('NewArray', 16, 4)
    for index, reg in enumerate((12, 13, 14, 15)):
        effect.emit('PutOwnByIndex', 16, reg, index)
    effect.get(17, 11, 'apply')
    effect.emit('Call3', 16, 17, 11, 3, 16)
    effect.emit('CreateClosure', 17, 0, 23251)
    effect.emit('Ret', 17)

    tick = Code(reader)
    tick.emit('GetEnvironment', 0, 0)
    tick.emit('LoadParam', 1, 1)
    tick.emit('LoadConstUndefined', 2)
    tick.emit('LoadFromEnvironment', 3, 0, 7)
    disposed = tick.branch('JmpTrueLong', 3)
    no_event = tick.branch('JmpFalse', 1)
    tick.emit('GetGlobalObject', 3)
    tick.get(3, 3, 'JSON')
    tick.get(4, 3, 'parse')
    tick.emit('Call2', 1, 4, 3, 1)
    tick.emit('LoadFromEnvironment', 5, 0, 2)
    tick.emit('LoadConstString', 6, ids['watch'])
    tick.emit('GetByVal', 7, 1, 6)
    tick.emit('GetByVal', 8, 5, 6)
    tick.emit('StrictEq', 7, 7, 8)
    wrong_scope = tick.branch('JmpFalseLong', 7)
    tick.emit('NewObject', 5)
    tick.emit('LoadConstString', 6, ids['address'])
    tick.emit('GetByVal', 6, 1, 6)
    tick.put(5, 6, 'address')
    tick.emit('LoadConstString', 6, ids['deadline'])
    tick.emit('GetByVal', 6, 1, 6)
    tick.put(5, 6, 'endTime')
    tick.emit('StoreToEnvironment', 0, 1, 5)
    tick.finish(no_event)
    tick.emit('LoadFromEnvironment', 5, 0, 1)
    tick.emit('GetGlobalObject', 3)
    tick.get(4, 3, 'clearTimeout')
    tick.emit('LoadFromEnvironment', 6, 0, 3)
    tick.emit('Call2', 7, 4, 2, 6)
    tick.get(4, 3, 'Object')
    tick.get(6, 4, 'assign')
    tick.emit('NewObject', 7)
    tick.emit('Call3', 5, 6, 4, 7, 5)
    tick.emit('LoadFromEnvironment', 6, 0, 0)
    tick.emit('Call2', 7, 6, 2, 5)
    tick.get(6, 5, 'endTime')
    tick.number(7, 1000)
    tick.emit('Mul', 6, 6, 7)
    tick.now(7)
    tick.emit('Sub', 6, 6, 7)
    tick.number(7, 0)
    tick.emit('Greater', 7, 6, 7)
    expired = tick.branch('JmpFalse', 7)
    tick.get(4, 3, 'setTimeout')
    tick.emit('LoadFromEnvironment', 5, 0, 6)
    tick.emit('Call3', 7, 4, 2, 5, 6)
    tick.emit('StoreToEnvironment', 0, 3, 7)
    tick.finish(expired)
    tick.finish(wrong_scope)
    tick.finish(disposed)
    tick.emit('Ret', 2)

    cleanup = Code(reader)
    cleanup.emit('GetEnvironment', 0, 0)
    cleanup.emit('LoadConstTrue', 1)
    cleanup.emit('StoreToEnvironment', 0, 7, 1)
    cleanup.emit('LoadFromEnvironment', 1, 0, 3)
    cleanup.emit('GetGlobalObject', 2)
    cleanup.get(3, 2, 'clearTimeout')
    cleanup.emit('LoadConstUndefined', 4)
    cleanup.emit('Call2', 5, 3, 4, 1)
    cleanup.emit('LoadFromEnvironment', 1, 0, 4)
    cleanup.get(3, 1, 'remove')
    cleanup.emit('Call1', 5, 3, 1)
    cleanup.emit('LoadFromEnvironment', 1, 0, 5)
    cleanup.emit('LoadConstString', 2, ids['stop'])
    cleanup.emit('GetByVal', 3, 1, 2)
    cleanup.emit('LoadFromEnvironment', 2, 0, 2)
    cleanup.emit('LoadConstString', 5, ids['watch'])
    cleanup.emit('GetByVal', 2, 2, 5)
    cleanup.emit('Call2', 5, 3, 1, 2)
    cleanup.emit('Ret', 4)

    copy = Code(reader)
    copy.emit('GetEnvironment',0,0)
    copy.emit('LoadFromEnvironment',1,0,8)
    copy.emit('GetEnvironment',2,1)
    copy.emit('LoadFromEnvironment',2,2,5)
    copy.get(2,2,'NativeModules')
    copy.get(2,2,'sta')
    copy.emit('LoadConstString',3,ids['copy'])
    copy.emit('GetByVal',3,2,3)
    copy.emit('Call2',4,3,2,1)
    copy.emit('LoadConstUndefined',4)
    copy.emit('Ret',4)

    storage = reader.function_headers[23246]
    combined = bytearray([ops.LoadConstNull.opcode, 0, ops.Ret.opcode, 0])
    locations = {}
    for fid, code in ((33330, w), (23250, c), (23247, effect), (23249, tick), (23251, cleanup), (23463,copy)):
        locations[fid] = (storage.offset + len(combined), len(code.raw))
        combined.extend(code.raw)
    # Hermes deduplicates a few other callbacks onto the retired children's
    # original bytes. Preserve their bodies and move only their headers too.
    aliases = {}
    for original_fid in (23247, 23251):
        old = reader.function_headers[original_fid]
        body = original[old.offset:old.offset + old.bytecodeSizeInBytes]
        location = storage.offset + len(combined)
        combined.extend(body)
        for fid, header in enumerate(reader.function_headers):
            if fid != original_fid and header.offset == old.offset:
                aliases[fid] = (location, len(body))
    end_storage = reader.function_headers[23251]
    capacity = end_storage.offset + end_storage.bytecodeSizeInBytes - storage.offset
    assert capacity == sum(reader.function_headers[f].bytecodeSizeInBytes for f in range(23246, 23252))
    assert set(fid for fid, header in enumerate(reader.function_headers)
            if header.offset < storage.offset + capacity
            and header.offset + header.bytecodeSizeInBytes > storage.offset) == set(range(23246, 23252)) | set(aliases)
    assert len(combined) <= capacity, (len(combined), capacity)
    replace(23246, bytes([ops.LoadConstNull.opcode, 0, ops.Ret.opcode, 0]), 'retain null banner')

    small = reader.get_small_func_header_reader()
    def metadata(fid):
        raw = small.from_buffer(data, reader.functions_offset + fid * ctypes.sizeof(small))
        return (reader.get_large_func_header_reader().from_buffer(data, (raw.infoOffset << 16) | raw.offset)
                if raw.overflowed else raw)

    assert [f['id'] for f in by_id.values() if 23463 in f['f']]==[23462]
    assert all(fid==23463 or header.offset!=reader.function_headers[23463].offset for fid,header in enumerate(reader.function_headers))
    for fid in (33330, 23247, 23248, 23249, 23250, 23251,23463):
        # Register each header in the caller's changed-functions audit first.
        replace(fid, bytes([ops.LoadConstNull.opcode, 0, ops.Ret.opcode, 0]),
                'bind private IPv6 helper' if fid != 23248 else 'alias original unchanged parameter rows')
    for fid in aliases:
        old = reader.function_headers[fid]
        replace(fid, original[old.offset:old.offset + old.bytecodeSizeInBytes],
                'preserve deduplicated callback while reserving retired banner storage')
    # replace(33330) above cleared its old storage. Restore exact original bytes
    # before making 23248 alias it. No other header points into this body.
    data[source.offset:source.offset + len(original_body)] = original_body
    data[storage.offset:storage.offset + capacity] = combined + bytes([ops.Unreachable.opcode]) * (capacity - len(combined))
    metadata(23246).bytecodeSizeInBytes = 4
    for fid, (offset, size) in aliases.items():
        meta = metadata(fid)
        meta.offset = offset
        meta.bytecodeSizeInBytes = size
    for fid in (33330, 23247, 23248, 23249, 23250, 23251,23463):
        meta = metadata(fid)
        if fid == 23248:
            offset, size = source.offset, source.bytecodeSizeInBytes
            frame, params = source.frameSize, source.paramCount
        else:
            offset, size = locations[fid]
            frame, params = 38, (2 if fid in (23249, 23250) else 1)
        meta.offset = offset
        meta.bytecodeSizeInBytes = size
        meta.frameSize = frame
        meta.paramCount = params
        meta.environmentSize = (source.environmentSize if fid == 23248 else
                                9 if fid == 23250 else 0)
        meta.highestReadCacheIndex = 64
        meta.highestWriteCacheIndex = 8
        meta.hasExceptionHandler = 0

