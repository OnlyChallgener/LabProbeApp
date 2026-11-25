"""Run the actual emitted MACC menu block and captured row click bodies."""
import argparse
import zipfile
from pathlib import Path
from patch_bundle import load
from patch_macc_entry import MENU_FID, PRESS_FID, BLOCK_END, JMPFALSE_POS, METHOD_SID, MACC_SID
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from verify_station_notify import literal
from verify_terminal_ipv6_patch import check_read_caches
from verify_station_lookup import check_frame


def execute(reader, fid, env, *, start=0, stop=None, registers=None):
    code={i.original_pos:i for i in parse_hbc_bytecode(reader.function_headers[fid],reader)}
    r=dict(registers or {});pc=start
    for _ in range(500):
        if pc==stop:return r
        i=code[pc];n=i.inst.name
        a=[getattr(i,'arg'+str(k+1)) for k in range(len(i.inst.operands))]
        nxt=pc+1+len(bytes(i.inst.structure()))
        if n=='GetEnvironment':r[a[0]]=env[a[1]]
        elif n=='LoadFromEnvironment':r[a[0]]=r[a[1]].get(a[2])
        elif n in ('LoadConstNull','LoadConstUndefined'):r[a[0]]=None
        elif n=='LoadConstString':r[a[0]]=reader.strings[a[1]]
        elif n in ('GetById','GetByIdShort'):r[a[0]]=r[a[1]].get(reader.strings[a[3]])
        elif n=='TypeOf':r[a[0]]='function' if callable(r[a[1]]) else 'undefined' if r[a[1]] is None else 'object'
        elif n=='NewObjectWithBufferLong':r[a[0]]=dict(zip(literal(reader,reader.object_keys,a[3],a[2]),literal(reader,reader.object_values,a[4],a[2])))
        elif n=='PutNewOwnByIdShort':r[a[0]][reader.strings[a[2]]]=r[a[1]]
        elif n=='PutByVal':r[a[0]][r[a[1]]]=r[a[2]]
        elif n=='PutOwnByIndex':r[a[0]][a[2]]=r[a[1]]
        elif n.startswith('Call'):r[a[0]]=r[a[1]](r[a[2]],*[r[k] for k in a[3:]])
        elif n.startswith('J'):
            if n in ('Jmp','JmpLong'):yes=True
            elif n in ('JmpFalse','JmpFalseLong'):yes=not r[a[1]]
            elif n in ('JStrictNotEqual','JStrictNotEqualLong'):yes=r[a[1]]!=r[a[2]]
            else:raise AssertionError((fid,pc,n))
            if yes:nxt=pc+a[0]
        elif n=='Ret':return r[a[0]]
        else:raise AssertionError(('reachable invalid instruction',fid,pc,n))
        pc=nxt
    raise AssertionError(('execution did not settle',fid,pc))


def check(reader):
    assert reader.strings[MACC_SID]=='MACC 账号'
    assert reader.strings[METHOD_SID]=='openMaccAccount'
    for fid in (MENU_FID,PRESS_FID,17938,19816):
        check_frame(reader,fid);check_read_caches(reader,fid)
    for flag in (False,True,None):
        rows=[{'type':'remote_control'},None,{'type':'about_us'}]
        regs=execute(reader,MENU_FID,{},start=JMPFALSE_POS,stop=BLOCK_END,
                     registers={2:rows,5:'old badge',9:flag})
        assert regs[5] is None
        assert rows[0]['type']=='remote_control' and rows[2]['type']=='about_us'
        assert rows[1]['title']=='MACC 账号' and rows[1]['type']=='openMaccAccount'
        assert rows[1]['icon']=='account_share'
        assert len({x['type'] for x in rows})==3,'React list keys must be unique'
    # Exercise the real Icon switch: this branch was previously stolen by
    # STUN, so checking only the row's icon string missed the blank icon.
    from verify_assistant_notification_navigation import VM
    from verify_terminal_ipv6_patch import Env, Fn
    vm=VM(reader); jsx=Fn(lambda _,kind,props,*key:kind.call(None,[props]) if isinstance(kind,Fn) else {'kind':kind,'props':props})
    omit=Fn(lambda _,props,keys:{k:v for k,v in props.items() if k not in keys})
    vm.globals['Object']['assign']=Fn(lambda _,dst,src:dict(dst,**src))
    svg=Env();svg.slots.update({0:Fn(lambda _,dep:{'jsx':jsx} if dep==3 else {'SvgXml':'SvgXml'}),1:{3:3,4:4},2:{'default':omit},3:['size','color']})
    icon_env=Env();icon_env.slots.update({0:Fn(lambda _,dep:{'jsx':jsx}),1:{126:126},2:{'default':omit},126:['name'],22:{'default':Fn(lambda _,props:vm.run(19816,svg,(props,)))}})
    rendered=vm.run(17938,icon_env,({'name':'account_share','size':24,'style':{'marginRight':8}},))
    assert rendered['kind']=='SvgXml' and rendered['props']['width']==24
    assert rendered['props']['style']=={'marginRight':8}, 'preserve original menu spacing'
    assert rendered['props']['xml']==reader.strings[1551] and '<path' in rendered['props']['xml']
    assert 'stroke="#444"' in rendered['props']['xml']
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    assert any(i.inst.name=='CreateClosure' and i.arg3==20053 for i in parse_hbc_bytecode(reader.function_headers[17938],reader)), 'STUN SVG dispatch must remain intact'
    calls=[]
    native={'openMaccAccount':lambda this:calls.append(('macc',this))}
    dispatcher=lambda this,kind:calls.append(('original',kind))
    module={3:{'NativeModules':{'sta':native}}}
    for kind in ('remote_control','about_us','satisfy_questionaire','feedback'):
        execute(reader,PRESS_FID,{0:{0:{'type':kind}},1:{0:dispatcher},2:module})
        assert calls[-1]==('original',kind)
    execute(reader,PRESS_FID,{0:{0:{'type':'openMaccAccount'}},1:{0:dispatcher},2:module})
    assert calls[-1]==('macc',native),'NativeModules must come from RN import, with sta receiver'
    count=len(calls)
    for rn in ({},{'NativeModules':{}},{'NativeModules':{'sta':{}}},{'NativeModules':{'sta':{'openMaccAccount':7}}}):
        execute(reader,PRESS_FID,{0:{0:{'type':'openMaccAccount'}},1:{0:dispatcher},2:{3:rn}})
    execute(reader,PRESS_FID,{0:{0:None},1:{0:dispatcher},2:module})
    execute(reader,PRESS_FID,{0:{0:{'type':'about_us'}},1:{0:None},2:module})
    assert len(calls)==count
    print('PASS MACC menu settles for true/false/missing version flag; unique keys; original menu dispatch; native click and missing bridge guards')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('artifact',type=Path);args=parser.parse_args()
    if args.artifact.suffix=='.apk':
        with zipfile.ZipFile(args.artifact) as z:data=z.read('assets/index.android.bundle')
    else:data=args.artifact.read_bytes()
    check(load(data))
