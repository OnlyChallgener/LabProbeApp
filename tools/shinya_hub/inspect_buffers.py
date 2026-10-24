from hermes_dec.parsers.hbc_file_parser import HBCReader

reader = HBCReader()
reader.read_whole_file(open(r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle', 'rb'))

# Let's inspect object_keys and object_values at offsets 69772 and 69781
for offset in [69772, 69781]:
    print(f'=== Offset {offset} ===')
    # print slice of object_values
    val_slice = reader.object_values[offset : offset + 30]
    print('Values:', list(val_slice))
    key_slice = reader.object_keys[offset : offset + 30]
    print('Keys:', list(key_slice))
