"""Bounded AArch64 string references in the retained firmware event writer."""
import struct
from pathlib import Path
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_ARM
from capstone.arm64 import ARM64_OP_IMM

p=Path('test/_analysis/extract/rootfs/usr/sbin/low_rate.elf');b=p.read_bytes()
h=struct.unpack_from('<HHIQQQIHHHHHH',b,16)
segments=[struct.unpack_from('<IIQQQQQQ',b,h[4]+i*h[8]) for i in range(h[9])]
def address(offset):
 for t,flags,off,vaddr,_,length,_,_ in segments:
  if t==1 and off<=offset<off+length:return vaddr+offset-off
 raise ValueError(offset)
targets={address(b.index(s.encode()+b'\0')):s for s in ('EventType','StaMac','Time','GetEventLog')}
md=Cs(CS_ARCH_ARM64,CS_MODE_ARM);md.detail=True;md.skipdata=True;lines=[];references=0
for t,flags,off,vaddr,_,length,_,_ in segments:
 if t!=1 or not flags&1:continue
 insts=list(md.disasm(b[off:off+length],vaddr));hits=[]
 for i,ins in enumerate(insts):
  if ins.mnemonic!='adrp':continue
  reg=ins.operands[0].reg;base=ins.operands[1].imm
  for j in range(i+1,min(i+6,len(insts))):
   nxt=insts[j]
   if nxt.mnemonic=='add' and len(nxt.operands)==3 and nxt.operands[1].reg==reg and nxt.operands[2].type==ARM64_OP_IMM:
    value=base+nxt.operands[2].imm
    if value in targets:hits.append((j,targets[value]))
 for pos,name in hits:
  references+=1
  lines.append('\nSTRING '+name+'\n')
  for ins in insts[max(0,pos-35):pos+35]:lines.append(f'{ins.address:x}: {ins.mnemonic} {ins.op_str}\n')
out=Path('test/_analysis/oct13-official-game/low-rate-event-xrefs.txt');out.write_text(''.join(lines),encoding='utf-8');print({'targets':targets,'references':references,'output':str(out)})
