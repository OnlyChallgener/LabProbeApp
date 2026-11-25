"""Execute original assistant DiagnoseDetail category and typed leaf bytecode."""
import argparse,hashlib,json,math
from pathlib import Path
from patch_bundle import load
from patch_assistant_diagnosis_detail import additions,FIDS
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from verify_terminal_ipv6_patch import Fn,Env,check_read_caches
from verify_station_lookup import check_frame
from verify_assistant_cards import walk,flatten_style
from capture_assistant_typography import literal
class Undefined:
    def __repr__(self):return 'undefined'
U=Undefined()
def truth(v):return not (v is U or v is None or v is False or isinstance(v,(int,float)) and (v==0 or math.isnan(v)) or isinstance(v,str) and v=='')
def jsstr(v):return 'undefined' if v is U else 'null' if v is None else str(v)
def jsnum(v):
    if v is U:return float('nan')
    if v is None:return 0
    try:return float(v)
    except (ValueError,TypeError):return float('nan')
class VM:
    def __init__(self,r):
        self.reader=r;self.leaf_calls=[]
        self.globals={'JSON':{'parse':Fn(lambda _,s:json.loads(s))},'Object':{'assign':Fn(lambda _,target,*sources:dict(target,**{k:v for src in sources for k,v in src.items()}))},'Number':Fn(lambda _,v:jsnum(v)),'Math':{'round':Fn(lambda _,v:round(jsnum(v)) if math.isfinite(jsnum(v)) else float('nan'))}}
        self.module=Env();self.module.slots.update({i:{'default':'OriginalComponent'} for i in range(32)});self.component=Env(self.module)
        self.styles={s:{} for s in r.strings};self.styles.update(commonDetailtext={'fontSize':14,'color':'#29CC6A'},problemColor={'color':'#F29938'})
        self.utils={k:Fn(lambda _,v:{}) for k in ['ml','mt','mv']}
        self.jsx=Fn(lambda _,kind,props,*key:kind.call(None,[props]) if isinstance(kind,Fn) else {'kind':kind,'props':props})
        rn={k:k for k in ['View','Text','Image','ScrollView','TouchableWithoutFeedback']}
        react={'useState':Fn(lambda _,v:[v,Fn(lambda _,*a:None)])}
        def require(_,i):
            if i==16:return {'jsx':self.jsx,'jsxs':self.jsx,'Fragment':'Fragment'}
            if i==18:return {'Flex':'Flex'}
            if i==14:return {'useThemeStyles':Fn(lambda _,*a:{'styles':self.styles,'stylesUtils':self.utils})}
            if i==17:return {'DiagnosisType':{k:k for k in ['netStatus','signalStatus','interfereStatus','loadStatus','iotStatus']},'ProblemType':dict(normal='normal',s2gChNoise='24g_interfere',iotShake='iot_shake',unstableLink='link_instability',notSatisfiedLink='wan_speed_not_full',apReboot='ap_reboot',sta5gLink2g='5g_connect_24g',badCoverAndBadQoe='signal_weak',highApLoad='high_ap_load',highStaLoad='high_sta_load',staPing='sta_ping')}
            return {'default':{'getRssiLevel':Fn(lambda _,*a:2),'getSignalStrengthIcon':Fn(lambda _,*a:'signal-icon')},'getRssiLevel':Fn(lambda _,*a:2),'getSignalStrengthIcon':Fn(lambda _,*a:'signal-icon')}
        self.module.slots.update({0:Fn(require),1:list(range(32)),2:{'default':Fn(lambda _,a,*b:a)},4:react,5:rn,6:{'default':{}},11:{k:80 for k in ['cpuUse','memUse','cpuTemp','onlineCnt','use24','use5']},12:{'1':'normal-badge','2':'warning-badge'}})
        self.module.slots[13]=Fn(lambda _,p:self.run(33278,self.module,[p]))
        self.component.slots.update({0:self.styles,1:{'colors':{s:'#999999' for s in r.strings}},2:self.utils})
        for slot,fid in {5:33245,12:33257,13:33258,15:33264,16:33265,17:33266,18:33268,19:33270,20:33272,21:33273,22:33274,23:33275}.items():
            self.component.slots[slot]=Fn(lambda _,*a,f=fid:self.run(f,self.component,a))
        for slot,fid in {6:33246,7:33248,8:33250,9:33252,10:33254,11:33256}.items():
            self.component.slots[slot]=Fn(lambda _,*a,f=fid:self.run(f,self.component,a))
    def prop(self,o,k):
        if o is U or o is None:raise TypeError('cannot read '+str(k)+' of '+str(o))
        if isinstance(o,dict):return o.get(str(k) if isinstance(k,(int,float)) else k,U)
        if isinstance(o,list):
            if k=='length':return len(o)
            if k=='map':return Fn(lambda _,f:[f.call(None,[v,i]) for i,v in enumerate(o)])
            if isinstance(k,int):return o[k] if 0<=k<len(o) else U
        if isinstance(o,(int,float)) and not isinstance(o,bool) and k=='toFixed':return Fn(lambda _,n:f'{o:.{n}f}')
        return U
    def run(self, fid, env, args=(), *, initial=None, start=None, stop=None):
        if fid in (33258,33264,33265,33266,33267,33268,33269,33270,33271,33272,33273,33274,33275,33276):self.leaf_calls.append(fid)
        instructions = list(parse_hbc_bytecode(self.reader.function_headers[fid], self.reader))
        positions = {i.original_pos:index for index,i in enumerate(instructions)}
        r, pc = dict(initial or {}), positions[start] if start is not None else 0
        for _ in range(10000):
            i = instructions[pc]
            if i.original_pos == stop:return r
            n = i.inst.name
            a = [getattr(i,'arg'+str(k+1)) for k in range(len(i.inst.operands))]
            pc += 1
            if n == 'CreateEnvironment': r[a[0]] = Env(env)
            elif n == 'GetEnvironment':
                value = env
                for _ in range(a[1]): value = value.parent
                r[a[0]] = value
            elif n == 'LoadFromEnvironment': r[a[0]] = r[a[1]].slots.get(a[2])
            elif n == 'StoreToEnvironment': r[a[0]].slots[a[1]] = r[a[2]]
            elif n == 'LoadParam': r[a[0]] = args[a[1]-1] if len(args)>=a[1] else U
            elif n=='LoadConstUndefined':r[a[0]]=U
            elif n=='LoadConstNull':r[a[0]]=None
            elif n == 'LoadConstTrue': r[a[0]] = True
            elif n == 'LoadConstFalse': r[a[0]] = False
            elif n in ('LoadConstInt','LoadConstUInt8','LoadConstDouble'): r[a[0]] = a[1]
            elif n == 'LoadConstZero': r[a[0]] = 0
            elif n == 'LoadConstString': r[a[0]] = self.reader.strings[a[1]]
            elif n == 'GetGlobalObject': r[a[0]] = self.globals
            elif n in ('GetById','GetByIdShort','TryGetById'):
                try:r[a[0]]=self.prop(r[a[1]],self.reader.strings[a[3]])
                except TypeError as err:raise TypeError((fid,hex(i.original_pos),str(err))) from err
            elif n == 'GetByVal': r[a[0]] = self.prop(r[a[1]],r[a[2]])
            elif n in ('PutNewOwnById','PutNewOwnByIdShort'): r[a[0]][self.reader.strings[a[2]]] = r[a[1]]
            elif n == 'NewObject': r[a[0]] = {}
            elif n in ('NewObjectWithBuffer','NewObjectWithBufferLong'):
                r[a[0]]=dict(zip(literal(self.reader,self.reader.object_keys[a[3]:],a[2]),
                                 literal(self.reader,self.reader.object_values[a[4]:],a[2])))
            elif n in ('PutById','PutByIdShort'):r[a[0]][self.reader.strings[a[3]]]=r[a[1]]
            elif n in ('NewArrayWithBuffer','NewArrayWithBufferLong'):r[a[0]]=literal(self.reader,self.reader.arrays[a[3]:],a[2])
            elif n == 'NewArray': r[a[0]] = [None]*a[1]
            elif n == 'PutOwnByIndex': r[a[0]][a[2]] = r[a[1]]
            elif n == 'CreateClosure':
                closure_env, closure_fid = r[a[1]], a[2]
                r[a[0]] = Fn(lambda _, *values, e=closure_env, f=closure_fid: self.run(f,e,values))
            elif n in ('Call1','Call2','Call3','Call4'):
                r[a[0]] = r[a[1]].call(r[a[2]], [r[x] for x in a[3:]])
            elif n == 'Mov': r[a[0]] = r[a[1]]
            elif n == 'Sub':r[a[0]]=jsnum(r[a[1]])-jsnum(r[a[2]])
            elif n == 'Add':
                x,y = r[a[1]],r[a[2]]
                r[a[0]] = jsstr(x)+jsstr(y) if isinstance(x,str) or isinstance(y,str) else jsnum(x)+jsnum(y)
            elif n=='AddEmptyString':r[a[0]]=jsstr(r[a[1]])
            elif n=='Not':r[a[0]]=not truth(r[a[1]])
            elif n in ('Greater','GreaterEq'):r[a[0]]=jsnum(r[a[1]])>jsnum(r[a[2]]) if n=='Greater' else jsnum(r[a[1]])>=jsnum(r[a[2]])
            elif n in ('StrictEq','StrictNeq'):r[a[0]]=(r[a[1]]==r[a[2]]) if n=='StrictEq' else (r[a[1]]!=r[a[2]])
            elif n=='Mul':r[a[0]]=r[a[1]]*r[a[2]]
            elif n == 'TypeOf':
                v = r[a[1]]
                r[a[0]] = 'boolean' if isinstance(v,bool) else 'number' if isinstance(v,(int,float)) else 'string' if isinstance(v,str) else 'undefined' if v is None else 'object'
            elif n.startswith('J'):
                if n in ('JStrictEqual','JStrictEqualLong'): yes = type(r[a[1]]) == type(r[a[2]]) and r[a[1]] == r[a[2]]
                elif n in ('JStrictNotEqual','JStrictNotEqualLong'): yes = type(r[a[1]]) != type(r[a[2]]) or r[a[1]] != r[a[2]]
                elif n in ('JmpTrue','JmpTrueLong'): yes = truth(r[a[1]])
                elif n in ('JmpFalse','JmpFalseLong'): yes = not truth(r[a[1]])
                elif n in ('Jmp','JmpLong'): yes = True
                elif n in ('JEqual','JNotEqual'):yes=(r[a[1]]==r[a[2]] or {id(r[a[1]]),id(r[a[2]])}<={id(None),id(U)});yes=not yes if n=='JNotEqual' else yes
                elif n=='JNotGreaterEqual':yes=not jsnum(r[a[1]])>=jsnum(r[a[2]])
                elif n=='JNotGreater':yes=not jsnum(r[a[1]])>jsnum(r[a[2]])
                elif n=='JNotLess':yes=not jsnum(r[a[1]])<jsnum(r[a[2]])
                else: raise AssertionError((fid,n))
                if yes: pc = positions[i.original_pos+a[0]]
            elif n == 'Ret': return r[a[0]]
            elif n == 'CompleteGenerator': pass
            else: raise AssertionError((fid,n))
        raise AssertionError(('did not settle',fid))

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--bundle',type=Path);args=ap.parse_args()
    root=Path(__file__).resolve().parents[2]
    if args.bundle:r=load(args.bundle.read_bytes())
    else:
        b=(root/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
        d=additions(load(b),bytearray(b),set(),[]);d[-20:]=hashlib.sha1(d[:-20]).digest();r=load(bytes(d));r.strings[12897]='未检测'
    for f in FIDS+(35955,35966):check_frame(r,f);check_read_caches(r,f)
    vm=VM(r)
    row_code=list(parse_hbc_bytecode(r.function_headers[35955],r))
    summary_read=next(i for i in row_code if i.inst.name=='GetById' and i.arg1==40 and r.strings[i.arg4]=='stepSubTitle')
    start=summary_read.original_pos
    end=next(i.original_pos for i in row_code if i.inst.name=='PutOwnByIndex' and (i.arg1,i.arg2,i.arg3)==(19,10,1))
    stage_module=Env()
    def stage_require(_,dep):
        if dep==22:return {'jsx':vm.jsx,'jsxs':vm.jsx,'Fragment':'Fragment'}
        if dep==23:return {'Flex':'Flex','Icon':'Icon'}
        raise AssertionError(('unexpected diagnosis stage dependency',dep))
    stage_module.slots.update({0:Fn(stage_require),1:list(range(32)),
        6:{key:key for key in ('View','Text','Image','ActivityIndicator')},
        14:{status:{'img':'original-status-icon','name':'status','color':'#25bcbf'} for status in ('success','running','pause','fail')}})
    stage_owner=Env(stage_module)
    stage_owner.slots.update({12:[{}]*5,19:{r.strings[i]:{'fontSize':14} for i in (41777,34972,49392,49355,49373,21932,49348,49347)}})
    for summary in ('已测量 4项 · 4 待判定','本次未取得数据'):
        registers=vm.run(35955,stage_owner,initial={0:{'stepSubTitle':summary},12:{'originalSubtitleStyle':True},11:vm.jsx,4:U,10:'Text',9:stage_module},start=start,stop=end)
        assert registers[10]['props']['children']==summary
        assert registers[10]['props']['style']=={'originalSubtitleStyle':True}
    # Execute the complete original row, including the live require/RN values
    # used after the replaced subtitle block (the hub.16 runtime regression).
    for index,tag in enumerate(('netStatus','signalStatus','interfereStatus','loadStatus','iotStatus')):
        for status in ('success','running','pause','fail'):
            summary='本次已测量，待判定'
            row={'tag':tag,'status':status,'name':tag,'img':'original-stage-icon',
                 'normalNumber':0,'exceptionNumber':0,'totalNumber':0,'stepSubTitle':summary}
            tree=vm.run(35955,stage_owner,[row,index])
            assert any(n.get('kind')=='Text' and n['props'].get('children')==summary for n in walk(tree)),(tag,status)
    legacy_row={'tag':'netStatus','status':'success','name':'网络连通','img':'original-stage-icon',
                'normalNumber':2,'exceptionNumber':0,'totalNumber':2}
    legacy_tree=vm.run(35955,stage_owner,[legacy_row,0])
    assert any(n.get('kind')=='Text' and '已检测' in str(n['props'].get('children'))
               for n in walk(legacy_tree)), 'Persisted original counters must still render a subtitle'
    for fid in (33247,33249,33251,33253,33255):
        summary='5G 利用率 18%；稳定性待判定'
        tree=vm.run(fid,Env(vm.component),[{'name':'实测项目','detailData':{'problemLevel':0,'problem':summary,
                   'measurementMetadata':{'available':True}}}])
        assert any(n.get('kind')=='Text' and n['props'].get('children')=='--' for n in walk(tree)), (fid,'partial reading must stay compact')
        assert not vm.leaf_calls,'incomplete vendor fields must not enter typed numeric renderer'
        missing_tree=vm.run(fid,Env(vm.component),[{'name':'缺测项目','detailData':{'problemLevel':0,
                   'problem':'未检测'}}])
        assert any(n.get('kind')=='Text' and n['props'].get('children')=='--' for n in walk(missing_tree))
    fixtures=json.loads((root/'docs/rea-evidence-20261024/diagnose-detail-backend-fixtures.json').read_text(encoding='utf8'))
    for name,detail in fixtures.items():
        vm=VM(r);vm.component.slots[3]=detail
        for category,fid in [('netStatus',33246),('signalStatus',33248),('interfereStatus',33250),('loadStatus',33252),('iotStatus',33254)]:
            vm.run(fid,vm.component)
            node=vm.run(33277,vm.component,[{'tag':category,'name':category,'img':'original-icon'}])
            if detail['diagnosisProblemDetailMap'][category]['problemLevel']==0:
                texts=[n['props'].get('children') for n in walk(node) if n.get('kind')=='Text']
                assert '--' in texts,(name,category,texts)
                assert not any(n.get('kind')=='Image' and n['props'].get('source')=='normal-badge' for n in walk(node))
        if name=='measured-abnormal':assert 33273 in vm.leaf_calls,'real known CPU anomaly must dispatch original typed detail'
        if name=='finite-phone-ping':assert 33276 not in vm.leaf_calls,'local probe row must remain removed'
        if name=='partial-metrics':
            load_section=detail['diagnosisProblemDetailMap']['loadStatus']
            assert load_section['problemLevel']==2 and load_section['high_ap_load']['problemLevel']==0
            raw=json.loads(load_section['high_ap_load']['problemDetail'])
            assert raw=={'cpuUse':90.0,'memUse':None,'cpuTemp':None}
            assert 33273 not in vm.leaf_calls,'partial fields must not label absent memory/temp normal'
            group=vm.run(33277,vm.component,[{'tag':'loadStatus','name':'loadStatus','img':'original-icon'}])
            assert any(n.get('kind')=='Image' and n['props'].get('source')=='warning-badge' for n in walk(group)), 'measured CPU anomaly must remain visible in category'
    # Execute actual typed map/toFixed paths: JSON parse alone cannot catch them.
    vm=VM(r)
    for fid,data in {33258:{'upLinkSpeed':100},33264:{'flapCount':0,'flapTime':'未检测'},33265:{'reboot_count':0,'last_time':'未检测'},33272:{'use24':26.64,'use5':1.95,'interfere':'小'},33273:{'cpuTemp':60,'cpuUse':20,'memUse':40},33274:{'onlineCnt':12,'bandWide':100}}.items():vm.run(fid,vm.component,[data])
    cases={33266:[{'staName':'phone','duration':None,'durationTime':None}],
           33268:[{'staName':'phone','rssiAvg':-75,'wifiBand':'2.4G','duration':None,'lastTime':None}],
           33270:[{'staName':'camera','shakeCnt':None,'lastShakeTime':None}],
           33275:[{'staName':'phone','pingType':'pingNet','address':'example.test','delay':2.5,'lossRate':0}]}
    for fid,data in cases.items():
        tree=vm.run(fid,vm.component,[data])
        if fid!=33275:assert any('--' in str(n['props'].get('children')) for n in walk(tree) if n.get('kind')=='Text')
    try:vm.run(33275,vm.component,[[{'delay':'2.5','lossRate':0,'address':'example.test'}]])
    except (AttributeError,TypeError):pass
    else:raise AssertionError('original numeric toFixed path must reject a string delay')
    try:vm.run(33268,vm.component,[{'available':True,'checks':[]}])
    except (AttributeError,TypeError):pass
    else:raise AssertionError('original signal typed renderer must reject the old wrapper object')
    layout=vm.run(33278,vm.module,[{'name':'信道利用率','showText':[
        {'text':'[5G] 100%','isProblem':False},{'text':'[2.4G] 100%','isProblem':False}]}])
    nodes=list(walk(layout))
    assert any(flatten_style(n['props'].get('style')).get('flexWrap')=='wrap' for n in nodes)
    readings=[n for n in nodes if n.get('kind')=='Text' and str(n['props'].get('children','')).startswith('[')]
    assert len(readings)==2
    for node in readings:
        style=flatten_style(node['props']['style'])
        assert style['flexGrow']==0 and style['flexShrink']==1 and style['minWidth']==0
    print('PASS diagnosis fixtures, typed renderers and bounded/wrapping two-band readings')

if __name__=='__main__':main()
