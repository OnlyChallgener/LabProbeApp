"""Structured assistant cards using the original RN message list and art.

The original speedTest message remains an action. hubCard is an independent
result type, mapped to the retained SignalCard wrapper. Only that type delegates
to this renderer; all original SignalCard code and navigation stay intact.

Integration before string allocation: merge STRINGS without conflicts, then call
additions(current_reader, data, changed_functions, changes). No Java class or
native view registration is required. The normal native assistant adapter must
convert a version-1 hubCard object to type hubCard / JSON messageDetail.
"""
import ctypes
import json
from collections import defaultdict
from pathlib import Path

from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.serialized_literal_parser import unpack_slp_array,TagType

CARD, METRIC, ROW, ACTION, PRESS = 20054, 20056, 20058, 20059, 20060
DAILY, CHILD_ROW, YTICK, XLABEL = 20083, 20084, 20087, 20089
YFMT = 20090
FIDS = (CARD, METRIC, ROW, ACTION, PRESS, DAILY, CHILD_ROW, YTICK, XLABEL, YFMT)
HUB_CARD = 12143
STRINGS = {
    HUB_CARD: 'hubCard', 12897: '未检测',
    29343: 'src_assets_image_helper_icon_card_bg_diagnose',
    37679: 'cardSpeedtest',
    39333: 'presence', 40990: 'interference', 42231: '抖动',
    45135: '连接记录', 46436: '关注用网', 31265: '网络排行',
    42985: 'NAS', 14103: '网络日报', 15239: '温度',
    18524: '诊断完成', 18544: 'hGet', 1330: 'hSet',
    433: 'src_assets_image_helper_diagnose_distribution',
    438: 'src_assets_image_helper_icon_device_onoff',
    # Daily report sections: retired slots with unique storage spans, free of
    # live bytecode/literal references and unused by every other patch module.
    258: '历史流量 (WAN)', 259: '累计下载', 263: '累计上传', 266: '最近测速',
    279: '今日网速趋势', 285: '关注设备上下线', 332: '上线 ', 335: ' 次 · 下线 ',
    336: ' 次', 337: ' TB', 367: '下行', 368: '上行',
    371: 'src_assets_image_helper_diagnose_network', 374: 'src_assets_image_helper_diagnose_apload', 382: 'src_assets_image_helper_diagnose_progress', 385: 'src_assets_image_diagnose_icon_phone_shell',
    388: '#6d7b8e', 390: '#CEE0F099', 392: '#172233', 394: 'downMbps',
    399: 'upMbps', 401: 'latencyMs', 402: 'jitterMs', 403: 'hasData',
    404: 'yTicks', 405: 'xLabels', 406: 'downArea', 413: 'rgba(0,136,188,0.15)',
    414: 'upArea', 418: 'rgba(139,92,246,0.12)', 422: 'downLine', 423: '#0088bc',
    424: 'upLine', 429: '#8b5cf6', 430: 'childEvents', 432: '#cee0f0',
}
REFRESH = tuple(zip((39333,78,36970,40990,26490,23777),
                    (45135,46436,31265,19632,42985,20693))) + ((27329,15239),)


def assert_literal_ownership(reader,sids,retired):
    """The old index omits literal-buffer string references. Check those too.

    This is a focused ownership audit of candidate private string IDs, not a
    feature rescan. Shared byte ranges remain protected by the main allocator.
    """
    groups=defaultdict(list)
    for fid,h in enumerate(reader.function_headers):groups[(h.offset,h.bytecodeSizeInBytes)].append(fid)
    cache={}
    def references(section,offset,count):
        identity=(section,offset,count)
        if identity not in cache:
            values=unpack_slp_array(getattr(reader,section)[offset:],count,reader.header.version)
            cache[identity]={v.value for v in values.items if v.tag_type in
                (TagType.LongStringTag,TagType.ShortStringTag,TagType.ByteStringTag)} & sids
        return cache[identity]
    for owners in groups.values():
        # A literal in an exclusively retired body is safe without decoding it.
        if all(fid in retired for fid in owners):continue
        for i in parse_hbc_bytecode(reader.function_headers[owners[0]],reader):
            found=set()
            if i.inst.name in ('NewObjectWithBuffer','NewObjectWithBufferLong'):
                found=references('object_keys',i.arg4,i.arg3)|references('object_values',i.arg5,i.arg3)
            elif i.inst.name in ('NewArrayWithBuffer','NewArrayWithBufferLong'):
                found=references('arrays',i.arg4,i.arg3)
            assert not found,('private string has a live literal-buffer reference',sorted(found),owners)


class C(Code):
    def __init__(self, reader, cache=0):
        super().__init__(reader)
        self.cache = cache

    def sid(self, text):
        for sid, value in STRINGS.items():
            if text == value: return sid
        return super().sid(text)

    def get(self, dst, obj, text):
        sid = self.sid(text)
        # Retired literal slots are not imported into Hermes' identifier map;
        # GetById on them resolves an empty SymbolID at runtime.
        if int(self.reader.string_kinds[sid]) != 1:
            self.emit('LoadConstString', dst, sid)
            self.emit('GetByVal', dst, obj, dst)
        else:
            self.cache += 1
            self.emit('GetById', dst, obj, self.cache, sid)

    def put(self, obj, value, text):
        sid = self.sid(text)
        if int(self.reader.string_kinds[sid]) != 1:
            self.emit('LoadConstString', 71, sid)
            self.emit('PutByVal', obj, value, 71)
        else:
            self.emit('PutNewOwnById', obj, value, sid)

    def value(self, dst, value):
        if isinstance(value, str): self.string(dst, value)
        elif isinstance(value, bool): self.emit('LoadConstTrue' if value else 'LoadConstFalse', dst)
        elif isinstance(value, int): self.number(dst, value)
        else: self.emit('LoadConstDouble', dst, value)

    def style(self, dst, values, scratch=29):
        self.emit('NewObject', dst)
        for key, value in values.items():
            self.value(scratch, value)
            self.put(dst, scratch, key)

    def node(self, dst, kind, children, style=None, props=24, sty=25, typ=26, extra=None, typography=None,dynamic_style=None):
        self.emit('NewObject', props)
        report_text=typography in ('enterButtonText','onlineTimeTitle')
        if report_text and style is None:style={}
        if typography is not None:
            self.get(27, 31, typography)
        if style is not None:
            self.style(sty, style)
            for key,register in (dynamic_style or {}).items():self.put(sty,register,key)
            if report_text:
                self.get(52,27,'fontSize');self.emit('LoadConstDouble',53,1.4)
                self.emit('Mul',52,52,53);self.put(sty,52,'lineHeight')
            if typography is not None:
                self.emit('NewArray', 28, 2)
                self.emit('PutOwnByIndex', 28, 27, 0)
                self.emit('PutOwnByIndex', 28, sty, 1)
                self.put(props, 28, 'style')
            else:self.put(props, sty, 'style')
        elif typography is not None:self.put(props, 27, 'style')
        if children is not None: self.put(props, children, 'children')
        for key, value in (extra or {}).items():
            self.value(29, value); self.put(props, 29, key)
        self.get(typ, 14, kind)
        self.emit('Call3', dst, 2, 3, typ, props)


def _array(c, dst, values):
    assert dst not in values, 'NewArray must not overwrite a child register'
    c.emit('NewArray', dst, len(values))
    for index, register in enumerate(values): c.emit('PutOwnByIndex', dst, register, index)


def _optional_text(c,dst,content,style,typography):
    c.emit('LoadConstNull',dst);empty=c.branch('JmpFalseLong',content)
    c.node(dst,'Text',content,style,typography=typography);c.finish(empty)


def _common(c):
    c.emit('GetEnvironment', 0, 0)
    c.emit('LoadFromEnvironment', 14, 0, 0)  # RN
    c.emit('LoadFromEnvironment', 2, 0, 1)   # jsx
    c.emit('LoadFromEnvironment', 31, 0, 4)  # original theme StyleSheet objects
    c.emit('LoadConstUndefined', 3)
    c.emit('LoadParam', 1, 1)
    present = c.branch('JmpTrue', 1)
    c.emit('NewObject', 1)
    c.finish(present)


def _scalar(c, dst, obj, field):
    c.get(dst, obj, field)
    c.emit('TypeOf', 20, dst)
    c.string(21, 'number')
    number = c.branch('JStrictEqual', 20, 21)
    c.string(21, 'string')
    string = c.branch('JStrictEqual', 20, 21)
    c.string(dst, '--')
    c.finish(number); c.finish(string)


def _map(c, owner, key, limit, fid, environment, output, limit_register=None):
    c.get(20, owner, key)
    c.emit('GetGlobalObject', 21)
    c.get(22, 21, 'Array')
    c.get(23, 22, 'isArray')
    c.emit('Call2', 24, 23, 22, 20)
    valid = c.branch('JmpTrue', 24)
    c.emit('NewArray', 20, 0)
    c.finish(valid)
    c.get(25, 20, 'slice')
    c.number(26, 0)
    if limit_register is None: c.number(27, limit)
    else: c.emit('Mov', 27, limit_register)
    c.emit('Call3', 20, 25, 20, 26, 27)
    c.get(28, 20, 'map')
    c.emit('CreateClosure', 29, environment, fid)
    c.emit('Call2', output, 28, 20, 29)


def _metric(reader):
    c = C(reader); _common(c)
    c.emit('LoadFromEnvironment',55,0,2);c.get(56,55,'kind')
    c.string(57,'daily');daily=c.branch('JStrictEqualLong',56,57)
    c.get(4, 1, 'label')
    c.node(5, 'Text', 4, {'color':'#595959','marginBottom':6,'flexShrink':1}, typography='enterButtonText',extra={'numberOfLines':2})
    _scalar(c, 4, 1, 'value')
    c.string(57,'speedtest')
    speed=c.branch('JStrictEqualLong',56,57)
    c.node(6, 'Text', 4, {'flexShrink':1}, typography='onlineTimeNum',
           extra={'numberOfLines':1,'adjustsFontSizeToFit':True,'minimumFontScale':0.7})
    number_done=c.branch('JmpLong')
    c.finish(speed)
    # Four equal readings: two columns, two rows. The speed figures use a
    # smaller 20-point numeral so their Mbps units fit beside them.
    c.node(6, 'Text', 4, {'fontSize':20,'lineHeight':28,'flexShrink':1}, typography='onlineTimeNum',
           extra={'numberOfLines':1,'adjustsFontSizeToFit':True,'minimumFontScale':0.7})
    c.finish(number_done)
    c.get(4, 1, 'unit')
    c.node(7, 'Text', 4, {'marginLeft':4,'flexShrink':0}, typography='onlineTimeUnit')
    _array(c,8,(6,7));c.node(8,'View',8,{'flexDirection':'row','alignItems':'baseline'})
    _array(c, 12, (5,8))
    ordinary=c.branch('JStrictNotEqualLong',56,57)
    c.node(9, 'View', 12, {'width':'50%','flexShrink':1,'minWidth':0,
        'paddingVertical':8,'paddingHorizontal':4})
    metric_done=c.branch('JmpLong')
    c.finish(ordinary)
    c.node(9, 'View', 12, {'flexGrow':1,'flexShrink':1,'flexBasis':0,'minWidth':64,
        'paddingVertical':8,'margin':4})
    c.finish(metric_done)
    all_done=c.branch('JmpLong')
    c.finish(daily)
    c.get(4, 1, 'label')
    c.node(5, 'Text', 4, {'color':'#6d7b8e','marginBottom':6,'flexShrink':1,'textAlign':'center'}, typography='enterButtonText',extra={'numberOfLines':2})
    _scalar(c, 4, 1, 'value')
    c.node(6, 'Text', 4, {'flexShrink':1,'fontSize':18,'lineHeight':25,'textAlign':'center'}, typography='onlineTimeNum',
           extra={'numberOfLines':1,'adjustsFontSizeToFit':True,'minimumFontScale':0.7})
    c.get(4, 1, 'unit')
    c.node(7, 'Text', 4, {'marginLeft':2,'flexShrink':0,'fontSize':12,'color':'#6d7b8e'}, typography='onlineTimeUnit')
    _array(c,8,(6,7));c.node(8,'View',8,{'flexDirection':'row','alignItems':'baseline','justifyContent':'center'})
    _array(c, 12, (5,8))
    c.emit('LoadParam',58,2);c.number(59,2)
    bordered=c.branch('JLess',58,59)
    c.node(9, 'View', 12, {'flexGrow':1,'flexShrink':1,'flexBasis':0,'minWidth':0,'paddingVertical':8})
    plain_done=c.branch('JmpLong')
    c.finish(bordered)
    c.node(9, 'View', 12, {'flexGrow':1,'flexShrink':1,'flexBasis':0,'minWidth':0,'paddingVertical':8,
                          'borderRightWidth':1,'borderRightColor':'#CEE0F099'})
    c.finish(plain_done)
    c.finish(all_done)
    c.emit('Ret', 9)
    return c


def _row(reader):
    c = C(reader); _common(c)
    c.emit('LoadFromEnvironment',18,0,2);c.get(18,18,'kind')
    variants={}
    for kind in ('bandwidth','children','daily','presence','interference'):
        c.string(19,kind);variants[kind]=c.branch('JStrictEqualLong',18,19)
    c.get(4, 1, 'label')
    c.node(5, 'Text', 4, {'color':'#333333','flex':1}, typography='enterButtonText')
    _device_icon(c)
    _array(c,18,(52,5));c.node(18,'View',18,{'flexDirection':'row','alignItems':'center','flex':1,'minWidth':0})
    _scalar(c, 4, 1, 'value')
    c.node(6, 'Text', 4, {'color':'#333333','marginLeft':8,'flexShrink':1}, typography='enterButtonText')
    c.get(4, 1, 'latestDevTime')
    _optional_text(c, 7, 4, {'color':'#999999','marginLeft':8,'marginTop':3,'flexShrink':1}, 'onlineTimeUnit')
    _array(c, 15, (6,7))
    c.node(6, 'View', 15, {'flex':1,'minWidth':0,'flexShrink':1})
    # Interface rows may contain real per-interface metrics instead of a scalar.
    c.get(20, 1, 'metrics')
    c.emit('GetGlobalObject', 21); c.get(22, 21, 'Array'); c.get(23, 22, 'isArray')
    c.emit('Call2', 24, 23, 22, 20)
    no_metrics = c.branch('JmpFalse', 24)
    c.get(21, 20, 'length'); empty = c.branch('JmpFalse', 21)
    c.emit('LoadConstNull', 6)
    c.finish(empty); c.finish(no_metrics)
    _array(c, 7, (18,6))
    c.node(8, 'View', 7, {'flexDirection':'row','alignItems':'center'})
    c.get(4, 1, 'detail')
    _optional_text(c,9,4,{'color':'#595959','marginTop':4},'onlineTimeUnit')
    c.emit('GetEnvironment', 30, 0)
    _map(c, 1, 'metrics', 4, METRIC, 30, 10)
    c.node(11, 'View', 10, {'flexDirection':'row','flexWrap':'wrap','marginHorizontal':-4})
    _array(c, 12, (8,9,11))
    c.node(13, 'View', 12, {'paddingVertical':10,'borderBottomWidth':1,'borderBottomColor':'#E5E5E5'})
    c.emit('Ret', 13)
    for kind, branch in variants.items():
        c.finish(branch);_variant_row(c,kind)
    return c


def _variant_row(c, kind):
    """Use the same measured payload fields; only the presentation varies."""
    c.get(4,1,'label');_scalar(c,6,1,'value');c.get(10,1,'detail')
    if kind=='bandwidth':
        c.emit('LoadParam',15,2);c.number(16,1);c.emit('Add',15,15,16)
        c.node(17,'Text',15,{'width':24,'marginRight':12,'color':'#25bcbf'},typography='onlineTimeTitle')
        # The same three packaged medals used by the original speed page.
        c.emit('GetGlobalObject',68);c.get(69,68,'__r');no_loader=c.branch('JmpFalseLong',69)
        medals=[]
        for rank in (1,2,3):
            c.number(60,rank);medals.append(c.branch('JStrictEqualLong',15,60))
        plain=c.branch('JmpLong')
        for branch in medals:c.finish(branch)
        c.number(60,3764);c.emit('Add',60,60,15);c.emit('Call2',61,69,68,60)
        c.node(17,'Image',None,{'width':24,'height':24,'marginRight':12},extra={'resizeMode':'contain'})
        c.put(24,61,'source');c.get(26,14,'Image');c.emit('Call3',17,2,3,26,24)
        c.finish(plain);c.finish(no_loader)
        c.node(5,'Text',4,{'flex':1,'color':'#333333'},typography='enterButtonText',extra={'numberOfLines':2})
        _device_icon(c)
        _array(c,8,(17,52,5));c.node(8,'View',8,{'flexDirection':'row','alignItems':'center'})
        c.node(7,'Text',6,{'marginLeft':36,'marginTop':4,'color':'#333333','flexShrink':1},typography='enterButtonText')
        c.emit('LoadConstNull',9);c.emit('TypeOf',18,6);c.string(19,'string');not_text=c.branch('JStrictNotEqualLong',18,19)
        c.get(18,6,'indexOf');c.string(19,'未检测');c.emit('Call2',18,18,6,19);c.number(19,-1)
        measured=c.branch('JStrictEqualLong',18,19)
        c.node(9,'Text',10,{'marginLeft':36,'marginTop':4,'color':'#595959','flexShrink':1},typography='enterButtonText')
        c.finish(not_text);c.finish(measured)
        _inline_metrics(c)
        _array(c,12,(8,7,9,11));c.node(13,'View',12,{'paddingVertical':10,'borderBottomWidth':1,'borderBottomColor':'#E5E5E5'})
    elif kind=='children':
        c.node(5,'Text',4,{'color':'#333333','marginBottom':4,'flexShrink':1},typography='onlineTimeTitle')
        _device_icon(c)
        _array(c,18,(52,5));c.node(5,'View',18,{'flexDirection':'row','alignItems':'center'})
        # The backend's measured duration is "126 分钟". Keep the original
        # report's 24/500 number and 12 unit instead of enlarging both together.
        c.emit('LoadConstUndefined',18);c.emit('LoadConstFalse',55)
        c.emit('TypeOf',19,6);c.string(20,'number');number=c.branch('JStrictEqualLong',19,20)
        c.string(20,'string');invalid=[c.branch('JStrictNotEqualLong',19,20)]
        c.get(46,6,'split');c.string(47,' ');c.emit('Call2',46,46,6,47)
        c.get(47,46,'length');c.number(48,2);invalid.append(c.branch('JStrictNotEqualLong',47,48))
        c.number(47,0);c.emit('GetByVal',48,46,47);c.number(47,1);c.emit('GetByVal',49,46,47)
        unit_branches=[]
        for unit in ('分钟','小时','秒'):
            c.string(47,unit);unit_branches.append(c.branch('JStrictEqualLong',49,47))
        invalid.append(c.branch('JmpLong'))
        for branch in unit_branches:c.finish(branch)
        c.emit('GetGlobalObject',50);c.get(51,50,'Number');c.emit('Call2',51,51,3,48)
        c.get(52,50,'isFinite');c.emit('Call2',52,52,3,51);invalid.append(c.branch('JmpFalseLong',52))
        c.emit('AddEmptyString',52,51);invalid.append(c.branch('JStrictNotEqualLong',52,48))
        c.emit('Mov',6,48);c.emit('Mov',18,49);c.finish(number);c.emit('LoadConstTrue',55)
        for branch in invalid:c.finish(branch)
        ordinary=c.branch('JmpFalseLong',55)
        c.node(7,'Text',6,{'color':'#9C7648'},typography='onlineTimeNum');done=c.branch('JmpLong')
        c.finish(ordinary);c.node(7,'Text',6,{'color':'#595959'},typography='enterButtonText');c.finish(done)
        c.node(18,'Text',18,{'color':'#9C7648','marginLeft':4},typography='onlineTimeUnit')
        _array(c,19,(7,18));c.node(7,'View',19,{'flexDirection':'row','alignItems':'baseline','marginBottom':4})
        _optional_text(c,9,10,{'color':'#595959'},'enterButtonText')
        c.emit('GetEnvironment',30,0);_map(c,1,'metrics',4,METRIC,30,11)
        c.node(11,'View',11,{'flexDirection':'row','flexWrap':'wrap'})
        _array(c,12,(5,7,9,11));c.node(13,'View',12,{'paddingVertical':12,'borderBottomWidth':1,'borderBottomColor':'#E5E5E5'})
    elif kind in ('daily','interference'):
        c.node(5,'Text',4,{'color':'#333333','marginBottom':4},typography='onlineTimeTitle')
        c.get(45,1,'icon');no_icon=c.branch('JmpFalseLong',45)
        c.emit('NewObject',46);c.put(46,45,'uri')
        c.node(17,'Image',None,{'width':20,'height':20,'marginRight':8},extra={'resizeMode':'contain'})
        # Source is supplied only by the report's known packaged icon rows.
        c.put(24,46,'source');c.get(26,14,'Image');c.emit('Call3',17,2,3,26,24)
        _array(c,18,(17,5));c.node(5,'View',18,{'flexDirection':'row','alignItems':'center'})
        c.finish(no_icon)
        c.node(7,'Text',6,{'color':'#333333','flexShrink':1},typography='enterButtonText')
        _optional_text(c,9,10,{'color':'#595959','marginTop':4},'enterButtonText')
        c.emit('GetEnvironment',30,0);_map(c,1,'metrics',4,METRIC,30,11)
        c.node(11,'View',11,{'flexDirection':'row','flexWrap':'wrap'})
        if kind == 'daily':
            _array(c,18,(5,7));c.node(18,'View',18,{'flexDirection':'row','alignItems':'center','justifyContent':'space-between','flexWrap':'wrap'})
            _array(c,12,(18,9,11));c.node(13,'View',12,{'paddingVertical':9,'borderBottomWidth':1,'borderBottomColor':'#E5E5E5'})
        else:
            _array(c,12,(5,7,9,11));c.node(13,'View',12,{'paddingVertical':12,'borderBottomWidth':1,'borderBottomColor':'#E5E5E5'})
    else:
        c.node(5,'Text',4,{'color':'#333333','marginBottom':4,'flexShrink':1},typography='onlineTimeTitle')
        _device_icon(c)
        _array(c,53,(52,5));c.node(5,'View',53,{'flexDirection':'row','alignItems':'center','minWidth':0})
        c.node(7,'Text',6,{'color':'#333333','flexShrink':1},typography='enterButtonText')
        _optional_text(c,9,10,{'color':'#595959','marginTop':4,'flexShrink':1},'enterButtonText')
        _array(c,12,(5,7,9));c.node(12,'View',12,{'flex':1,'minWidth':0,'paddingLeft':12,'paddingBottom':12})
        c.emit('LoadConstNull',16);c.node(16,'View',16,{'width':8,'height':8,'borderRadius':4,'backgroundColor':'#25bcbf','marginTop':6})
        # Draw a connector only below each dot, ending before the next dot.
        c.emit('LoadConstNull',17)
        c.emit('LoadFromEnvironment',45,0,2);c.get(45,45,'rows');c.get(46,45,'length')
        c.emit('LoadParam',47,2);c.number(48,1);c.emit('Add',47,47,48)
        last=c.branch('JStrictEqualLong',47,46)
        c.node(17,'View',None,{'width':1,'flex':1,'backgroundColor':'#D9D9D9','marginTop':8,'marginBottom':6})
        c.finish(last)
        _array(c,18,(16,17));c.node(19,'View',18,{'width':14,'alignItems':'center'})
        _array(c,18,(19,12));c.node(13,'View',18,{'flexDirection':'row','alignItems':'stretch','paddingLeft':4})
    c.emit('Ret',13)


def _device_icon(c, size=20, gap=8):
    """Reuse the terminal list's live StaTypeIcons before any legacy URI fallback."""
    c.emit('LoadConstNull',52)
    c.emit('LoadConstNull',66)
    c.get(67,1,'iconType');no_type=c.branch('JmpFalseLong',67)
    c.emit('GetGlobalObject',68);c.get(69,68,'__r');no_loader=c.branch('JmpFalseLong',69)
    # Module 2057 is the original F22183 export used by the terminal list.
    c.number(70,2057);c.emit('Call2',69,69,68,70)
    c.get(69,69,'StaTypeIcons');no_table=c.branch('JmpFalseLong',69)
    c.emit('GetByVal',66,69,67)
    c.finish(no_table);c.finish(no_loader);c.finish(no_type)
    found=c.branch('JmpTrueLong',66)
    c.get(65,1,'icon');missing=c.branch('JmpFalseLong',65)
    c.emit('NewObject',66);c.put(66,65,'uri')
    c.finish(found)
    c.node(52,'Image',None,{'width':size,'height':size,'marginRight':gap,'flexShrink':0},
           extra={'resizeMode':'contain'})
    c.put(24,66,'source');c.get(26,14,'Image');c.emit('Call3',52,2,3,26,24)
    c.finish(missing)


def _inline_metrics(c):
    """Keep real per-device upload/download fields in compact ranking rows."""
    c.emit('LoadConstNull',11);c.get(45,1,'metrics')
    c.emit('GetGlobalObject',46);c.get(46,46,'Array');c.get(47,46,'isArray')
    c.emit('Call2',47,47,46,45);missing=c.branch('JmpFalseLong',47)
    c.emit('NewArray',54,4)
    for index in range(4):
        c.number(46,index);c.emit('GetByVal',48,45,46);c.emit('LoadConstNull',49)
        absent=c.branch('JmpFalseLong',48)
        c.get(50,48,'label');c.node(50,'Text',50,{'marginRight':3,'color':'#595959','fontSize':12},typography='enterButtonText')
        _scalar(c,51,48,'value');c.node(51,'Text',51,{'color':'#333333','fontSize':12,'flexShrink':1},typography='enterButtonText',extra={'numberOfLines':1,'adjustsFontSizeToFit':True,'minimumFontScale':0.8})
        c.get(52,48,'unit');c.node(52,'Text',52,{'marginLeft':3,'color':'#595959','fontSize':12},typography='onlineTimeUnit')
        _array(c,53,(50,51,52));c.node(49,'View',53,{'flexDirection':'row','alignItems':'center','width':'50%','minWidth':0,'marginTop':4})
        c.finish(absent);c.emit('PutOwnByIndex',54,49,index)
    c.node(11,'View',54,{'flexDirection':'row','marginLeft':36,'minWidth':0})
    c.finish(missing)


def _action(reader):
    c = C(reader)
    c.emit('CreateEnvironment', 30)
    _common(c)
    c.get(4, 1, 'action')
    branches = []
    for name in ('speedtest','survey','refresh'):
        c.string(5, name); branches.append(c.branch('JStrictEqual', 4, 5))
    c.emit('LoadConstNull', 5); c.emit('Ret', 5)
    for branch in branches: c.finish(branch)
    c.emit('StoreToEnvironment', 30, 0, 1)
    c.get(4, 1, 'label')
    c.node(5, 'Text', 4, typography='enterButtonText')
    c.emit('NewObject', 24)
    c.style(25, {'minHeight':36,'minWidth':96,'paddingVertical':6,'paddingHorizontal':12,'marginRight':8,
                 'marginBottom':4,'borderRadius':20,'backgroundColor':'#25bcbf',
                 'alignItems':'center','justifyContent':'center'})
    c.emit('LoadFromEnvironment',18,0,2);c.get(18,18,'kind');c.string(19,'children')
    ordinary=c.branch('JStrictNotEqual',18,19)
    c.string(19,'#9C7648');c.put(25,19,'backgroundColor');c.finish(ordinary)
    c.put(24, 25, 'style'); c.put(24, 5, 'children')
    c.emit('CreateClosure', 26, 30, PRESS); c.put(24, 26, 'onPress')
    c.get(26, 14, 'TouchableOpacity')
    c.emit('Call3', 6, 2, 3, 26, 24)
    c.emit('Ret', 6)
    return c


def _press(reader):
    c = C(reader)
    c.emit('GetEnvironment', 0, 0); c.emit('LoadFromEnvironment', 1, 0, 0)
    c.get(4, 1, 'action')
    c.emit('GetEnvironment', 0, 1)
    c.emit('LoadFromEnvironment', 14, 0, 0)
    c.emit('LoadFromEnvironment', 6, 0, 2)
    c.get(7, 6, 'projectId'); c.get(12, 6, 'sn')
    c.emit('LoadFromEnvironment',13,0,5)
    c.emit('LoadConstUndefined', 3)
    invalid_scope=[]
    for register in (7,12):
        c.emit('TypeOf',20,register);c.string(21,'string')
        invalid_scope.append(c.branch('JStrictNotEqualLong',20,21))
        invalid_scope.append(c.branch('JmpFalseLong',register))
    invalid_scope.append(c.branch('JStrictNotEqualLong',7,13))
    for action, method in (('speedtest','cardSpeedtest'), ('survey','startScan')):
        c.string(5, action); other = c.branch('JStrictNotEqualLong', 4, 5)
        c.get(8, 14, 'NativeModules'); c.get(8, 8, 'sta'); c.get(9, 8, method)
        c.emit('Call3', 11, 9, 8, 7, 12)
        c.emit('Ret', 3); c.finish(other)
    c.string(5, 'refresh'); unknown = c.branch('JStrictNotEqualLong', 4, 5)
    c.get(7, 6, 'kind')
    for kind_sid, question_sid in REFRESH:
        c.emit('LoadConstString', 5, kind_sid)
        other = c.branch('JStrictNotEqual', 7, 5)
        c.emit('LoadFromEnvironment', 8, 0, 3)
        c.emit('LoadConstString', 9, question_sid); c.string(10, 'chat')
        c.emit('Call3', 11, 8, 3, 9, 10)
        c.emit('Ret', 3); c.finish(other)
    c.finish(unknown)
    for branch in invalid_scope:c.finish(branch)
    c.emit('Ret', 3)
    return c


def _section_head(c, icon, title, out):
    c.string(41, icon)
    c.emit('NewObject', 42); c.put(42, 41, 'uri')
    c.node(43, 'Image', None, {'width': 18, 'height': 18, 'marginRight': 6, 'flexShrink': 0},
           extra={'resizeMode': 'contain'})
    c.put(24, 42, 'source'); c.get(26, 14, 'Image'); c.emit('Call3', 43, 2, 3, 26, 24)
    c.string(44, title)
    c.node(45, 'Text', 44, {'color': '#172233', 'fontSize': 14, 'fontWeight': '700', 'flexShrink': 1},
           extra={'numberOfLines': 1})
    _array(c, 46, (43, 45))
    c.node(out, 'View', 46, {'flexDirection': 'row', 'alignItems': 'center', 'marginBottom': 8})


def _bytes_text(c, objreg, field, out):
    """Firmware byte counters as TB/GB; an absent counter stays '--'."""
    c.string(out, '--')
    missing = c.branch('JmpFalseLong', objreg)
    c.get(51, objreg, field)
    c.emit('TypeOf', 52, 51); c.string(53, 'number')
    notnum = c.branch('JStrictNotEqualLong', 52, 53)
    c.emit('LoadConstDouble', 54, 1024 ** 4); c.emit('Div', 55, 51, 54)
    c.emit('LoadConstDouble', 56, 1)
    gb = c.branch('JLess', 55, 56)
    c.string(61, ' TB')
    tb_done = c.branch('JmpLong')
    c.finish(gb)
    c.emit('LoadConstDouble', 54, 1024 ** 3); c.emit('Div', 55, 51, 54)
    c.string(61, ' GB')
    c.finish(tb_done)
    c.get(57, 55, 'toFixed'); c.number(58, 2); c.emit('Call2', 59, 57, 55, 58)
    c.emit('Add', out, 59, 61)
    c.finish(notnum); c.finish(missing)


def _wan_box(c, label, field, out, first):
    c.string(41, label)
    c.node(42, 'Text', 41, {'color': '#6d7b8e', 'fontSize': 12}, extra={'numberOfLines': 1})
    c.get(50, 1, 'wan')
    _bytes_text(c, 50, field, 43)
    c.node(44, 'Text', 43, {'color': '#172233', 'fontSize': 14, 'fontWeight': '700', 'marginTop': 2},
           extra={'numberOfLines': 1, 'adjustsFontSizeToFit': True, 'minimumFontScale': 0.7})
    _array(c, 45, (42, 44))
    style = {'flex': 1, 'minWidth': 0, 'backgroundColor': '#FFFFFF66', 'borderWidth': 1,
             'borderColor': '#FFFFFF99', 'borderRadius': 10, 'padding': 8}
    if first:
        style['marginRight'] = 8
    c.node(out, 'View', 45, style)


def _speed_cell(c, label, field, unit, out, source='speed', digits=2):
    c.string(41, label)
    c.node(42, 'Text', 41, {'color': '#6d7b8e', 'fontSize': 13, 'flexShrink': 0}, extra={'numberOfLines': 1})
    c.get(51, 1, source)
    c.string(43, '--')
    absent = c.branch('JmpFalseLong', 51)
    c.get(52, 51, field)
    c.emit('TypeOf', 53, 52); c.string(54, 'number')
    notnum = c.branch('JStrictNotEqualLong', 53, 54)
    c.get(55, 52, 'toFixed'); c.number(56, digits); c.emit('Call2', 43, 55, 52, 56)
    c.finish(notnum); c.finish(absent)
    c.node(44, 'Text', 43, {'color': '#172233', 'fontSize': 13, 'fontWeight': '700', 'marginLeft': 6, 'flexShrink': 1},
           extra={'numberOfLines': 1, 'adjustsFontSizeToFit': True, 'minimumFontScale': 0.7})
    c.string(45, unit)
    c.node(46, 'Text', 45, {'color': '#6d7b8e', 'fontSize': 11, 'marginLeft': 3, 'flexShrink': 0},
           extra={'numberOfLines': 1})
    _array(c, 47, (42, 44, 46))
    c.node(out, 'View', 47, {'flexDirection': 'row', 'alignItems': 'baseline',
                             'width': '50%', 'minWidth': 0, 'paddingVertical': 3})


def _chart(c, out):
    """Reuse the official speed-statistics LineChart; no new native component."""
    c.emit('LoadConstNull', out)
    c.get(50, 1, 'chart')
    absent = c.branch('JmpFalseLong', 50)
    c.get(51, 50, 'lineChart')
    nodata = c.branch('JmpFalseLong', 51)
    c.emit('GetGlobalObject', 60); c.get(61, 60, '__r')
    noload = c.branch('JmpFalseLong', 61)
    c.number(62, 12); c.emit('Call2', 63, 61, 60, 62)
    c.get(64, 63, 'LineChart')
    c.get(41, 51, 'data')
    c.get(42, 51, 'xAxis')
    c.get(43, 51, 'yAxis')
    c.emit('NewObject', 24)
    c.put(24, 41, 'data')
    c.put(24, 42, 'xAxis')
    c.put(24, 43, 'yAxis')
    c.get(60, 14, 'Dimensions'); c.get(61, 60, 'get'); c.string(62, 'window')
    c.emit('Call2', 60, 61, 60, 62); c.get(61, 60, 'width')
    c.emit('LoadConstDouble', 62, 0.8); c.emit('Mul', 61, 61, 62)
    c.emit('LoadConstDouble', 62, 40); c.emit('Sub', 61, 61, 62)
    c.put(24, 61, 'width')
    c.number(61, 140); c.put(24, 61, 'height')
    c.emit('Call3', out, 2, 3, 64, 24)
    c.finish(nodata); c.finish(noload); c.finish(absent)


def _ytick(reader):
    c = C(reader); _common(c)
    c.emit('GetGlobalObject', 60); c.get(61, 60, '__r')
    c.number(62, 4); c.emit('Call2', 63, 61, 60, 62)
    c.get(66, 63, 'Line'); c.get(67, 63, 'Text')
    c.get(41, 1, 'y')
    c.emit('NewObject', 24)
    c.number(29, 30); c.put(24, 29, 'x1')
    c.number(29, 320); c.put(24, 29, 'x2')
    c.put(24, 41, 'y1'); c.put(24, 41, 'y2')
    c.string(29, '#cee0f0'); c.put(24, 29, 'stroke')
    c.emit('LoadConstDouble', 29, 0.5); c.put(24, 29, 'strokeWidth')
    c.emit('Call3', 42, 2, 3, 66, 24)
    c.get(43, 1, 'label')
    c.emit('LoadConstDouble', 44, 3); c.emit('Add', 45, 41, 44)
    c.emit('NewObject', 24)
    c.number(29, 26); c.put(24, 29, 'x')
    c.put(24, 45, 'y')
    c.number(29, 9); c.put(24, 29, 'fontSize')
    c.string(29, '#6d7b8e'); c.put(24, 29, 'fill')
    c.string(29, 'end'); c.put(24, 29, 'textAnchor')
    c.put(24, 43, 'children')
    c.emit('Call3', 46, 2, 3, 67, 24)
    _array(c, 47, (42, 46))
    c.emit('Ret', 47)
    return c


def _xlabel(reader):
    c = C(reader); _common(c)
    c.emit('GetGlobalObject', 60); c.get(61, 60, '__r')
    c.number(62, 4); c.emit('Call2', 63, 61, 60, 62)
    c.get(67, 63, 'Text')
    c.get(41, 1, 'x'); c.get(43, 1, 'label')
    c.emit('NewObject', 24)
    c.put(24, 41, 'x')
    c.number(29, 128); c.put(24, 29, 'y')
    c.number(29, 8); c.put(24, 29, 'fontSize')
    c.string(29, '#6d7b8e'); c.put(24, 29, 'fill')
    c.string(29, 'middle'); c.put(24, 29, 'textAnchor')
    c.put(24, 43, 'children')
    c.emit('Call3', 46, 2, 3, 67, 24)
    c.emit('Ret', 46)
    return c


def _child_row(reader):
    c = C(reader); _common(c)
    _device_icon(c, size=24, gap=8)
    c.get(41, 1, 'label')
    c.node(42, 'Text', 41, {'color': '#172233', 'fontSize': 13, 'fontWeight': '500',
                            'flex': 1, 'minWidth': 0, 'marginRight': 8},
           extra={'numberOfLines': 1})
    c.get(43, 1, 'online'); c.get(44, 1, 'offline')
    c.string(45, '上线 '); c.emit('Add', 46, 45, 43)
    c.string(45, ' 次 · 下线 '); c.emit('Add', 47, 46, 45)
    c.emit('Add', 48, 47, 44)
    c.string(45, ' 次'); c.emit('Add', 49, 48, 45)
    c.node(50, 'Text', 49, {'color': '#6d7b8e', 'fontSize': 13, 'flexShrink': 1},
           extra={'numberOfLines': 1})
    _array(c, 51, (52, 42, 50))
    c.node(53, 'View', 51, {'flexDirection': 'row', 'alignItems': 'center', 'paddingVertical': 5})
    c.emit('Ret', 53)
    return c


def _daily(reader):
    """Report sections in the approved card design; width stays the card's own."""
    c = C(reader); _common(c)
    _section_head(c, 'src_assets_image_helper_diagnose_network', '历史流量 (WAN)', 41)
    c.emit('Mov', 72, 41)
    _wan_box(c, '累计下载', 'down', 42, True)
    c.emit('Mov', 74, 42)
    _wan_box(c, '累计上传', 'up', 43, False)
    c.emit('Mov', 75, 43)
    _array(c, 44, (74, 75))
    c.node(45, 'View', 44, {'flexDirection': 'row'})
    _array(c, 46, (72, 45))
    c.node(47, 'View', 46, {'paddingVertical': 12, 'borderBottomWidth': 1, 'borderBottomColor': '#CEE0F099'})
    c.emit('Mov', 73, 47)
    _section_head(c, 'src_assets_image_helper_diagnose_apload', '最近测速', 41)
    c.emit('Mov', 74, 41)
    _speed_cell(c, '下行', 'downMbps', 'Mbps', 42)
    c.emit('Mov', 76, 42)
    _speed_cell(c, '上行', 'upMbps', 'Mbps', 43)
    c.emit('Mov', 77, 43)
    _array(c, 44, (76, 77)); c.node(45, 'View', 44, {'flexDirection': 'row'})
    c.emit('Mov', 75, 45)
    _speed_cell(c, '时延', 'latencyMs', 'ms', 42)
    c.emit('Mov', 76, 42)
    _speed_cell(c, '抖动', 'jitterMs', 'ms', 43)
    c.emit('Mov', 77, 43)
    _array(c, 46, (76, 77)); c.node(47, 'View', 46, {'flexDirection': 'row'})
    c.emit('Mov', 76, 47)
    _array(c, 48, (74, 75, 76))
    c.node(49, 'View', 48, {'paddingVertical': 12, 'borderBottomWidth': 1, 'borderBottomColor': '#CEE0F099'})
    c.emit('Mov', 77, 49)
    _section_head(c, 'src_assets_image_helper_diagnose_distribution', '今日平均无线速率', 41)
    c.emit('Mov', 78, 41)
    _speed_cell(c, '下载速率', 'downMbps', 'Mbps', 42, source='wireless', digits=0)
    c.emit('Mov', 74, 42)
    _speed_cell(c, '上传速率', 'upMbps', 'Mbps', 43, source='wireless', digits=0)
    c.emit('Mov', 75, 43)
    _array(c, 44, (74, 75))
    c.node(45, 'View', 44, {'flexDirection': 'row'})
    _array(c, 46, (78, 45))
    c.node(44, 'View', 46, {'paddingVertical': 12, 'borderBottomWidth': 1, 'borderBottomColor': '#CEE0F099'})
    c.emit('Mov', 79, 44)
    _section_head(c, 'src_assets_image_helper_icon_device_onoff', '关注设备上下线', 41)
    c.emit('Mov', 71, 41)
    c.emit('GetEnvironment', 30, 0)
    _map(c, 1, 'childEvents', 8, CHILD_ROW, 30, 42)
    _array(c, 43, (71, 42))
    c.node(45, 'View', 43, {'paddingTop': 12})
    _array(c, 46, (73, 77, 79, 45))
    c.emit('Ret', 46)
    return c


def _yfmt(reader):
    """Axis tick formatter: bps -> Kbps/Mbps, matching the official rate ladder."""
    c = C(reader)
    c.emit('LoadParam', 4, 1)
    c.emit('TypeOf', 5, 4); c.string(6, 'number')
    notnum = c.branch('JStrictNotEqualLong', 5, 6)
    c.emit('LoadConstDouble', 7, 1024*1024)
    below_m = c.branch('JLess', 4, 7)
    c.emit('Div', 8, 4, 7); c.get(9, 8, 'toFixed'); c.number(10, 1)
    c.emit('Call2', 11, 9, 8, 10); c.string(12, 'Mbps')
    c.string(13, ' '); c.emit('Add', 14, 11, 13); c.emit('Add', 15, 14, 12)
    c.emit('Ret', 15)
    c.finish(below_m)
    c.emit('LoadConstDouble', 7, 1024)
    below_k = c.branch('JLess', 4, 7)
    c.emit('Div', 8, 4, 7); c.get(9, 8, 'toFixed'); c.number(10, 1)
    c.emit('Call2', 11, 9, 8, 10); c.string(12, 'Kbps')
    c.string(13, ' '); c.emit('Add', 14, 11, 13); c.emit('Add', 15, 14, 12)
    c.emit('Ret', 15)
    c.finish(below_k)
    c.get(9, 4, 'toFixed'); c.number(10, 0)
    c.emit('Call2', 11, 9, 4, 10); c.string(12, 'bps')
    c.string(13, ' '); c.emit('Add', 14, 11, 13); c.emit('Add', 15, 14, 12)
    c.emit('Ret', 15)
    c.finish(notnum)
    c.string(15, '--'); c.emit('Ret', 15)
    return c


def _card(reader):
    c = C(reader, cache=6)
    c.emit('CreateEnvironment', 35)
    # Original SignalCard's theme and image hooks, unchanged and in the same order.
    h = reader.function_headers[35856]
    reader.file_buffer.seek(h.offset)
    c.raw.extend(reader.file_buffer.read(0x89))
    c.emit('LoadParam', 36, 1)
    c.get(37, 36, 'message'); c.get(37, 37, 'messageDetail')
    c.emit('GetGlobalObject', 20); c.get(21, 20, 'JSON'); c.get(22, 21, 'parse')
    c.emit('Call2', 38, 22, 21, 37)
    c.emit('LoadFromEnvironment', 14, 0, 2)
    c.number(20, 11); c.emit('GetByVal', 20, 18, 20)
    c.emit('Call2', 21, 17, 3, 20); c.get(2, 21, 'jsx')
    c.emit('StoreToEnvironment', 35, 0, 14)
    c.emit('StoreToEnvironment', 35, 1, 2)
    c.emit('StoreToEnvironment', 35, 2, 38)
    c.get(20, 36, 'sendMessage'); c.emit('StoreToEnvironment', 35, 3, 20)
    # SignalCard's first theme hook (r8) is shared helper styles (module4114).
    # Its second hook (r16) owns local card/report styles (module4137/F35859).
    # The private report typography was added to the latter factory.
    c.emit('StoreToEnvironment', 35, 4, 16)
    c.emit('Mov', 31, 16)
    c.get(20,36,'projectId');c.emit('StoreToEnvironment',35,5,20)
    # Reuse the shipped SVGA player and original diagnosticStandstill asset.
    # requireNativeComponent is a registration API and must never run here:
    # a second render used to throw and poison the persisted Helper history.
    c.get(40,38,'kind');c.string(41,'loading')
    ordinary_card=c.branch('JStrictNotEqualLong',40,41)
    c.emit('GetGlobalObject',40);c.get(41,40,'__r')
    c.number(42,2043);c.emit('Call2',42,41,40,42);c.get(43,42,'default')
    c.number(42,4175);c.emit('Call2',44,41,40,42)
    c.emit('NewObject',24);c.style(25,{'width':96,'height':96})
    c.put(24,25,'style');c.put(24,44,'source')
    c.emit('LoadConstFalse',45);c.put(24,45,'clearsAfterStop')
    c.emit('Call3',46,2,3,43,24)
    c.emit('NewObject',24);c.style(25,{'height':112,'alignItems':'flex-end','justifyContent':'center'})
    c.get(60,14,'Dimensions');c.get(61,60,'get');c.string(62,'window')
    c.emit('Call2',60,61,60,62);c.get(61,60,'width')
    c.emit('LoadConstDouble',62,0.92);c.emit('Mul',5,61,62);c.put(25,5,'width')
    c.put(24,25,'style');c.put(24,46,'children');c.get(26,14,'View')
    c.emit('Call3',13,2,3,26,24);c.emit('Ret',13)
    c.finish(ordinary_card)
    # The diagnose backdrop is an existing packaged gradient, not generated art.
    c.emit('NewObject', 24); c.emit('NewObject', 25)
    c.string(29, STRINGS[29343]);c.get(40,38,'kind');c.string(41,'daily')
    daily=c.branch('JStrictEqualLong',40,41)
    resource_done=c.branch('JmpLong')
    c.finish(daily);c.get(42,29,'replace');c.string(43,'diagnose');c.string(44,'common')
    c.emit('Call3',29,42,29,43,44);c.finish(resource_done)
    c.put(25, 29, 'uri');c.emit('Mov',58,25)
    # ImageBackground sizes its image from the final content height. A separate
    # absolute Image with height=100% stopped at the first measured row on RN,
    # leaving the action button outside the painted card.
    c.emit('LoadConstNull',11)
    # Purpose-specific original art sits above the backdrop and outside the
    # content flow; it cannot create an empty column or determine card height.
    c.emit('LoadConstNull',32);c.get(50,38,'icon');no_art=c.branch('JmpFalseLong',50)
    c.emit('NewObject',51);c.put(51,50,'uri')
    c.node(32,'Image',None,{'position':'absolute','top':16,'right':16,'width':32,'height':26},extra={'resizeMode':'contain'})
    c.put(24,51,'source');c.get(26,14,'Image');c.emit('Call3',32,2,3,26,24)
    c.finish(no_art)
    c.get(4, 38, 'title')
    c.node(7, 'Text', 4, {'color':'#333333','marginBottom':8,'paddingRight':36,'flexShrink':1}, typography='onlineTimeTitle')
    c.get(4, 38, 'subtitle')
    c.node(8, 'Text', 4, {'color':'#595959','flex':1},
           extra={'numberOfLines':2}, typography='enterButtonText')
    c.emit('LoadConstNull', 15)
    c.get(20, 38, 'state')
    c.string(21, 'running'); running = c.branch('JStrictEqual', 20, 21)
    c.string(21, 'starting'); starting = c.branch('JStrictEqual', 20, 21)
    idle = c.branch('JmpLong')
    c.finish(running); c.finish(starting)
    c.node(15, 'ActivityIndicator', None, {'width':16,'height':16,'marginRight':8},
           extra={'animating':True,'size':'small','color':'#25bcbf'})
    c.finish(idle)
    _array(c, 16, (15,8))
    c.node(8, 'View', 16, {'flexDirection':'row','alignItems':'center','marginBottom':10})
    c.get(50,38,'kind');c.string(51,'bandwidth');not_rank=c.branch('JStrictNotEqualLong',50,51)
    c.get(50,38,'subtitle');has_subtitle=c.branch('JmpTrueLong',50)
    c.emit('LoadConstNull',8)
    c.finish(has_subtitle);c.finish(not_rank)
    # Older persisted speed cards contain only three readings. Add an unknown
    # jitter cell in this parsed render object; never fabricate a measurement.
    c.get(50,38,'kind');c.string(51,'speedtest');not_speed=c.branch('JStrictNotEqualLong',50,51)
    c.get(52,38,'metrics');c.emit('GetGlobalObject',53);c.get(53,53,'Array');c.get(54,53,'isArray')
    c.emit('Call2',54,54,53,52);not_array=c.branch('JmpFalseLong',54)
    c.get(53,52,'length');c.number(54,3);not_old=c.branch('JStrictNotEqualLong',53,54)
    c.emit('NewObject',54);c.string(55,'抖动');c.put(54,55,'label')
    c.emit('LoadConstNull',55);c.put(54,55,'value');c.string(55,'ms');c.put(54,55,'unit')
    c.get(53,52,'push');c.emit('Call2',53,53,52,54)
    c.finish(not_old);c.finish(not_array);c.finish(not_speed)
    c.number(39, 4)
    _map(c, 38, 'metrics', 4, METRIC, 35, 6, limit_register=39)
    c.node(6, 'View', 6, {'flexDirection':'row','flexWrap':'wrap','marginHorizontal':-4})
    c.number(39,12)
    c.get(50,38,'kind');c.string(51,'bandwidth')
    other_kind=c.branch('JStrictNotEqualLong',50,51)
    c.get(52,38,'rows');c.emit('GetGlobalObject',53);c.get(53,53,'Array');c.get(54,53,'isArray')
    c.emit('Call2',54,54,53,52);invalid_rows=c.branch('JmpFalseLong',54)
    c.get(39,52,'length')
    c.finish(invalid_rows);c.finish(other_kind)
    _map(c, 38, 'rows', 12, ROW, 35, 9, limit_register=39)
    c.emit('LoadConstNull', 15)
    c.get(20, 38, 'kind'); c.string(21, 'speedtest')
    ordinary = c.branch('JStrictNotEqualLong', 20, 21)
    # A router-WAN test has one four-reading grid. Additional interface rows
    # and a separate latency footer would duplicate or unbalance the card.
    c.emit('NewArray', 9, 0)
    c.finish(ordinary)
    c.get(50, 38, 'kind'); c.string(51, 'daily')
    not_daily_rows = c.branch('JStrictNotEqualLong', 50, 51)
    c.emit('LoadConstNull', 9)
    c.finish(not_daily_rows)
    _map(c, 38, 'actions', 3, ACTION, 35, 10)
    c.get(40,10,'length');has_actions=c.branch('JmpTrueLong',40)
    c.emit('LoadConstNull',10);no_actions=c.branch('JmpLong')
    c.finish(has_actions);c.node(10, 'View', 10, {'flexDirection':'row','flexWrap':'wrap','marginTop':12});c.finish(no_actions)
    c.emit('LoadConstNull', 65)
    c.get(50, 38, 'kind'); c.string(51, 'daily')
    not_daily = c.branch('JStrictNotEqualLong', 50, 51)
    c.emit('Mov', 63, 35)
    c.emit('CreateClosure', 64, 63, DAILY)
    c.emit('Call2', 65, 64, 3, 38)
    c.finish(not_daily)
    _array(c, 12, (11,32,7,8,6,15,65,9,10))
    c.emit('NewObject', 24)
    c.style(25, {'position':'relative','borderRadius':16,'overflow':'hidden',
                 'padding':20,'paddingBottom':24})
    # Match the width of the retained promotional card across every new kind.
    c.get(60,14,'Dimensions');c.get(61,60,'get');c.string(62,'window')
    c.emit('Call2',60,61,60,62);c.get(61,60,'width')
    c.emit('LoadConstDouble',62,0.80);c.emit('Mul',5,61,62)
    c.put(25,5,'width');c.put(24,25,'style');c.put(24,12,'children')
    c.put(24,58,'source');c.string(29,'cover');c.put(24,29,'resizeMode')
    c.get(26,14,'ImageBackground');c.emit('Call3',13,2,3,26,24)
    c.emit('Ret', 13)
    return c


def _report_typography(reader):
    """Extend SignalCard's live theme factory with original report styles.

    Baseline F36181 / user-original F36201 onlineTimeNum/Unit/Title are identical
    (assistant-typography/styles.json). Keep the exact theme tokens, weights and
    line-height expressions; do not choose independent numeric font sizes.
    """
    save=C(reader); save.emit('LoadParam', 10, 1)
    c=C(reader,reader.function_headers[35859].highestReadCacheIndex)
    c.get(11,10,'color_text_paragraph')
    for name, token, weight, line in (
            ('onlineTimeNum','font_size_heading_xl','500',True),
            ('onlineTimeUnit','font_size_caption_sm',None,True),
            ('onlineTimeTitle','font_size_subhead','500',False)):
        c.get(12,10,token); c.emit('NewObject',14)
        c.put(14,11,'color'); c.put(14,12,'fontSize')
        if line:
            c.emit('LoadConstDouble',13,1.4); c.emit('Mul',13,13,12)
            c.put(14,13,'lineHeight')
        if weight:
            c.string(15,weight); c.put(14,15,'fontWeight')
        c.put(0,14,name)
    body,_,_=rewrite(reader,35859,{0:bytes(save.raw),0x111:bytes(c.raw)},{})
    return body,c.cache


def additions(reader, data, changed, changes):
    assert reader.header.version == 90
    for fid,size in ((35691,1708),(35856,804),(35859,280),(35773,681)):
        assert reader.function_headers[fid].bytecodeSizeInBytes==size,(fid,'unexpected prior patch')
    index = json.loads((Path(__file__).resolve().parents[2]/
        'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'))
    by_id = {f['id']: f for f in index}
    retired, todo = set(), [20053]
    while todo:
        fid = todo.pop()
        if fid in retired: continue
        retired.add(fid); todo.extend(by_id[fid]['f'])
    assert set(FIDS) <= retired
    for fid in FIDS:
        assert all(f['id'] in retired for f in index if fid in f['f'])
        assert [i for i,h in enumerate(reader.function_headers)
                if h.offset == reader.function_headers[fid].offset] == [fid]
    for sid in STRINGS:
        assert all(f['id'] in retired for f in index if sid in f['s']), sid
    assert_literal_ownership(reader,set(STRINGS),retired)
    # Register the result type with the existing SignalCard component at its
    # original mapping site. The shared component variable is still in r16.
    mapping = C(reader)
    mapping.emit('NewObject', 40); mapping.string(41, 'hubCard')
    mapping.put(40, 41, 'type'); mapping.put(40, 16, 'component')
    mapping.emit('PutOwnByIndex', 1, 40, 15)
    grow = C(reader); grow.emit('NewArray', 1, 16)
    factory, _, _ = rewrite(reader, 35691, {0x576: bytes(mapping.raw)},
        {0x526: ('NewArray', [1,15], bytes(grow.raw))})
    # Original receive/save/render uses only type + JSON messageDetail. Extra
    # server fields are not assumed to survive the original Redux message store.
    delegate = C(reader, reader.function_headers[35856].highestReadCacheIndex)
    delegate.emit('LoadParam', 32, 1); delegate.get(33, 32, 'message')
    absent = delegate.branch('JmpFalse', 33)
    delegate.get(34, 33, 'type'); delegate.string(31, 'hubCard')
    original = delegate.branch('JStrictNotEqual', 34, 31)
    delegate.emit('GetEnvironment', 30, 0)
    delegate.emit('CreateClosure', 31, 30, CARD)
    delegate.emit('LoadConstUndefined', 30)
    delegate.emit('Call2', 31, 31, 30, 32); delegate.emit('Ret', 31)
    delegate.finish(absent); delegate.finish(original)
    signal, _, _ = rewrite(reader, 35856, {0: bytes(delegate.raw)}, {})
    # Message already selects curProject through its original Redux selector.
    # Pass that exact project to result cards, so a stale/history card cannot
    # refresh or execute an action against a newly selected Helper project.
    scope=C(reader,reader.function_headers[35773].highestReadCacheIndex)
    scope.emit('LoadConstUndefined',28)
    missing=scope.branch('JmpFalse',4)
    scope.get(28,4,'projectId');scope.finish(missing)
    prop=C(reader);prop.put(0,28,'projectId')
    # The original text-bubble shell is white. Structured cards own their
    # complete surface; override only their shell, never shared style objects.
    shell=C(reader,scope.cache);shell.get(30,11,'type');shell.string(31,'hubCard')
    ordinary=shell.branch('JStrictNotEqualLong',30,31)
    shell.style(30,{'backgroundColor':'transparent','padding':0,'minHeight':0,'borderRadius':0})
    shell.emit('PutOwnByIndex',5,30,4);shell.finish(ordinary)
    message,_,_=rewrite(reader,35773,{0x8b:bytes(scope.raw),0x22b:bytes(shell.raw),0x289:bytes(prop.raw)},{})
    typography,typography_cache=_report_typography(reader)
    # Native charts accept an array valueFormatter, not a JS formatter callback.
    # HUB's linear series is normalized to positions 0..4 and supplies integer labels.
    # An older HUB without yTicks keeps the original axis configuration.
    axis = C(reader, reader.function_headers[33165].highestReadCacheIndex)
    no_data = axis.branch('JmpFalseLong', 29)
    axis.get(22, 29, 'yTicks')
    missing = axis.branch('JmpFalseLong', 22)
    axis.get(23, 12, 'right')
    axis.put(23, 22, 'valueFormatter')
    axis.number(24, 0); axis.put(23, 24, 'axisMinimum')
    axis.number(24, 4); axis.put(23, 24, 'axisMaximum')
    axis.number(24, 5); axis.put(23, 24, 'labelCount')
    axis.emit('LoadConstTrue', 24); axis.put(23, 24, 'labelCountForce')
    axis.finish(missing)
    axis.finish(no_data)
    chart, _, _ = rewrite(reader, 33165, {0x86b: bytes(axis.raw)}, {})
    built = {CARD:_card(reader), METRIC:_metric(reader), ROW:_row(reader),
             ACTION:_action(reader), PRESS:_press(reader), DAILY:_daily(reader),
             CHILD_ROW:_child_row(reader), YTICK:_ytick(reader), XLABEL:_xlabel(reader),
             YFMT:_yfmt(reader)}
    bodies = [factory, signal,typography,message,chart] + [bytes(built[fid].raw) for fid in FIDS]
    result, placed = insert_bodies(reader, data, bodies)
    small = reader.get_small_func_header_reader()
    for fid, location in zip((35691,35856,35859,35773,33165,*FIDS), placed):
        header = small.from_buffer(result, reader.functions_offset+fid*ctypes.sizeof(small))
        if header.overflowed:
            header = reader.get_large_func_header_reader().from_buffer(result, (header.infoOffset<<16)|header.offset)
        header.offset, header.bytecodeSizeInBytes = location
        if fid in FIDS:
            header.frameSize = 96
            header.paramCount = 1 if fid == PRESS else 3 if fid == ROW else 2
            header.environmentSize = 6 if fid == CARD else 1 if fid == ACTION else 0
            header.highestReadCacheIndex = built[fid].cache
            header.highestWriteCacheIndex = 0
            header.hasExceptionHandler = 0
        elif fid == 35856:
            header.frameSize = max(header.frameSize, 44)
            header.highestReadCacheIndex = delegate.cache
        elif fid == 35859:
            header.frameSize=max(header.frameSize,36)
            header.highestReadCacheIndex=typography_cache
        elif fid == 35773:
            header.frameSize=max(header.frameSize,48)
            header.highestReadCacheIndex=shell.cache
        elif fid == 33165:
            header.frameSize=max(header.frameSize,72)
            header.highestReadCacheIndex=max(header.highestReadCacheIndex,axis.cache)
        changed.add(fid)
        changes.append({'function':fid,'action':'structured assistant result card with original RN art and whitelisted actions'})
        del header
    return result
