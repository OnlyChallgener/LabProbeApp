"""Validate the actual hash table, including changed IPv6 event JSON keys."""
import argparse
import hashlib
from pathlib import Path

from patch_bundle import load
from hermes_identifier_hashes import hash_string, identifiers, update, verify


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[2] / 'test/_analysis/oct08-shinya-hub/index.android.bundle'
    original = source.read_bytes()
    reader = load(original)
    assert verify(reader) == 30958
    ids = identifiers(reader)
    # Reproduce the earlier binary's changed name / unchanged hash mismatch.
    reader.strings[37924] = 'watchId'
    reader.strings[45195] = 'ipv6Public'
    reader.strings[43523] = 'ipv6DisplayUntil'
    assert all(reader.identifier_hashes[ids.index(sid)] != hash_string(reader.strings[sid])
               for sid in (37924, 45195, 43523))
    try:
        verify(reader)
    except AssertionError:
        pass
    else:
        raise AssertionError('stale hash regression was not rejected')
    data = bytearray(original)
    assert update(data, reader) == 3
    data[-20:] = hashlib.sha1(data[:-20]).digest()
    updated = load(bytes(data))
    updated.strings = reader.strings
    assert verify(updated) == 30958
    if args.bundle:
        verify(load(args.bundle.read_bytes()))
    print('PASS: 30958 baseline hashes, stale IPv6 keys rejected, hash repair, actual bundle' if args.bundle
          else 'PASS: 30958 baseline hashes, stale IPv6 keys rejected, hash repair')


if __name__ == '__main__':
    main()
