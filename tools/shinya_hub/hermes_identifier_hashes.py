"""Keep HBC90 identifier hashes consistent with replacement string contents."""
import struct


def hash_string(value):
    # Hermes hashString / updateJenkinsHash; UTF16 code units, no final mixing.
    encoded = value.encode('utf-16-le')
    result = 0
    for (unit,) in struct.iter_unpack('<H', encoded):
        result = (result + unit) & 0xffffffff
        result = (result + (result << 10)) & 0xffffffff
        result ^= result >> 6
    return result


def identifiers(reader):
    assert reader.header.version == 90
    return [sid for sid, kind in enumerate(reader.string_kinds) if int(kind) == 1]


def verify(reader):
    ids = identifiers(reader)
    assert len(ids) == reader.header.identifierCount
    for sid, actual in zip(ids, reader.identifier_hashes):
        assert actual == hash_string(reader.strings[sid]), ('stale identifier hash', sid)
    return len(ids)


def update(data, reader):
    ids = identifiers(reader)
    # HBC90 stores the aligned identifier hash array directly before the small
    # string table. The latter is a sequence of 4-byte entries.
    offset = reader.table_offset - 4 * reader.header.identifierCount
    assert list(struct.unpack_from('<' + 'I' * len(ids), data, offset)) == reader.identifier_hashes
    changed = 0
    for index, sid in enumerate(ids):
        expected = hash_string(reader.strings[sid])
        if expected != reader.identifier_hashes[index]:
            struct.pack_into('<I', data, offset + index * 4, expected)
            changed += 1
    return changed


def verify_property_operands(reader):
    """A literal has text but no imported SymbolID; by-ID opcodes require one.

    Checking decoded spelling alone misses this runtime regression. Dynamic
    property access must load such a literal and use GetByVal/PutByVal.
    """
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    reads={'GetByIdShort','GetById','GetByIdLong','TryGetById','TryGetByIdLong',
           'PutById','PutByIdLong','TryPutById','TryPutByIdLong'}
    writes={'PutNewOwnByIdShort','PutNewOwnById','PutNewOwnByIdLong',
            'PutNewOwnNEById','PutNewOwnNEByIdLong'}
    count=0
    for fid,header in enumerate(reader.function_headers):
        for ins in parse_hbc_bytecode(header,reader):
            name=ins.inst.name
            sid=ins.arg4 if name in reads else ins.arg3 if name in writes else None
            if sid is not None:
                assert int(reader.string_kinds[sid])==1, ('non-identifier property operand',fid,ins.original_pos,name,sid,reader.strings[sid])
                count+=1
    return count
