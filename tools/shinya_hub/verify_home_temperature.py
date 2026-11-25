"""Verify the UNMODIFIED original icon and click-open temperature tooltip.

No home-temperature patch is needed: original F22895 already reads the actual
temperature and inserts it between the original title and status. F22893 is a
fixed-size icon button and must not receive extra children. This verifier also
checks the original measure/Portal.add/Portal.remove lifecycle byte-for-byte.
It cannot replace screenshot validation of the installed APK.
"""
import argparse
import hashlib
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.serialized_literal_parser import unpack_slp_array, TagType
from patch_bundle import SOURCE_SHA, load
from verify_terminal_ipv6_patch import check_read_caches

FUNCTIONS = tuple(range(22893, 22900))


def literal(reader, buffer, offset, length):
    values = unpack_slp_array(buffer[offset:], length, reader.header.version).items
    return [reader.strings[item.value] if item.tag_type in {
        TagType.LongStringTag, TagType.ShortStringTag, TagType.ByteStringTag} else item.value
        for item in values]


def tooltip(reader, temperature):
    """Execute the original tooltip's actual Text-render instructions."""
    code = {i.original_pos: i for i in parse_hbc_bytecode(reader.function_headers[22895], reader)}
    element = lambda kind, props: {'kind': kind, 'props': dict(props)}
    dependencies = {6: {'jsx': element, 'jsxs': element}}
    module = {0: lambda dependency: dependencies[dependency], 1: list(range(9)), 4: {'Text': 'Text'}}
    high = temperature is not None and temperature >= 95
    r = {20: {0: temperature, 6: high}, 21: module, 24: 6, 2: None,
         23: element, 22: 'Text', 27: {'original': 'toolBarButtonText'},
         18: reader.strings[19856], 31: reader.strings[19674],
         30: {'original': 'statusColor'}}
    pc = 0x205
    for _ in range(100):
        if pc == 0x2c2:
            return r[22]
        i = code[pc]
        a = [getattr(i, 'arg'+str(n+1)) for n in range(len(i.inst.operands))]
        n = i.inst.name
        nxt = pc+1+len(bytes(i.inst.structure()))
        if n == 'LoadFromEnvironment': r[a[0]] = r[a[1]][a[2]]
        elif n == 'LoadConstNull': r[a[0]] = None
        elif n == 'LoadConstString': r[a[0]] = reader.strings[a[1]]
        elif n == 'JmpFalse':
            if not r[a[1]]: nxt = pc+a[0]
        elif n == 'GetByVal': r[a[0]] = r[a[1]][r[a[2]]]
        elif n == 'Call2': r[a[0]] = r[a[1]](r[a[3]])
        elif n == 'Call3': r[a[0]] = r[a[1]](r[a[3]], r[a[4]])
        elif n in ('GetById', 'GetByIdShort'): r[a[0]] = r[a[1]][reader.strings[a[3]]]
        elif n == 'Add': r[a[0]] = str(r[a[1]])+str(r[a[2]])
        elif n == 'Mov': r[a[0]] = r[a[1]]
        elif n == 'NewObject': r[a[0]] = {}
        elif n == 'NewObjectWithBufferLong':
            keys = literal(reader, reader.object_keys, a[3], a[2])
            values = literal(reader, reader.object_values, a[4], a[2])
            r[a[0]] = dict(zip(keys, values))
        elif n == 'NewArrayWithBufferLong':
            r[a[0]] = literal(reader, reader.arrays, a[3], a[2])+[None]*(a[1]-a[2])
        elif n in ('PutNewOwnById', 'PutNewOwnByIdShort'):
            r[a[0]][reader.strings[a[2]]] = r[a[1]]
        elif n == 'PutOwnByIndex': r[a[0]][a[2]] = r[a[1]]
        else: raise AssertionError(n)
        pc = nxt
    raise AssertionError('original tooltip segment did not settle')


def plain_text(value):
    if value is None: return ''
    if isinstance(value, dict): return plain_text(value['props'].get('children'))
    if isinstance(value, list): return ''.join(plain_text(item) for item in value)
    return str(value)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--bundle', type=Path)
    args = p.parse_args()
    source = Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.android.bundle'
    assert hashlib.sha256(source.read_bytes()).hexdigest() == SOURCE_SHA
    before = load(source.read_bytes())
    after = load(args.bundle.read_bytes() if args.bundle else source.read_bytes())
    for fid in FUNCTIONS:
        original, actual = before.function_headers[fid], after.function_headers[fid]
        before.file_buffer.seek(original.offset)
        after.file_buffer.seek(actual.offset)
        assert before.file_buffer.read(original.bytecodeSizeInBytes) == after.file_buffer.read(actual.bytecodeSizeInBytes), (
            fid, 'original icon/tooltip/lifecycle changed')
        for field in ('bytecodeSizeInBytes', 'frameSize', 'environmentSize',
                      'highestReadCacheIndex', 'highestWriteCacheIndex'):
            assert getattr(original, field) == getattr(actual, field), (fid, field)
        check_read_caches(after, fid)
    for value, suffix in ((60.2, '60.2\u2103 \u6b63\u5e38'), (50.47, '50.47\u2103 \u6b63\u5e38'),
                          (96, '96\u2103 \u504f\u9ad8')):
        node = tooltip(after, value)
        assert node['kind'] == 'Text'
        assert node['props']['style'] == {'original': 'toolBarButtonText'}
        text = plain_text(node)
        assert text == '\u8def\u7531\u5668\u82af\u7247\u6e29\u5ea6: '+suffix, repr(text)
    absent = plain_text(tooltip(after, None))
    assert '\u2103' not in absent
    print('PASS: original fixed icon, callbacks, measure/Portal lifecycle and metadata unchanged; original tooltip renders actual 60.2/50.47 C and original high-temperature status')


if __name__ == '__main__':
    main()
