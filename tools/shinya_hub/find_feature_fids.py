import sys
sys.stdout.reconfigure(encoding='utf-8')
from hermes_dec.parsers.hbc_file_parser import HBCReader

bundle_path = r'D:\Github\LabProbeApp\test\_analysis\apk\assets\index.android.bundle'
with open(bundle_path, 'rb') as f:
    bundle_data = f.read()

reader = HBCReader()
reader.read_whole_file(open(bundle_path, 'rb'))

target_strs = ['按以下时间段定时关闭', '定时关闭', '信道优化', '定时降低信号强度', '周围干扰扫描', '周边干扰扫描', '定时节能', '定时重启']
target_sids = {}
for sid, s in enumerate(reader.strings):
    for t in target_strs:
        if t == s:
            target_sids[sid] = t
            print(f'Target String {sid}: {s}')

fids = {}
for fid, header in enumerate(reader.function_headers):
    start = header.offset
    raw = bundle_data[start : start + header.bytecodeSizeInBytes]
    for sid, t in target_sids.items():
        sid_le16 = sid.to_bytes(2, 'little')
        if sid_le16 in raw:
            fids.setdefault(fid, []).append(t)

for fid, ts in sorted(fids.items()):
    print(f'FID {fid}: {ts}')
