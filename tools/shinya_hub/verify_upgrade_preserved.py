"""Assert the original upgrade page, styling and project state remain untouched."""
from pathlib import Path
from patch_bundle import load
source=Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
patched=Path('test/_h3b/1791544164/decoded/assets/index.android.bundle').read_bytes()
before,after=load(source),load(patched)
for fid in (32270,32274,32275,32276,32277,32297,32298,24043,24047,24049,24051,24053,24054,24075,24077):
    h=before.function_headers[fid]
    assert source[h.offset:h.offset+h.bytecodeSizeInBytes]==patched[h.offset:h.offset+h.bytecodeSizeInBytes],fid
assert before.strings[20086]==after.strings[20086]
print('Original upgrade renderer, per-project manager and detection caller: 15 function bodies identical.')
