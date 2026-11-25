"""Execute the patched progress bytecode fragment using real bundle opcodes."""
import os
import sys
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from patch_bundle import load

path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(os.environ['TEMP']) / 'shinya-legacy-progress-test.bundle'
reader = load(path.read_bytes())
ins = {x.original_pos:x for x in parse_hbc_bytecode(reader.function_headers[24065], reader)}


def progress(response, previous):
    regs = {1:{'progressInfo':{'router':{'progress':previous}}}, 4:None,
            7:'router', 20:response}
    pc = 0x19c
    while pc not in (0x1c1, 0x228):
        i=ins[pc]
        name=i.inst.name
        args=[getattr(i,'arg'+str(n)) for n in range(1,5) if hasattr(i,'arg'+str(n))]
        next_pc = min(pos for pos in ins if pos > pc)
        if name == 'GetById':
            obj=regs.get(args[1]);key=reader.strings[args[3]]
            regs[args[0]]=obj.get(key) if isinstance(obj,dict) else None
        elif name == 'GetByVal':
            regs[args[0]]=regs[args[1]][regs[args[2]]]
        elif name == 'JNotEqual':
            if regs.get(args[1]) != regs.get(args[2]): next_pc=pc+args[0]
        elif name == 'LoadConstZero': regs[args[0]]=0
        elif name == 'JLessLong':
            if regs[args[1]] < regs[args[2]]: next_pc=pc+args[0]
        elif name == 'Jmp': next_pc=pc+args[0]
        else: raise AssertionError((hex(pc),name))
        pc=next_pc
    return ('offline_after_download' if regs[8]==-2 else 'failed') if pc==0x228 else regs[8]


assert progress({'progress':37}, 9)==37
assert progress({'progress':0}, 9)==0
assert progress({'progress':None}, 9)==9
assert progress({}, 9)==9
assert progress('request failed', 9)==9
assert progress({'progress':-1}, 9)=='failed'
assert progress({'progress':-2}, 9)=='offline_after_download'
def dispatch(marker):
    regs={8:marker};pc=0x228
    while pc!=0x266:
        i=ins[pc];name=i.inst.name
        args=[getattr(i,'arg'+str(n)) for n in range(1,5) if hasattr(i,'arg'+str(n))]
        next_pc=min(pos for pos in ins if pos>pc)
        if name=='LoadConstInt': regs[args[0]]=args[1]
        elif name=='JStrictEqual':
            if regs[args[1]]==regs[args[2]]:next_pc=pc+args[0]
        elif name=='Jmp':next_pc=pc+args[0]
        elif name=='LoadConstTrue':regs[args[0]]=True
        elif name=='CompleteGenerator':pass
        elif name=='Ret':return regs[args[0]]
        else:raise AssertionError((hex(pc),name))
        pc=next_pc
    return 'failed'

assert dispatch(-2) is True
assert dispatch(-1)=='failed'
assert dispatch(30)=='failed'
assert ins[0xcc].inst.name=='Mov' and ins[0xcc].arg1==20 and ins[0xcc].arg2==1
assert ins[0x69].inst.name=='LoadConstUInt8' and ins[0x69].arg2==30
timer=[x for x in parse_hbc_bytecode(reader.function_headers[24074],reader) if x.inst.name!='Unreachable']
assert [x.inst.name for x in timer]==['LoadConstUndefined','Ret']
assert ins[0x228].inst.name=='LoadConstInt' and ins[0x228].arg2==-2
caller={x.original_pos:x for x in parse_hbc_bytecode(reader.function_headers[24073],reader)}
assert caller[0xcc].inst.name=='LoadConstInt' and caller[0xcc].arg2==1000
print('PASS original upgrade progress uses measured values and never increments an absent sample')
