"""Execute journal notification navigation and original Helper bytecode."""
import argparse, hashlib, json
from pathlib import Path
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from patch_bundle import load
from patch_assistant_notifications import *
from patch_assistant_history import additions as history
from verify_terminal_ipv6_patch import Env,Fn,check_read_caches
from verify_station_lookup import check_frame
from hbc_grow import assert_bodies_mapped
from capture_assistant_typography import literal

class VM:
    def __init__(self,reader):self.reader=reader;self.globals={'JSON':{'parse':Fn(lambda _,s:json.loads(s)),'stringify':Fn(lambda _,v:json.dumps(v))},'setTimeout':Fn(lambda _,*a:None),'Object':{'keys':Fn(lambda _,v:list(v))},'console':{'warn':Fn(lambda _,*a:None)}}
    def prop(self,obj,key):
        if isinstance(obj,dict):return obj.get(key)
        if isinstance(obj,list):return len(obj) if key=='length' else obj[key] if isinstance(key,int) and 0<=key<len(obj) else None
        return None
    def run(self,fid,env,args=()):
        instructions=list(parse_hbc_bytecode(self.reader.function_headers[fid],self.reader));bypos={i.original_pos:n for n,i in enumerate(instructions)}
        r={};pc=0
        for _ in range(3000):
            ins=instructions[pc];pc+=1;n=ins.inst.name;a=[getattr(ins,'arg'+str(k+1)) for k in range(len(ins.inst.operands))]
            if n=='GetEnvironment':
                e=env
                for _ in range(a[1]):e=e.parent
                r[a[0]]=e
            elif n=='CreateEnvironment':r[a[0]]=Env(env)
            elif n=='LoadFromEnvironment':r[a[0]]=r[a[1]].slots.get(a[2])
            elif n=='StoreToEnvironment':r[a[0]].slots[a[1]]=r[a[2]]
            elif n=='LoadParam':r[a[0]]=args[a[1]-1] if len(args)>=a[1] else None
            elif n in ('LoadConstNull','LoadConstUndefined'):r[a[0]]=None
            elif n=='LoadConstTrue':r[a[0]]=True
            elif n=='LoadConstFalse':r[a[0]]=False
            elif n=='LoadConstZero':r[a[0]]=0
            elif n in ('LoadConstUInt8','LoadConstInt'):r[a[0]]=a[1]
            elif n=='LoadConstString':r[a[0]]=self.reader.strings[a[1]]
            elif n in ('GetById','GetByIdShort','TryGetById'):
                assert int(self.reader.string_kinds[a[3]])==1, ('Hermes by-ID requires Identifier kind',fid,a[3])
                r[a[0]]=self.prop(r[a[1]],self.reader.strings[a[3]])
            elif n=='GetByVal':r[a[0]]=self.prop(r[a[1]],r[a[2]])
            elif n in ('PutNewOwnById','PutNewOwnByIdShort'):r[a[0]][self.reader.strings[a[2]]]=r[a[1]]
            elif n=='NewObject':r[a[0]]={}
            elif n in ('NewObjectWithBuffer','NewObjectWithBufferLong'):
                r[a[0]]=dict(zip(literal(self.reader,self.reader.object_keys[a[3]:],a[2]),literal(self.reader,self.reader.object_values[a[4]:],a[2])))
            elif n=='NewArray':r[a[0]]=[None]*a[1]
            elif n=='PutOwnByIndex':r[a[0]][a[2]]=r[a[1]]
            elif n=='PutByVal':r[a[0]][r[a[1]]]=r[a[2]]
            elif n in ('CreateClosure','CreateGeneratorClosure'):
                e,f=r[a[1]],a[2];r[a[0]]=Fn(lambda _,*values,e=e,f=f:self.run(f,e,values))
            elif n.startswith('Call'):r[a[0]]=r[a[1]].call(r[a[2]],[r[k] for k in a[3:]])
            elif n=='GetGlobalObject':r[a[0]]=self.globals
            elif n=='PutById':r[a[0]][self.reader.strings[a[3]]]=r[a[1]]
            elif n=='LoadConstDouble':r[a[0]]=a[1]
            elif n=='Inc':r[a[0]]=r[a[1]]+1
            elif n=='Mul':r[a[0]]=r[a[1]]*r[a[2]]
            elif n=='Add':r[a[0]]=r[a[1]]+r[a[2]]
            elif n=='AddEmptyString':r[a[0]]='undefined' if r[a[1]] is None else str(r[a[1]])
            elif n=='Mov':r[a[0]]=r[a[1]]
            elif n=='Greater':r[a[0]]=r[a[1]]>r[a[2]]
            elif n=='StrictEq':r[a[0]]=type(r[a[1]])==type(r[a[2]]) and r[a[1]]==r[a[2]]
            elif n=='TypeOf':
                v=r[a[1]];r[a[0]]='function' if isinstance(v,Fn) else 'string' if isinstance(v,str) else 'undefined' if v is None else 'object'
            elif n.startswith('J'):
                if n in ('Jmp','JmpLong'):yes=True
                elif n in ('JmpFalse','JmpFalseLong'):yes=not r[a[1]]
                elif n in ('JmpTrue','JmpTrueLong'):yes=bool(r[a[1]])
                elif n in ('JStrictEqual','JStrictEqualLong'):yes=type(r[a[1]])==type(r[a[2]]) and r[a[1]]==r[a[2]]
                elif n in ('JStrictNotEqual','JStrictNotEqualLong'):yes=type(r[a[1]])!=type(r[a[2]]) or r[a[1]]!=r[a[2]]
                elif n=='JNotLessLong':yes=not r[a[1]]<r[a[2]]
                else:raise AssertionError((fid,n))
                if yes:pc=bypos[ins.original_pos+a[0]]
            elif n=='Ret':return r[a[0]]
            else:raise AssertionError((fid,n))
        raise AssertionError('did not settle')

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--bundle',type=Path);args=ap.parse_args()
    if args.bundle:r=load(args.bundle.read_bytes())
    else:
        b=(Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
        d=history(load(b),bytearray(b),set(),[]);d[-20:]=hashlib.sha1(d[:-20]).digest()
        d=additions(load(bytes(d)),d,set(),[]);d[-20:]=hashlib.sha1(d[:-20]).digest();r=load(bytes(d))
        for k,v in STRINGS.items():r.strings[k]=v
        r.strings[30092]='assistantHistoryScope'
    assert_bodies_mapped(r)
    for f in HELPERS+(21967,35695,35702,35728,35743):check_frame(r,f);check_read_caches(r,f)
    vm=VM(r);module=Env();owner=Env(module);calls=[];listeners=[]
    pending={'eventId':'e2','projectId':'p2','historyScope':'hub:acct:p2'}
    native={'nPending':Fn(lambda _:json.dumps(pending) if pending else ''),
            'assistantHistoryScope':Fn(lambda _,p:'hub:acct:'+str(p)),
            'nOpened':Fn(lambda _,e:calls.append(('ack',e)))}
    def add_listener(_,name,callback):
        token={'name':name,'callback':callback};listeners.append(token)
        return {'remove':Fn(lambda __:listeners.remove(token) if token in listeners else None)}
    react_native={'NativeModules':{'sta':native},
                  'DeviceEventEmitter':{'addListener':Fn(add_listener)}}
    module.slots[0]=Fn(lambda _,mid:react_native if mid==18 else None)
    owner.slots.update({5:{'projectId':'p1'},8:[{'projectId':'p1'},{'projectId':'p2'}],
                       7:{'navigate':Fn(lambda _,v:calls.append(('navigate',v)))},
                       17:Fn(lambda _,v:calls.append(('set',v))),18:{'current':None}})
    # Live hub.13 regression: a present sta object with a missing synchronous
    # export must not throw from the global provider mount on unrelated pages.
    pending_method=native.pop('nPending')
    vm.run(PROVIDER_TICK,owner);assert calls==[]
    native['nPending']=42
    vm.run(PROVIDER_TICK,owner);assert calls==[]
    native['nPending']=pending_method
    vm.run(PROVIDER_TICK,owner);assert calls==[('set',{'projectId':'p2'})],calls
    owner.slots[5]={'projectId':'p2'};vm.run(PROVIDER_TICK,owner)
    assert calls[-1]==('navigate','Helper');vm.run(PROVIDER_TICK,owner);assert len(calls)==2
    owner.slots[8].append({'projectId':'p2'});owner.slots[18]['current']=None
    vm.run(PROVIDER_TICK,owner);assert len(calls)==2,'ambiguous projects must not navigate'
    owner.slots[8]=[{'projectId':'p3'}];vm.run(PROVIDER_TICK,owner);assert len(calls)==2
    pending['historyScope']='other:account:p2';owner.slots[8]=[{'projectId':'p2'}]
    vm.run(PROVIDER_TICK,owner);assert len(calls)==2,'wrong account scope must not navigate'
    pending['historyScope']='hub:acct:p2'
    chat=Env(module)
    msgs=[{'uuid':'old','messageDetail':'retained'},{'uuid':'e2','messageDetail':'alert'}]
    chat.slots.update({9:msgs,10:Fn(lambda _,v:calls.append(('messages',v))),23:'p2',
                      35:{'scope':'hub:acct:p2','ready':False},36:'hub:acct:p2',
                      18:{'current':{'scrollToIndex':Fn(lambda _,v:calls.append(('scroll',v))),
                                     'scrollToOffset':Fn(lambda _,v:calls.append(('offset',v))),
                                     'scrollToEnd':Fn(lambda _,*options:calls.append(('end',)))}}})
    module.slots[0]=Fn(lambda _,mid:react_native if mid==18 else None)
    saved_pending=pending.copy();pending.clear()
    provider_cleanup=vm.run(PROVIDER_EFFECT,owner)
    assert [x['name'] for x in listeners]==['HubNoticeOpen'],listeners
    chat_cleanup=vm.run(CHAT_EFFECT,chat)
    assert [x['name'] for x in listeners]==['HubNoticeOpen','HubNoticeOpen','HubNoticeMessages'],listeners
    provider_cleanup.call(None,[]);chat_cleanup.call(None,[])
    assert listeners==[],'both subscriptions must be removed on unmount'
    pending.update(saved_pending)
    native['nMerge']=Fn(lambda _,p,s:calls.append(('merge',p,json.loads(s))) or '')
    vm.run(CHAT_TICK,chat);assert len(calls)==2,'must not merge before hydrate'
    chat.slots[35]['ready']=True;vm.run(CHAT_TICK,chat)
    assert calls[-2][0]=='merge' and calls[-1][0]=='scroll' and calls[-1][1]['index']==1,calls
    assert not any(v[0]=='ack' for v in calls),'scroll call must not acknowledge'
    native['nMerge']=Fn(lambda _,p,s:json.dumps(json.loads(s)+[{'uuid':'new','messageDetail':'new alert'}]))
    vm.run(CHAT_TICK,chat)
    assert calls[-1][0]=='messages' and calls[-1][1][:2]==msgs and len(calls[-1][1])==3
    assert not any(v[0]=='ack' for v in calls),'merged state must wait for layout/viewability'
    for _ in range(5):vm.run(SCROLL_FAILED,chat,({'averageItemLength':80,'index':1},))
    assert chat.slots[35]['count']==3,'unmeasured list retries must be bounded'
    vm.run(VIEWABLE,chat,({'viewableItems':[{'item':{'uuid':'old'}}]},));assert not any(v[0]=='ack' for v in calls)
    vm.run(VIEWABLE,chat,({'viewableItems':[{'item':{'uuid':'e2','historyScope':'hub:acct:p2'}}]},));assert calls[-1]==('ack','e2')
    chat.slots[35]['scope']='hub:acct:p3';before=len(calls)
    vm.run(VIEWABLE,chat,({'viewableItems':[{'item':{'uuid':'e2','historyScope':'hub:acct:p2'}}]},));assert len(calls)==before,'stable callback must use live scope ref'
    chat.slots[35].update({'scope':'hub:acct:p2','ready':True})
    chat.slots[33]=Fn(lambda _,q,t:calls.append(('query',q,t)))
    pending.update({'eventId':'query:once','question':'测网速'})
    before=len(calls);vm.run(35702,chat);vm.run(35702,chat)
    assert calls[before:]==[('query','测网速','chat'),('ack','query:once')],calls[before:]
    pending.update({'eventId':'query:two','historyScope':'other:p2'})
    before=len(calls);vm.run(35702,chat);assert len(calls)==before
    # A deliberate command scroll is independent of any pending notification.
    before=len(calls);vm.run(AUTOSCROLL,chat);assert calls[before:]==[('end',)]
    chat.slots[35]['ready']=False;before=len(calls);vm.run(AUTOSCROLL,chat);assert len(calls)==before
    chat.slots[35].update(ready=True,scope='another-project');vm.run(AUTOSCROLL,chat);assert len(calls)==before
    chat.slots[35]['scope']='hub:acct:p2';chat.slots[18]['current']=None
    vm.run(AUTOSCROLL,chat);assert len(calls)==before,'unmounted page must not scroll'
    send=list(parse_hbc_bytecode(r.function_headers[35743],r))
    assert len([i for i in send if i.inst.name=='CreateClosure' and i.arg3==AUTOSCROLL])==2
    print('PASS notification project commit, ambiguity/account isolation, hydrate gate, original chat merge, bounded scroll retry and visible-message ack')

if __name__=='__main__':main()
