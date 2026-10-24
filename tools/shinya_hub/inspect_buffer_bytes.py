from hermes_dec.parsers.hbc_file_parser import HBCReader
import struct

reader = HBCReader()
reader.read_whole_file(open(r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle', 'rb'))

# Let's inspect object_values
# In Hermes HBC v90:
# object_values has serialized values.
# Let's see what each byte in reader.object_values means:
# In HBC: values are encoded as tag (1 byte) followed by value:
# tag 0: null, 1: true, 2: false, 3: Number (int32 or double), 4: string id, etc.
# Or tag 114:
# Let's check what hermes bytecode parser says about buffer serialization!

print('Values around 69772:')
for i in range(69772, 69772 + 30):
    print(f'{i}: {reader.object_values[i]} (0x{reader.object_values[i]:02x})')

print('Values around 69781:')
for i in range(69781, 69781 + 30):
    print(f'{i}: {reader.object_values[i]} (0x{reader.object_values[i]:02x})')
