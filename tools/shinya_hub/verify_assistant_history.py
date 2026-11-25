"""Static + actual-instruction regression checks for scoped assistant history.

These checks execute the patched storage/load/cleanup instructions with fake
AsyncStorage and React state. They do not claim a phone or installed APK test.
"""
import argparse
import json
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hbc_grow import assert_bodies_mapped
from patch_bundle import load
from patch_assistant_history import FIDS, BASELINE
from verify_terminal_ipv6_patch import check_read_caches


class VM:
    def __init__(self, reader, fid, env, globals=None):
        self.reader = reader; self.fid = fid; self.env = env
        self.global_ = globals or {}; self.r = {}; self.pc = 0
        self.code = {i.original_pos: i for i in parse_hbc_bytecode(reader.function_headers[fid], reader)}
        self.resume = None; self.completed = False; self.next_resume = (None, False)

    def run(self, value=None):
        if self.resume is not None:
            self.pc = self.resume; self.resume = None; self.next_resume = (value, False)
        for _ in range(1000):
            if self.pc == getattr(self,'stop_pc',None): return
            i = self.code[self.pc]; n = i.inst.name
            a = [getattr(i, 'arg'+str(k+1)) for k in range(len(i.inst.operands))]
            pc = self.pc; nxt = pc + 1 + len(bytes(i.inst.structure())); r = self.r
            if n in ('StartGenerator',): pass
            elif n == 'ResumeGenerator': r[a[0]], r[a[1]] = self.next_resume
            elif n == 'SaveGenerator': self.resume = pc + a[0]
            elif n == 'CompleteGenerator': self.completed = True
            elif n == 'GetEnvironment': r[a[0]] = self.env[a[1]]
            elif n == 'LoadFromEnvironment': r[a[0]] = r[a[1]].get(a[2])
            elif n == 'StoreToEnvironment': r[a[0]][a[1]] = r[a[2]]
            elif n == 'GetGlobalObject': r[a[0]] = self.global_
            elif n in ('GetById', 'GetByIdShort', 'TryGetById'):
                r[a[0]] = r[a[1]].get(self.reader.strings[a[3]])
            elif n in ('PutById', 'PutNewOwnById', 'PutNewOwnByIdShort'):
                sid = a[3] if n == 'PutById' else a[2]
                r[a[0]][self.reader.strings[sid]] = r[a[1]]
            elif n == 'LoadConstUndefined' or n == 'LoadConstNull': r[a[0]] = None
            elif n == 'LoadConstString': r[a[0]] = self.reader.strings[a[1]]
            elif n == 'LoadConstTrue': r[a[0]] = True
            elif n == 'LoadConstFalse': r[a[0]] = False
            elif n == 'NewObject': r[a[0]] = {}
            elif n == 'NewArray': r[a[0]] = [None] * a[1]
            elif n == 'Mov': r[a[0]] = r[a[1]]
            elif n.startswith('Call'):
                r[a[0]] = r[a[1]](*[r[v] for v in a[3:]])
            elif n == 'Ret': return r[a[0]]
            elif n in ('Jmp', 'JmpLong'): nxt = pc + a[0]
            elif n in ('JmpTrue', 'JmpTrueLong', 'JmpFalse', 'JmpFalseLong'):
                yes = bool(r[a[1]])
                if ('False' in n) != yes: nxt = pc + a[0]
            elif n in ('JStrictEqual', 'JStrictEqualLong', 'JStrictNotEqual', 'JStrictNotEqualLong'):
                x,y=r[a[1]],r[a[2]]
                yes = x is y if isinstance(x,(dict,list)) else type(x)==type(y) and x==y
                if ('NotEqual' in n) != yes: nxt = pc + a[0]
            else: raise AssertionError((self.fid, hex(pc), n))
            self.pc = nxt
        raise AssertionError('instruction execution did not settle')


def scenarios(reader):
    stored = {}; seen = []; writes = []; guesses = []
    storage = {'getMessageByAccountId': lambda key: (seen.append(key), stored.get(key))[1],
               'saveMessageByAccountId': lambda key, body: (writes.append((key,body)), stored.__setitem__(key,body))[1]}
    module = {15: {'default': {'helper': storage}}}
    clear_result = [True]; journal_clears = []
    module[6] = {'NativeModules': {'sta': {'nClear': lambda project:
        (journal_clears.append(project), clear_result[0])[1]}}}
    ref = {'scope':'HUB-A/user-A/project-A', 'ready':False,'current':{}}
    helper = {4:{'accountId':'legacy-account'}, 9:[], 10:lambda values: helper.__setitem__(9,values),
              11:None, 17:{'current':None},
              31:lambda *args: (guesses.append(args),helper.__setitem__(9,[{'type':'guess'}]))[1],
              35:ref, 36:ref['scope']}
    effect = {2:ref,3:ref['scope'],4:ref['current']}
    global_ = {'JSON':{'parse':json.loads,'stringify':json.dumps}}
    def save(): VM(reader,35722,{0:helper,1:module},global_).run()
    def loader(): return VM(reader,35708,{2:effect,3:helper,4:module},global_)
    save(); assert not writes, 'initial empty state must not overwrite storage'
    history = [{'message':'yesterday'}, {'message':'last year'}, {'message':'today'}] * 500
    stored[ref['scope']] = json.dumps(history)
    vm=loader(); value=vm.run(); assert seen == [effect[3]]
    save(); assert not writes, 'awaiting storage must remain read-only'
    vm.run(value); assert helper[9] == history and ref['ready'] is True
    save(); assert json.loads(writes[-1][1]) == history
    # A second project cannot read or overwrite A. Late A responses are ignored.
    old=loader(); old_result=old.run()
    ref.update(scope='HUB-A/user-A/project-B',ready=False,current={})
    helper[36]=ref['scope']; helper[9]=[]
    old.run(old_result); assert helper[9] == [] and ref['ready'] is False
    VM(reader,35721,{0:effect,1:helper},global_).run()
    assert ref['scope'] == helper[36], 'old cleanup must not invalidate B'
    effect={2:ref,3:ref['scope'],4:ref['current']}
    vm=loader(); value=vm.run(); assert value is None
    vm.run(value); assert guesses and helper[9] == [{'type':'guess'}] and ref['ready'] is True
    # The retained manual clear action calls the setter and the original guess
    # helper; its following save affects only B, retaining A's full history.
    pending=loader();pending_value=pending.run()
    helper[23]='project-B'
    clear=VM(reader,35728,{1:module},global_)
    sites=[i for i in clear.code.values() if i.inst.name=='LoadFromEnvironment' and i.arg1==60 and i.arg3==35]
    assert len(sites)==1
    clear.pc=sites[0].original_pos;clear.r[5]=helper
    clear.stop_pc=next(i.original_pos for i in clear.code.values()
                       if i.original_pos>clear.pc and i.inst.name=='LoadFromEnvironment' and i.arg3==10)
    stale=VM(reader,35728,{1:module},global_);stale.pc=clear.pc;stale.stop_pc=clear.stop_pc
    stale.r[5]=dict(helper)
    stale.r[5][36]='HUB-A/user-A/project-A'
    token=ref['current'];stale.run()
    assert ref['current'] is token, 'stale project clear must not clear current project'
    has_notifications = any(reader.strings[i.arg4]=='nClear' for i in clear.code.values()
                            if i.inst.name in ('GetById','GetByIdShort'))
    if has_notifications:
        failed=VM(reader,35728,{1:module},global_);failed.pc=clear.pc;failed.stop_pc=clear.stop_pc;failed.r[5]=helper
        clear_result[0]=False;old_messages=helper[9];failed.run()
        assert failed.pc!=failed.stop_pc and helper[9] is old_messages, 'failed journal clear must stop before UI clear'
        clear_result[0]=True
    clear.run()
    if has_notifications:assert journal_clears==['project-B','project-B']
    helper[10]([]); helper[31]('', 'other', 'guess'); save()
    pending.run(pending_value)
    assert helper[9]==[{'type':'guess'}], 'pending hydration must not undo manual clear'
    assert json.loads(stored[helper[36]]) == [{'type':'guess'}]
    assert json.loads(stored['HUB-A/user-A/project-A']) == history
    # Failed/missing native identity never loads or saves the legacy account.
    # Even A -> B -> A cannot let the first A request hydrate the second A.
    ref.update(scope=old.env[2][3],ready=False,current={});helper[36]=ref['scope'];helper[9]=[]
    old.completed=False;old.resume=next(i.original_pos for i in old.code.values() if i.inst.name=='ResumeGenerator' and i.original_pos>10)
    old.run(old_result);assert helper[9]==[] and ref['ready'] is False
    ref.update(scope='',ready=False,current={});helper[36]='';effect={2:ref,3:'',4:ref['current']}
    before=len(seen);vm=loader();vm.run();save();assert len(seen)==before and vm.completed
    return 12


def verify(reader):
    original=load(BASELINE.read_bytes())
    assert reader.header.version==90
    assert_bodies_mapped(reader)
    for fid in FIDS:
        h=reader.function_headers[fid]
        check_read_caches(reader,fid)
        instructions=list(parse_hbc_bytecode(h,reader)); starts={i.original_pos for i in instructions}
        write_slots={};maxreg=-1;maxargs=0
        for i in instructions:
            for k,operand in enumerate(i.inst.operands):
                value=getattr(i,'arg'+str(k+1))
                if operand.operand_type.name.startswith('Reg'):
                    maxreg=max(maxreg,value)
                    assert value < h.frameSize,(fid,value,h.frameSize)
            if i.inst.name.startswith('Call') and i.inst.name[4:].isdigit():
                maxargs=max(maxargs,int(i.inst.name[4:]))
            if i.inst.name=='PutById':
                assert i.arg3<=h.highestWriteCacheIndex
                assert write_slots.setdefault(i.arg3,i.arg4)==i.arg4,(fid,'write cache collision')
            if i.inst.operands and 'Addr' in i.inst.operands[0].operand_type.name:
                assert i.original_pos+i.arg1 in starts,(fid,i.original_pos)
        assert maxreg+6+maxargs < h.frameSize,(fid,'outgoing call frame',maxreg,maxargs,h.frameSize)
        if fid in (35708,35728):
            assert h.hasExceptionHandler
            for item in reader.function_id_to_exc_handlers[fid]:
                assert item.start in starts and item.end in starts and item.target in starts
                assert item.start < item.end <= item.target
                assert instructions[[j.original_pos for j in instructions].index(item.target)].inst.name=='Catch'
        else: assert not h.hasExceptionHandler
    from patch_assistant_notifications import CHAT_EFFECT
    helper_instructions=list(parse_hbc_bytecode(reader.function_headers[35695],reader))
    notification_hook=any(i.inst.name=='CreateClosure' and i.arg3==CHAT_EFFECT for i in helper_instructions)
    # The integrated notification hook adds exactly one stable viewability
    # callback ref at env37; history's original env35/36 remain unchanged.
    assert reader.function_headers[35695].environmentSize==(38 if notification_hook else 37)
    assert reader.function_headers[35705].environmentSize==5
    # Preserve clear shortcut, native phone ping, socket receive and message UI.
    for fid in (35712,8561):
        a=reader.function_headers[fid];b=original.function_headers[fid]
        reader.file_buffer.seek(a.offset); original.file_buffer.seek(b.offset)
        assert reader.file_buffer.read(a.bytecodeSizeInBytes)==original.file_buffer.read(b.bytecodeSizeInBytes),fid
    return scenarios(reader)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('bundle',type=Path);args=parser.parse_args()
    r=load(args.bundle.read_bytes())
    count=verify(r)
    print(f'PASS: scoped history, {count} behavioral cases; frame/cache/branch/EH + retained clear/ping/storage bodies')
