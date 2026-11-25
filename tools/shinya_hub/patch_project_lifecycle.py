"""Select the acknowledged home before resetting navigation and refreshing lists."""
import ctypes
from hbc_grow import insert_bodies
from patch_terminal_ipv6 import Code
from patch_measured_status import rewrite
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops


def additions(reader, data, changed, changes):
    # The creation flow keeps the OFFICIAL single navigation: the create page
    # shows 创建中 and the acknowledged home is selected once by the original
    # list propagation. Earlier interventions (immediate updateState dispatch,
    # pending-project merge, live-selection re-read) produced extra hops
    # (default -> new -> default) on real devices, so they are removed.
    # Only ID normalization stays: canonical HUB fields survive the official
    # list adapter, and outward IDs match textually across number/string.
    bodies = []

    # Outward IDs may cross the JSON boundary as numbers or strings. Match
    # their textual ID, rather than treating the same ID as a missing home.
    ids = Code(reader); ids.emit('AddEmptyString', 1, 1); ids.emit('AddEmptyString', 0, 0)
    body, _, _ = rewrite(reader, 21056, {0x14: bytes(ids.raw)}, {})
    bodies.append((21056, body, reader.function_headers[21056].frameSize))

    # The official list adapter overwrites projectId with buildingId and
    # projectName with name, even when the HUB already supplied valid values.
    # Keep the canonical HUB fields; accept legacy aliases only when absent.
    row = Code(reader)
    row.emit('LoadParam', 4, 1); row.emit('LoadConstNull', 0); row.emit('LoadConstUndefined', 2); row.string(1, '')
    for dst, field, fallback in ((5, 'projectId', 'buildingId'), (6, 'projectName', 'name')):
        row.get(dst, 4, field)
        missing = [row.branch('JStrictEqualLong', dst, 0), row.branch('JStrictEqualLong', dst, 2), row.branch('JStrictEqualLong', dst, 1)]
        valid = row.branch('JmpLong')
        for branch in missing: row.finish(branch)
        row.get(dst, 4, fallback); row.finish(valid)
    row.emit('GetGlobalObject', 0); row.get(7, 0, 'Object'); row.get(8, 7, 'assign')
    row.emit('NewObject', 9); row.emit('NewObject', 10)
    row.put(10, 5, 'projectId'); row.put(10, 6, 'projectName')
    row.emit('Call4', 0, 8, 7, 9, 4, 10); row.emit('Ret', 0)
    bodies.append((20719, bytes(row.raw), 24))

    result, placed = insert_bodies(reader, data, [body for _, body, _ in bodies])
    small = reader.get_small_func_header_reader()
    for (target, _, frame), location in zip(bodies, placed):
        meta = small.from_buffer(result, reader.functions_offset+target*ctypes.sizeof(small))
        assert not meta.overflowed
        meta.offset, meta.bytecodeSizeInBytes = location
        meta.highestReadCacheIndex = max(meta.highestReadCacheIndex, 1)
        meta.frameSize = max(meta.frameSize, frame)
        del meta
        changed.add(target)
    changes.append({'function':20719,'action':'preserve valid canonical HUB ID/name during actual project-list normalization; legacy aliases only for missing fields'})
    changes.append({'function':21056,'action':'match outward project IDs textually across number/string boundaries'})
    return result
