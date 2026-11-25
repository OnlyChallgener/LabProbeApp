"""Validate frame-relative operands that Call/Construct do not encode."""
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

IMPLICIT={'Call','CallLong','Construct','ConstructLong'}

def verify(original,patched):
    checked=[]
    for fid,h in enumerate(patched.function_headers):
        old=original.function_headers[fid]
        if h.frameSize==old.frameSize:continue
        ins=list(parse_hbc_bytecode(h,patched))
        calls=[(k,i) for k,i in enumerate(ins) if i.inst.name in IMPLICIT]
        if not calls:continue
        old_calls=[i for i in parse_hbc_bytecode(old,original) if i.inst.name in IMPLICIT]
        assert len(calls)==len(old_calls),('changed implicit call count',fid)
        for (k,i),prior in zip(calls,old_calls):
            assert i.inst.name==prior.inst.name and i.arg3==prior.arg3,('changed implicit call ABI',fid)
            # Hermes' six frame header registers follow the this slot.
            expected={(h.frameSize-7-n,old.frameSize-7-n) for n in range(i.arg3)}
            copies=ins[k-i.arg3:k]
            actual={(x.arg1,x.arg2) for x in copies if x.inst.name in ('Mov','MovLong')}
            assert actual==expected,('implicit outgoing arguments not rebased',fid,i.original_pos,old.frameSize,h.frameSize,expected,actual)
        checked.append(fid)
    return checked
