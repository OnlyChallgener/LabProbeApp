import sys, struct
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
# Read array buffer at offset 58534
# In Hermes HBC, array buffer contains serialized elements
buf_offset = reader.array_buffer_offset + 58534
print('array_buffer_offset:', hex(reader.array_buffer_offset))
# Let's inspect the buffer bytes
raw = reader.file_buffer.getvalue()[buf_offset:buf_offset+200]
print('raw bytes:', list(raw[:50]))
# Hermes array buffer serialization: 0x20 is int32, 0x21 is int16, etc.
