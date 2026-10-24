"""Place grown function bodies inside the bytecode region Hermes maps.

A Hermes file describes its bytecode area up to debugInfoOffset; everything from
there to the 20-byte footer is the debug section. Bodies appended after the
footer's predecessor are therefore outside the region the runtime treats as
code, and any closure that executes them dies with the JS thread. Insert new
bodies immediately before the debug section and move that whole block down.
"""
import struct

from hermes_dec.parsers.hbc_opcodes import hbc90 as ops

FOOTER = 20


def insert_bodies(reader, data, bodies):
    """Insert bodies at debugInfoOffset; return (data, [(offset, size), ...])."""
    header = type(reader.header)
    debug = struct.unpack_from('<I', data, header.debugInfoOffset.offset)[0]
    assert 0 < debug <= len(data) - FOOTER, debug
    joined = b''.join(bytes(body) for body in bodies)
    # Keep the debug section aligned exactly as the untouched file has it.
    if debug % 4 == 0:
        joined += bytes([ops.Unreachable.opcode]) * ((-len(joined)) % 4)
    result = bytearray(data[:debug]) + joined + data[debug:]
    struct.pack_into('<I', result, header.debugInfoOffset.offset, debug + len(joined))
    struct.pack_into('<I', result, header.fileLength.offset, len(result))
    placed = []
    cursor = debug
    for body in bodies:
        placed.append((cursor, len(body)))
        cursor += len(body)
    return result, placed


def assert_bodies_mapped(result):
    """Fail the build if any function body sits behind debugInfoOffset."""
    beyond = [i for i, h in enumerate(result.function_headers)
              if h.offset + h.bytecodeSizeInBytes > result.header.debugInfoOffset]
    assert not beyond, ('function bodies outside the mapped bytecode region', beyond[:8])
