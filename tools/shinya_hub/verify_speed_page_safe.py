"""Guard the original Speed page while its project-scoped link is redesigned."""
import sys
from pathlib import Path

from patch_bundle import load


def body(reader, fid):
    header = reader.function_headers[fid]
    return bytes(reader.file_buffer.getbuffer()[header.offset:header.offset + header.bytecodeSizeInBytes])


def main(bundle):
    root = Path(__file__).resolve().parents[2]
    original = load((root / 'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
    candidate = load(Path(bundle).read_bytes())
    for fid in (33130,):
        assert body(candidate, fid) == body(original, fid), f'Speed page changed: {fid}'
        assert candidate.function_headers[fid].frameSize == original.function_headers[fid].frameSize
        assert candidate.function_headers[fid].environmentSize == original.function_headers[fid].environmentSize
    print('PASS original Speed render body, hooks, frame and environment remain intact')


if __name__ == '__main__':
    main(sys.argv[1])
