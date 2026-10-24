"""Resolve station details across native compact and H5 colon MAC formats."""
import ctypes
import json
from pathlib import Path

from patch_terminal_ipv6 import Code


MATCHER = 33302
HELPER = 23462


def additions(reader, original, replace, data):
    # The original detail predicate uses strict equality. Native station lists
    # use uppercase compact MACs, but H5 staDetail passes a colon-form staMac.
    # On a miss, StaInfoModal falls back to route.sta (often only {mac}) and
    # consequently displays the generic name/icon instead of archived identity.
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    before = list(parse_hbc_bytecode(reader.function_headers[MATCHER], reader))
    assert before[-2].inst.name == 'StrictEq'
    assert before[-2].arg1 == 0 and before[-2].arg2 == 0 and before[-2].arg3 == 1
    index = json.loads((Path(__file__).resolve().parents[2] /
        'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'))
    by_id = {entry['id']: entry for entry in index}
    retired = set()
    pending = [23454]  # GameFeedBack is already removed and erased by caller.
    while pending:
        fid = pending.pop()
        if fid not in retired:
            retired.add(fid)
            pending.extend(by_id[fid]['f'])
    assert HELPER in retired
    assert all(entry['id'] in retired for entry in index if HELPER in entry['f'])

    c = Code(reader)
    c.emit('LoadParam', 0, 1)
    c.get(0, 0, 'mac')
    c.emit('GetEnvironment', 2, 0)
    c.emit('LoadFromEnvironment', 1, 2, 1)  # same route.sta as original predicate
    failures = [c.branch('JmpFalseLong', 0), c.branch('JmpFalseLong', 1)]
    c.get(1, 1, 'mac')
    failures.append(c.branch('JmpFalseLong', 1))
    # Keep normalization inside this comparison. IDs used by rate selectors,
    # display rows and router commands retain their original representations.
    for value in (0, 1):
        c.emit('TypeOf', 2, value)
        c.string(3, 'string')
        failures.append(c.branch('JStrictNotEqualLong', 2, 3))
        for separator in (':', '-'):
            c.get(2, value, 'split')
            c.string(3, separator)
            c.emit('Call2', value, 2, value, 3)
            c.get(2, value, 'join')
            c.string(3, '')
            c.emit('Call2', value, 2, value, 3)
        c.get(2, value, 'toUpperCase')
        c.emit('Call1', value, 2, value)
    failures.append(c.branch('JmpFalseLong', 0))
    c.emit('StrictEq', 0, 0, 1)
    c.emit('Ret', 0)
    for failure in failures:
        c.finish(failure)
    c.emit('LoadConstFalse', 0)
    c.emit('Ret', 0)
    replace(HELPER, bytes(c.raw), 'canonical MAC comparison for the original station detail lookup')

    w = Code(reader)
    w.emit('GetEnvironment', 0, 0)
    w.emit('CreateClosure', 1, 0, HELPER)
    w.emit('LoadParam', 2, 1)
    w.emit('LoadConstUndefined', 3)
    w.emit('Call2', 0, 1, 3, 2)
    w.emit('Ret', 0)
    replace(MATCHER, bytes(w.raw), 'resolve H5 station MAC against current project archive')
    # Call2 needs outgoing arguments plus Hermes' six saved-frame slots, in
    # addition to r0..r3. The old leaf predicate had only five frame slots;
    # keeping that header corrupts the native Hermes stack despite a mock VM
    # successfully interpreting all explicit register accesses.
    small = reader.get_small_func_header_reader()
    header = small.from_buffer(data, reader.functions_offset + MATCHER * ctypes.sizeof(small))
    if header.overflowed:
        header = reader.get_large_func_header_reader().from_buffer(data, (header.infoOffset << 16) | header.offset)
    header.frameSize = max(header.frameSize, 14)
