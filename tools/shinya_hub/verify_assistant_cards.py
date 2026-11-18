"""Execute the real structured-card bytecode, including every action callback."""
import argparse
import hashlib
import json
import math
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hbc_grow import assert_bodies_mapped
from patch_assistant_cards import additions, STRINGS, FIDS, CARD, ACTION, PRESS, REFRESH
from patch_bundle import load
from verify_station_lookup import check_frame
from verify_terminal_ipv6_patch import check_read_caches, Env, Fn
from capture_assistant_typography import literal


class VM:
    def __init__(self, reader):
        self.reader = reader
        self.calls = []
        self.globals = {'JSON': {'parse': Fn(lambda _, text: json.loads(text))},
                        '__r': Fn(lambda _, module: require(None, module)),
                        'Array': {'isArray': Fn(lambda _, value: isinstance(value, list))},'Number':Fn(lambda _,v:float(v) if str(v).replace('.','',1).isdigit() else float('nan')),'isFinite':Fn(lambda _,v:math.isfinite(v))}
        jsx = Fn(lambda _, kind, props: {'kind': kind, 'props': props})
        rn = dict.fromkeys(('View','Text','Image','ImageBackground','TouchableOpacity','ActivityIndicator'))
        rn.update({kind:kind for kind in rn})
        self.registered_views=set()
        def register(_,name):
            if name in self.registered_views:raise RuntimeError('Tried to register two views with the same name '+name)
            self.registered_views.add(name);return name
        rn['requireNativeComponent']=Fn(register)
        rn['Dimensions']={'get':Fn(lambda _,surface:{'width':444,'height':790})}
        rn['StyleSheet']={'create':Fn(lambda _, styles: styles)}
        rn['NativeModules'] = {'sta': {
            'cardSpeedtest': Fn(lambda _, project, sn: self.calls.append(('speedtest',project,sn))),
            'startScan': Fn(lambda _, project, sn: self.calls.append(('survey',project,sn)))}}
        theme_object=next(i for i in parse_hbc_bytecode(reader.function_headers[17551],reader)
                          if i.inst.name in ('NewObjectWithBuffer','NewObjectWithBufferLong'))
        theme=dict(zip(literal(reader,reader.object_keys[theme_object.arg4:],theme_object.arg3),
                       literal(reader,reader.object_values[theme_object.arg5:],theme_object.arg3)))
        def require(_, module):
            if module == 1:return {'fontWeightMedium':'bold'}
            if module == 4:
                return {'Svg': 'Svg', 'Path': 'Path', 'Line': 'Line', 'Text': 'SvgText', 'G': 'G'}
            if module == 12:
                return {'LineChart': 'LineChart'}
            if module == 2057:
                return {'StaTypeIcons': {'phone': 'src_assets_image_common_icon_statype_phone', 'pad': 'src_assets_image_common_icon_statype_ipad'}}
            if module == 10:
                return {'useThemeStyles': Fn(lambda _, options: {'styles': options['themeStyles'].call(None,[theme])
                    if isinstance(options['themeStyles'],Fn) else options['themeStyles']})}
            if module == 11: return {'jsx': jsx, 'jsxs': jsx}
            raise AssertionError(('unexpected require', module))
        self.module = Env()
        self.module.slots.update({0:Fn(require),1:list(range(12)),2:rn,
            3:{'default':'original-signal-art'},
            # Audited Metro deps: env4=shared helper theme4114, env5=local
            # SignalCard theme4137. Do not mock the local factory in both slots.
            4:{'default':Fn(lambda _, value:{})},
            5:{'default':Fn(lambda _, value:self.run(35859,self.module,[value]))},
            9:{'default':Fn(lambda _, props: {'imageStyle':{}})}})

    def prop(self, owner, key):
        if owner is None: raise TypeError(('JS property access on null/undefined',key))
        if isinstance(owner, dict): return owner.get(key)
        if isinstance(owner,(int,float)) and not isinstance(owner,bool) and key=='toFixed':
            return Fn(lambda _, digits: f'{owner:.{int(digits)}f}')
        if isinstance(owner,str) and key=='replace':return Fn(lambda _,a,b:owner.replace(a,b,1))
        if isinstance(owner,str) and key=='split':return Fn(lambda _,separator:owner.split(separator))
        if isinstance(owner,str) and key=='indexOf':return Fn(lambda _,part:owner.find(part))
        if isinstance(owner, list):
            if key == 'length': return len(owner)
            if key == 'slice': return Fn(lambda _, start, stop: owner[start:stop])
            if key == 'map': return Fn(lambda _, callback: [callback.call(None,[v,i]) for i,v in enumerate(owner)])
            if key == 'push': return Fn(lambda _, value: owner.append(value) or len(owner))
            if isinstance(key, int): return owner[key] if 0 <= key < len(owner) else None
        return None

    def run(self, fid, env, args=()):
        instructions = list(parse_hbc_bytecode(self.reader.function_headers[fid], self.reader))
        positions = {i.original_pos:index for index,i in enumerate(instructions)}
        r, pc = {}, 0
        for _ in range(10000):
            i = instructions[pc]
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
            elif n == 'LoadParam': r[a[0]] = args[a[1]-1] if len(args)>=a[1] else None
            elif n in ('LoadConstUndefined','LoadConstNull'): r[a[0]] = None
            elif n == 'LoadConstTrue': r[a[0]] = True
            elif n == 'LoadConstFalse': r[a[0]] = False
            elif n in ('LoadConstInt','LoadConstUInt8','LoadConstDouble'): r[a[0]] = a[1]
            elif n == 'LoadConstZero': r[a[0]] = 0
            elif n == 'LoadConstString': r[a[0]] = self.reader.strings[a[1]]
            elif n == 'GetGlobalObject': r[a[0]] = self.globals
            elif n in ('GetById','GetByIdShort'): r[a[0]] = self.prop(r[a[1]],self.reader.strings[a[3]])
            elif n == 'GetByVal': r[a[0]] = self.prop(r[a[1]],r[a[2]])
            elif n in ('PutNewOwnById','PutNewOwnByIdShort'): r[a[0]][self.reader.strings[a[2]]] = r[a[1]]
            elif n == 'NewObject': r[a[0]] = {}
            elif n in ('NewObjectWithBuffer','NewObjectWithBufferLong'):
                r[a[0]]=dict(zip(literal(self.reader,self.reader.object_keys[a[3]:],a[2]),
                                 literal(self.reader,self.reader.object_values[a[4]:],a[2])))
            elif n in ('PutById','PutByIdShort'):r[a[0]][self.reader.strings[a[3]]]=r[a[1]]
            elif n=='PutByVal':r[a[0]][r[a[1]]]=r[a[2]]
            elif n == 'NewArray': r[a[0]] = [None]*a[1]
            elif n == 'PutOwnByIndex': r[a[0]][a[2]] = r[a[1]]
            elif n == 'CreateClosure':
                closure_env, closure_fid = r[a[1]], a[2]
                r[a[0]] = Fn(lambda _, *values, e=closure_env, f=closure_fid: self.run(f,e,values))
            elif n in ('Call1','Call2','Call3','Call4'):
                r[a[0]] = r[a[1]].call(r[a[2]], [r[x] for x in a[3:]])
            elif n=='AddEmptyString':r[a[0]]=str(int(r[a[1]])) if isinstance(r[a[1]],float) and r[a[1]].is_integer() else str(r[a[1]])
            elif n == 'Mov': r[a[0]] = r[a[1]]
            elif n == 'Add':
                x,y = r[a[1]],r[a[2]]
                r[a[0]] = str(x)+str(y) if isinstance(x,str) or isinstance(y,str) else x+y
            elif n=='Mul':r[a[0]]=r[a[1]]*r[a[2]]
            elif n=='Sub':r[a[0]]=r[a[1]]-r[a[2]]
            elif n=='Div':r[a[0]]=r[a[1]]/r[a[2]]
            elif n == 'TypeOf':
                v = r[a[1]]
                r[a[0]] = 'boolean' if isinstance(v,bool) else 'number' if isinstance(v,(int,float)) else 'string' if isinstance(v,str) else 'undefined' if v is None else 'object'
            elif n.startswith('J'):
                if n in ('JStrictEqual','JStrictEqualLong'): yes = type(r[a[1]]) == type(r[a[2]]) and r[a[1]] == r[a[2]]
                elif n in ('JStrictNotEqual','JStrictNotEqualLong'): yes = type(r[a[1]]) != type(r[a[2]]) or r[a[1]] != r[a[2]]
                elif n in ('JmpTrue','JmpTrueLong'): yes = bool(r[a[1]])
                elif n in ('JmpFalse','JmpFalseLong'): yes = not r[a[1]]
                elif n in ('Jmp','JmpLong'): yes = True
                elif n in ('JLess','JLessLong'): yes = r[a[1]] < r[a[2]]
                elif n in ('JNotGreater','JNotGreaterLong'): yes = not r[a[1]] > r[a[2]]
                else: raise AssertionError((fid,n))
                if yes: pc = positions[i.original_pos+a[0]]
            elif n == 'Ret': return r[a[0]]
            else: raise AssertionError((fid,n))
        raise AssertionError(('did not settle',fid))


def walk(node):
    if isinstance(node, dict):
        yield node
        yield from walk(node.get('props',{}).get('children'))
    elif isinstance(node,list):
        for child in node: yield from walk(child)


def flatten_style(value):
    if isinstance(value,list):
        result={}
        for part in value:result.update(flatten_style(part))
        return result
    return value if isinstance(value,dict) else {}


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--bundle',type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.android.bundle'
    original = source.read_bytes(); before = load(original)
    if args.bundle:
        patched = load(args.bundle.read_bytes())
        # Empty entries explicitly return retired storage to the shared pool.
        # The integrated notification patch owns these reclaimed identifiers;
        # verify their exact new values rather than accepting arbitrary text.
        from patch_assistant_notifications import STRINGS as notification_strings
        expected = dict(STRINGS)
        for sid, value in notification_strings.items():
            if sid in expected:
                assert expected[sid] == ''
                expected[sid] = value
        assert all(patched.strings[sid]==value for sid,value in expected.items())
        from hermes_identifier_hashes import verify
        verify(patched)
    else:
        data = additions(before,bytearray(original),set(),[])
        data[-20:] = hashlib.sha1(data[:-20]).digest()
        patched = load(bytes(data))
        for sid,value in STRINGS.items(): patched.strings[sid] = value
    for fid in (35691,35856,35859,35773,*FIDS):
        check_frame(patched,fid); check_read_caches(patched,fid)
        positions = {i.original_pos for i in parse_hbc_bytecode(patched.function_headers[fid],patched)}
        for i in parse_hbc_bytecode(patched.function_headers[fid],patched):
            if i.inst.operands and 'Addr' in i.inst.operands[0].operand_type.name:
                assert i.original_pos+i.arg1 in positions
    assert_bodies_mapped(patched)
    # Verify the real Message component supplies the selected Redux project to
    # cards, rather than only injecting that prop in this renderer test fixture.
    message=list(parse_hbc_bytecode(patched.function_headers[35773],patched))
    selected=[i for i in message if i.inst.name in ('GetById','GetByIdShort')
              and patched.strings[i.arg4]=='curProject']
    scope_read=[i for i in message if i.inst.name=='GetById' and i.arg1==28
                and patched.strings[i.arg4]=='projectId']
    scope_put=[i for i in message if i.inst.name=='PutNewOwnById' and i.arg1==0
               and i.arg2==28 and patched.strings[i.arg3]=='projectId']
    assert len(selected)==len(scope_read)==len(scope_put)==1
    assert selected[0].arg1==scope_read[0].arg2==4
    assert selected[0].original_pos<scope_read[0].original_pos<scope_put[0].original_pos
    position=message.index(scope_put[0])
    assert message[position+1].inst.name=='Call3' and message[position+1].arg5==0
    assert patched.function_headers[CARD].environmentSize == 6
    assert patched.function_headers[ACTION].environmentSize == 1
    vm = VM(patched)
    def render(card):
        props = {'width':320,'height':480,'projectId':'BE50-project',
            'message':{'type':'hubCard','messageDetail':json.dumps(card,ensure_ascii=False)},
            'sendMessage':Fn(lambda _, question, kind: vm.calls.append(('refresh',question,kind)))}
        # Execute the actual SignalCard conditional, then actual result renderer.
        return vm.run(35856,vm.module,[props])
    # F35691 imports the SVGA wrapper in dependency slot 28 (env14), not
    # dependency 14: the module's environment slots are deliberately reordered.
    assert literal(before,before.arrays[63095:],56)[28]==2043
    vm.globals['__r']=Fn(lambda _,mid:{'default':'original-SVGA-player'} if mid==2043 else 4175 if mid==4175 else None)
    for _ in range(3):
        loading=render({'version':1,'kind':'loading','title':'正在整理网络日报…'})
        assert loading['kind']=='View'
        assert loading['props']['children']['kind']=='original-SVGA-player'
        assert loading['props']['children']['props']['source']==4175
        assert flatten_style(loading['props']['style'])['alignItems']=='flex-end'
    del vm.globals['__r']
    payload = {'version':1,'kind':'speedtest','state':'complete','projectId':'BE50-project','sn':'BE50-SN',
        'title':'网络测速','subtitle':'真实固件结果','icon':'src_assets_image_helper_diagnose_apload',
        'metrics':[{'label':'下载','value':123.4,'unit':'Mbps'},
                   {'label':'上传','value':0,'unit':'Mbps'},
                   {'label':'时延','value':1.72,'unit':'ms'},
                   {'label':'抖动','value':None,'unit':'ms'}],
        'rows':[{'label':'WAN','value':None,'detail':'',
                 'metrics':[{'label':'下载','value':123.4,'unit':'Mbps'}]}],
        'actions':[{'label':'重新测速','action':'speedtest'}, {'label':'不允许','action':'rebootRouter'}]}
    root = render(payload)
    nodes = list(walk(root)); texts = [n['props'].get('children') for n in nodes if n.get('kind')=='Text']
    assert 123.4 in texts and 0 in texts and '--' in texts
    assert 'WAN' not in texts  # speed result is not duplicated in interface rows
    title=next(n for n in nodes if n.get('kind')=='Text' and n['props'].get('children')=='网络测速')
    number=next(n for n in nodes if n.get('kind')=='Text' and n['props'].get('children')==123.4)
    title_style=flatten_style(title['props']['style']);number_style=flatten_style(number['props']['style'])
    # V1 report titles use subhead/500; the 24-point promotional headline
    # belongs to illustrated feature introductions, not measured reports.
    assert (title_style['fontSize'],title_style['fontWeight'])==(16,'500')
    assert (number_style['fontSize'],number_style['fontWeight'],round(number_style['lineHeight'],1))==(20,'500',28)
    speed_labels = [n for n in nodes if n.get('kind')=='Text' and n['props'].get('children') in ('下载','上传','时延','抖动')]
    assert len(speed_labels)==4
    legacy=dict(payload,metrics=payload['metrics'][:3])
    legacy_texts=[n['props'].get('children') for n in walk(render(legacy)) if n.get('kind')=='Text']
    assert '抖动' in legacy_texts and '--' in legacy_texts,'Persisted three-reading cards must gain an unknown jitter cell'
    assert len([n for n in nodes if n.get('kind')=='View' and flatten_style(n['props'].get('style')).get('width')=='50%'])==4
    units=[flatten_style(n['props'].get('style')) for n in nodes if n.get('kind')=='Text' and n['props'].get('children')=='Mbps']
    assert units and all((v['fontSize'],round(v['lineHeight'],1))==(12,16.8) for v in units)
    assert number['props']['numberOfLines']==1 and number['props']['adjustsFontSizeToFit'] is True
    assert not any(flatten_style(n['props'].get('style')).get('height')==160 for n in nodes)
    assert not any('fontFamily' in flatten_style(n['props'].get('style')) for n in nodes)
    # A read-only history card reuses this same renderer: the newest run is in
    # metrics and older runs are timestamp-labelled rows. Both must draw.
    history_payload = {'version':1,'kind':'speedtest','state':'complete',
        'projectId':'BE50-project','sn':'BE50-SN',
        'title':'测速历史','subtitle':'最近 3 次 · 来自路由器固件\n11-18 09:12 580.2/40.1 Mbps  ·  11-17 20:34 512.7/38.6 Mbps',
        'metrics':[{'label':'下载','value':591.08,'unit':'Mbps'},
                   {'label':'上传','value':42.3,'unit':'Mbps'},
                   {'label':'时延','value':1.73,'unit':'ms'},
                   {'label':'抖动','value':0.16,'unit':'ms'}],
        'rows':[],
        'actions':[{'label':'去测速','action':'speedtest'}]}
    hn=list(walk(render(history_payload)));ht=[n['props'].get('children') for n in hn if n.get('kind')=='Text']
    assert 591.08 in ht and '测速历史' in ht
    history_text=' '.join(str(x) for x in ht)
    assert '580.2/40.1' in history_text and '512.7/38.6' in history_text,'history in subtitle must render'
    hactions=[n for n in hn if n.get('kind')=='TouchableOpacity']
    assert len(hactions)==1
    assert root['kind']=='ImageBackground'
    assert root['props']['source']['uri']==STRINGS[29343]
    assert root['props']['resizeMode']=='cover'
    assert abs(flatten_style(root['props']['style'])['width']-355.2)<0.01
    images = [n for n in nodes if n.get('kind')=='Image']
    assert images[0]['props']['source']['uri']=='src_assets_image_helper_diagnose_apload'
    assert flatten_style(images[0]['props']['style'])['position']=='absolute'
    # The retained V1 SignalCard body follows the new type-only delegation
    # verbatim. None of its original renderer instructions are restyled.
    old=before.function_headers[35856];new=patched.function_headers[35856]
    original_code=list(parse_hbc_bytecode(old,before))
    retained=[i for i in parse_hbc_bytecode(new,patched)
              if i.original_pos>=new.bytecodeSizeInBytes-old.bytecodeSizeInBytes]
    def semantic(i):
        values=[getattr(i,'arg'+str(n+1)) for n in range(len(i.inst.operands))]
        # Final bundle canonicalizes property read-cache indexes globally.
        # Keep the opcode, object/destination registers and exact property SID.
        if i.inst.name in ('GetById','GetByIdShort','GetByIdLong'):values[2]=0
        return i.inst.name,values
    assert [semantic(i) for i in retained]==[semantic(i) for i in original_code]
    buttons = [n for n in nodes if n.get('kind')=='TouchableOpacity']
    assert len(buttons) == 1
    cyclic_event = {}; cyclic_event['nativeEvent'] = cyclic_event
    buttons[0]['props']['onPress'].call(None,[cyclic_event])
    assert vm.calls == [('speedtest','BE50-project','BE50-SN')]
    for bad in ('',None,False):
        payload['sn']=bad
        no_scope=list(walk(render(payload)))
        before_calls=list(vm.calls)
        for n in no_scope:
            if n.get('kind')=='TouchableOpacity':n['props']['onPress'].call(None,[])
        assert vm.calls==before_calls
    payload['sn']='BE50-SN'
    payload['projectId']='different-project'
    before_calls=list(vm.calls)
    for n in walk(render(payload)):
        if n.get('kind')=='TouchableOpacity':n['props']['onPress'].call(None,[])
    assert vm.calls==before_calls
    payload['projectId']='BE50-project'
    for state, spinners in (('ready',0),('starting',1),('running',1),('complete',0),('failed',0)):
        payload['state'] = state
        assert len([n for n in walk(render(payload)) if n.get('kind')=='ActivityIndicator']) == spinners
    for kind_sid, question_sid in REFRESH:
        payload.update(kind=patched.strings[kind_sid],actions=[{'label':'刷新','action':'refresh'}, {'label':'扫描干扰','action':'survey'}])
        for node in walk(render(payload)):
            if node.get('kind')=='TouchableOpacity': node['props']['onPress'].call(None,[cyclic_event])
        assert vm.calls[-2:] == [('refresh',patched.strings[question_sid],'chat'),('survey','BE50-project','BE50-SN')]
    for value in (None,True,{},[]):
        payload.update(metrics=[{'label':'缺测','value':value,'unit':''}],rows=[],actions=[])
        assert '--' in [n['props'].get('children') for n in walk(render(payload)) if n.get('kind')=='Text']
    payload.update(metrics=[{'label':'metric-'+str(i),'value':i,'unit':''} for i in range(7)],
                   rows=[{'label':'row-'+str(i),'value':i,'detail':''} for i in range(20)],
                   actions=[{'label':'刷新','action':'refresh'} for _ in range(5)])
    bounded = list(walk(render(payload)))
    bounded_text = [n['props'].get('children') for n in bounded if n.get('kind')=='Text']
    assert 'metric-3' in bounded_text and 'metric-4' not in bounded_text
    assert 'row-11' in bounded_text and 'row-12' not in bounded_text
    assert len([n for n in bounded if n.get('kind')=='TouchableOpacity']) == 3
    payload.update(metrics=None,rows=None,actions=None)
    assert render(payload)['kind']=='ImageBackground'
    layouts={}
    for kind in ('bandwidth','children','daily','presence','interference','devices'):
        payload.update(kind=kind,title=kind,icon='src_assets_image_service_childreporticon' if kind=='daily' else None,metrics=[{'label':'总量','value':0,'unit':'Mbps'}],
            rows=[{'label':'device-'+str(i),'value':None if i==0 else str(i)+' 分钟',
                   'detail':'视频 42 分钟 · 游戏 18 分钟',
                   'icon':'src_assets_image_common_icon_statype_phone' if kind in ('bandwidth','children','presence','devices') else
                          'src_assets_image_helper_diagnose_network' if kind in ('daily','interference') else None,
                   'metrics':[{'label':'下载','value':12.3,'unit':'Mbps'},
                              {'label':'上传','value':0,'unit':'Mbps'}]} for i in range(20)],
            actions=[{'label':'刷新','action':'refresh'}])
        layouts[kind]=list(walk(render(payload)))
        texts=[n['props'].get('children') for n in layouts[kind] if n.get('kind')=='Text']
        assert ('device-11' in texts) == (kind != 'daily'), kind
        assert ('device-12' in texts) == (kind == 'bandwidth')
        if kind != 'devices':
            assert '--' in texts,(kind,texts[:15])
            if kind not in ('bandwidth','daily'):assert '视频 42 分钟 · 游戏 18 分钟' in texts
    def styles(kind):return [flatten_style(n['props'].get('style')) for n in layouts[kind]]
    def uri(kind):return next(n['props']['source']['uri'] for n in layouts[kind] if n.get('kind')=='ImageBackground')
    assert uri('daily').endswith('icon_card_bg_common')
    assert uri('children')==uri('bandwidth')==uri('presence')==uri('interference')==STRINGS[29343]
    rank_texts=[n['props'].get('children') for n in layouts['bandwidth'] if n.get('kind')=='Text']
    assert 1 in rank_texts and 12 in rank_texts and 12.3 in rank_texts and 0 in rank_texts
    assert any(v.get('width')==24 and v.get('marginRight')==12 for v in styles('bandwidth'))
    assert any(v.get('backgroundColor')=='#9C7648' for v in styles('children'))
    assert any(v.get('color')=='#9C7648' and v.get('fontSize')==24 for v in styles('children'))
    duration_units=[n for n in layouts['children'] if n.get('kind')=='Text' and n['props'].get('children')=='分钟']
    assert duration_units and all(flatten_style(n['props']['style'])['fontSize']==12 for n in duration_units)
    assert any(n.get('kind')=='Image' and n['props'].get('source',{}).get('uri')=='src_assets_image_helper_diagnose_network' for n in layouts['interference'])
    for kind in ('bandwidth','children','presence','devices'):
        assert any(n.get('kind')=='Image' and n['props'].get('source',{}).get('uri')=='src_assets_image_common_icon_statype_phone' for n in layouts[kind])
    # A type supplied by the server must resolve through the same Metro table
    # as the terminal list, overriding an outdated legacy fallback URI.
    vm.globals['__r']=Fn(lambda _,module: {'StaTypeIcons':{'lock':12345}} if module==2057 else 60000+module)
    for kind in ('bandwidth','children','presence','devices'):
        payload.update(kind=kind,metrics=[],actions=[],rows=[{'label':'门锁','iconType':'lock',
            'icon':'src_assets_image_common_icon_statype_other','value':'在线'}])
        assert any(n.get('kind')=='Image' and n['props'].get('source')==12345 for n in walk(render(payload))),kind
        if kind=='bandwidth':
            assert any(n.get('kind')=='Image' and n['props'].get('source')==63765 for n in walk(render(payload)))
    del vm.globals['__r']
    payload.update(kind='devices', metrics=[], actions=[], rows=[
        {'label':'长设备名称 NAS', 'value':'在线', 'latestDevTime':'检测时间：15:28:30',
         'detail':'192.0.2.1 · 有线', 'icon':'src_assets_image_common_icon_statype_pc'},
        {'label':'摄像头', 'value':'离线或休眠', 'latestDevTime':'离线时间：10-14 13:08',
         'detail':'192.0.2.2 · Wi-Fi'}])
    status_nodes=list(walk(render(payload)))
    for value in ('在线','检测时间：15:28:30','离线或休眠','离线时间：10-14 13:08','192.0.2.1 · 有线'):
        assert any(n.get('kind')=='Text' and n['props'].get('children')==value for n in status_nodes), value
    for value in ('检测时间：15:28:30','离线时间：10-14 13:08'):
        item=next(n for n in status_nodes if n.get('kind')=='Text' and n['props'].get('children')==value)
        assert flatten_style(item['props']['style'])['color']=='#999999'
    for kind in layouts:
        assert not any(v.get('backgroundColor')=='#FFFFFF' for v in styles(kind)), (kind,'nested white surface')
        assert not any(v.get('position')=='absolute' and v.get('width')=='100%' for v in styles(kind))
        assert any(isinstance(v.get('width'),float) and abs(v['width']-355.2)<0.01 for v in styles(kind))
    assert any(v.get('width')==1 and v.get('flex')==1 for v in styles('presence'))
    assert any(v.get('width')==8 and v.get('height')==8 and v.get('borderRadius')==4 for v in styles('presence'))
    long_name='会议室与客厅共用的超长名称平板电脑-ABCDEFGHIJKLMNOPQRSTUVWXYZ'
    rate='↓ 10000.123456 Mbps | ↑ 9999.987654 Mbps'
    payload.update(kind='bandwidth',rows=[{'label':long_name,'value':rate,'detail':'不应重复显示的实测流量速率'}],metrics=[],actions=[])
    ns=list(walk(render(payload)))
    label=next(n for n in ns if n.get('kind')=='Text' and n['props'].get('children')==long_name)
    value=next(n for n in ns if n.get('kind')=='Text' and n['props'].get('children')==rate)
    assert label['props']['numberOfLines']==2 and flatten_style(value['props']['style'])['marginLeft']==36
    assert not any(n.get('kind')=='Text' and n['props'].get('children')=='不应重复显示的实测流量速率' for n in ns)
    for raw in (None,False,0,'未检测到即时统计 请稍后刷新','126 分钟','1.5 小时','126 未知单位','12 分钟 附加说明'):
        payload.update(kind='children',rows=[{'label':long_name,'value':raw,'detail':''}])
        texts=[n['props'].get('children') for n in walk(render(payload)) if n.get('kind')=='Text']
        expected='--' if raw is None or raw is False else '126' if raw=='126 分钟' else '1.5' if raw=='1.5 小时' else raw
        assert expected in texts,(raw,texts)
    vm.globals['__r']=Fn(lambda _,module: {'LineChart':'LineChart'} if module==12 else
                         {'StaTypeIcons':{'phone':'src_assets_image_common_icon_statype_phone'}} if module==2057 else None)
    payload.update(kind='daily', metrics=[{'label':'当前在线','value':11,'unit':'台'},
                                          {'label':'关注设备','value':6,'unit':'台'},
                                          {'label':'今日流量','value':637.36,'unit':'GB'}],
                   rows=[], actions=[{'label':'刷新日报','action':'refresh'}],
                   wan={'down':2*1024**4+410*1024**3,'up':384*1024**3+200*1024**2},
                   speed={'downMbps':587.73,'upMbps':49.63,'latencyMs':1.72,'jitterMs':0.45},
                   wireless={'downMbps':521,'upMbps':461},
                   childEvents=[{'label':'iPhone 15 Pro','iconType':'phone','icon':'src_assets_image_common_icon_statype_phone','online':3,'offline':3}])
    ns=list(walk(render(payload)))
    texts=[n['props'].get('children') for n in ns if n.get('kind')=='Text']
    for value in ('历史流量 (WAN)','累计下载','累计上传','最近测速','今日平均无线速率','下载速率','上传速率','关注设备上下线','上线 3 次 · 下线 3 次'):
        assert value in texts, value
    assert any(isinstance(t,str) and t.endswith(' TB') for t in texts), texts
    # The wireless average is the router's own integer Mbps, not a 2-decimal rate.
    assert '521' in texts and '461' in texts, texts
    assert not any(n.get('kind') in ('Svg','LineChart') for n in ns)
    payload.update(wan=None,speed=None,wireless=None,childEvents=[])
    ns=list(walk(render(payload)))
    texts=[n['props'].get('children') for n in ns if n.get('kind')=='Text']
    assert '--' in texts
    assert not any(n.get('kind') in ('Svg','LineChart') for n in ns)
    from patch_assistant_cards import YFMT
    for value, expected in ((400000,'390.6 Kbps'), (193.18*1024,'193.2 Kbps'),
                            (587.73e6,'560.5 Mbps'), (12,'12 bps'), (None,'--')):
        got=vm.run(YFMT,vm.module,[value])
        assert got==expected,(value,got,expected)
    print('PASS: actual RN card bytecode; four distinct new layouts, original V1 instructions/art/fonts, measured/zero/missing values, nested metrics, bounded lists, scoped actions; HBC frame/env/cache/branches/code mapping')


if __name__ == '__main__': main()
