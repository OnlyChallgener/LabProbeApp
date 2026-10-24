import json
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT=Path('test/_analysis/oct08-shinya-hub')
OUT=Path('test/_analysis/oct11-children/contracts')
OUT.mkdir(parents=True,exist_ok=True)
r=load((ROOT/'index.android.bundle').read_bytes())
rows=json.loads((ROOT/'index.json').read_text(encoding='utf8'))
for row in rows:
    if 20132<row['id']<20570 or 35900<row['id']<36360:
        words=[r.strings[s] for s in row.get('s',[]) if len(r.strings[s])<200]
        (OUT/(str(row['id'])+'-strings.json')).write_text(json.dumps(words,ensure_ascii=False),encoding='utf8')

def dump(fid):
    lines=[]
    for i in parse_hbc_bytecode(r.function_headers[fid],r):
        args=[getattr(i,'arg'+str(n+1)) for n in range(7) if hasattr(i,'arg'+str(n+1))]
        desc=''
        if 'String' in i.inst.name and len(args)>1:desc=r.strings[args[1]]
        elif 'ById' in i.inst.name:
            at=2 if i.inst.name.startswith('Put') else 3
            if len(args)>at and args[at]<len(r.strings):desc=r.strings[args[at]]
        lines.append(f'{i.original_pos:5} {i.inst.name:25} {args} {desc}')
    (OUT/(str(fid)+'.txt')).write_text('\n'.join(lines),encoding='utf8')

if __name__=='__main__':
    import sys
    for fid in map(int,sys.argv[1:]):dump(fid)
    for row in rows:
        if 20132<row['id']<20570:
            words=[r.strings[s] for s in row.get('s',[]) if len(r.strings[s])<200]
            if any('/netguard/' in w or w.startswith('netguard/') or 'childProtection' in w for w in words):
                print(row['id'],words)
