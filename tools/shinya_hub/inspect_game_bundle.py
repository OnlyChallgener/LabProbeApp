import json
import io
import sys
import re
from pathlib import Path
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

OUT = Path('test/_analysis/oct13-official-game')
r = HBCReader()
r.read_whole_file(io.BytesIO((OUT / 'official.bundle').read_bytes()))
pattern = re.compile(r'AI降噪|AI流控|AI防抖|防节电|特殊终端|游戏加速引擎|Game.*(?:Log|Statistic|Engine|Priority)|(?:flow|qos|noise|jitter|sniffer|kick|gaming|power.?sav|terminal.*enhance|PowerEconomize|StaEconomize|LowRate|EsportAccelerateStatus)', re.I)
if len(sys.argv)>1:
    pattern=re.compile(sys.argv[1])
strings = {i: s for i, s in enumerate(r.strings) if len(s) < 300 and pattern.search(s)}
(OUT / 'game-strings.json').write_text(json.dumps(strings, ensure_ascii=False, indent=2), encoding='utf8')
print('API strings:', json.dumps({i:s for i,s in strings.items() if s.startswith('/')}, ensure_ascii=False))
matches = []
for fid, header in enumerate(r.function_headers):
    used = set()
    for inst in parse_hbc_bytecode(header, r):
        sid = None
        if 'String' in inst.inst.name and hasattr(inst, 'arg2'):
            sid = inst.arg2
        elif 'ById' in inst.inst.name:
            args=[getattr(inst,'arg'+str(n)) for n in range(1,9) if hasattr(inst,'arg'+str(n))]
            sid=args[-1]
        if sid in strings:
            used.add(sid)
    if used:
        matches.append({'id': fid, 'name': r.strings[header.functionName],
                        'strings': [strings[i] for i in sorted(used)]})
(OUT / 'game-functions-all.json').write_text(json.dumps(matches, ensure_ascii=False, indent=2), encoding='utf8')
print('Functions:', len(matches))
for row in matches:
    if len(sys.argv)>1 or any('/' in s or 'AI' in s or 'GameEngine' in s or 'Statistics' in s for s in row['strings']):
        print(json.dumps(row, ensure_ascii=False))
