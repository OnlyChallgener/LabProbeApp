"""Extend the existing identity and child icon tables with reviewed assets."""
import ctypes
import struct

from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops

# Slots audited against every instruction and literal buffer in the original
# bundle, and excluded from the current hub.26 changed-string manifest.
SLOTS = (15785,8571,15079,14408,10467,14388,15555,12734,14384,15229,15852,16921,
         12640,8924,10373,6413,15150,15783,16373,4323,16092,9519,18191,1898,
         10721,14538,15468,15708,16831,8932,9972,9998,12439,4664,16835,17126,
         820,1897,18608,2318,13886,7668,16363,16834,16851,20729,7657,1396,
         7201,7205,7214,7216,489,9587,2179)
BRANDS = [('360','360'),('aqara','Aqara'),('gigabyte','技嘉'),('google','Google'),
          ('ikuai','爱快'),('iqoo','iQOO'),('lenovo','联想'),('littlegenius','小天才'),
          ('netgear','网件'),('tenda','腾达'),('xiangrikui','向日葵'),('zte','中兴'),
          ('hisilicon_video','海思视讯'),('aupu','奥普'),('synology','群晖'),('anker','安克'),
          ('cuktech','酷态科'),('zidoo','芝杜'),('mitu','米兔')]
TYPES = [('smartplug','智能插座'),('netswitch','交换机'),('touchspeaker','触屏音箱'),
         ('bathheater','浴霸'),('lock','智能门锁'),('linux_pc','Linux电脑'),('mac_pc','Mac电脑')]
ITEMS = []
BRAND_PREFIX, DEVICE_PREFIX, SUFFIX = SLOTS[-2], SLOTS[-1], SLOTS[-3]
STRINGS = {BRAND_PREFIX:'asset:///hub-brandicons/', DEVICE_PREFIX:'asset:///hub-brandicons/device_', SUFFIX:'.png'}
assert 2*len(BRANDS+TYPES) <= len(SLOTS)-3, 'catalog string slots overlap URI constants'
for index,(key,title) in enumerate(BRANDS+TYPES):
    key_sid,title_sid = SLOTS[index*2:index*2+2]
    kind='brand' if index < len(BRANDS) else 'device'
    STRINGS.update({key_sid:key,title_sid:title})
    ITEMS.append((kind,key,title,key_sid,title_sid))


def additions(reader, data, changed, changes):
    # SID20729 was used only by the wholly replaced locale extension F23580;
    # it has no original object/array literal references (audited 2026-11-13).
    # SIDs7657/1396 belonged only to retired push APIs F20353/F20357;
    # final-combination instruction and all literal references were audited absent.
    # SID25623 was rejected: a live F8573 literal still owns that store URL key.
    assert reader.strings[20729] not in ('uri', '.png')
    uri_sid=reader.strings.index('uri')
    # Share the prefix and suffix instead of allocating a URI for every icon.
    def icon(key, prefix, target):
        return (bytes([ops.LoadConstString.opcode,71]) + struct.pack('<H',prefix)
            + bytes([ops.LoadConstString.opcode,72]) + struct.pack('<H',key)
            + bytes([ops.Add.opcode,71,71,72,ops.LoadConstString.opcode,72]) + struct.pack('<H',SUFFIX)
            + bytes([ops.Add.opcode,71,71,72,ops.NewObject.opcode,72])
            + bytes([ops.PutNewOwnById.opcode,72,71]) + struct.pack('<H',uri_sid)
            + bytes([ops.LoadConstString.opcode,71]) + struct.pack('<H',key)
            + bytes([ops.PutByVal.opcode,target,71,72]))
    exports={}
    for i in parse_hbc_bytecode(reader.function_headers[22183],reader):
        if i.inst.name == 'PutById' and reader.strings[i.arg4] in ('ManufacturerIcons','StaTypeIcons','ChildStaTypeIcons'):
            exports[reader.strings[i.arg4]]=(i.original_pos,i.arg2)
    assert set(exports)=={'ManufacturerIcons','StaTypeIcons','ChildStaTypeIcons'}
    insertions={}
    for export,(pos,target) in exports.items():
        chosen=[x for x in ITEMS if x[0] == ('brand' if export=='ManufacturerIcons' else 'device')]
        insertions[pos]=b''.join(icon(x[3],BRAND_PREFIX if x[0]=='brand' else DEVICE_PREFIX,target) for x in chosen)
    icon_body,_,_=rewrite(reader,22183,insertions,{})

    # Rebuild the retired locale extension with long null-guard branches.
    translations=bytearray([ops.LoadParam.opcode,0,1,ops.LoadConstUndefined.opcode,3,
                            ops.JmpFalseLong.opcode,0,0,0,0,0])
    guards=[5]
    for reg,sid,cache in ((1,25031,1),(2,33957,2)):
        translations += bytes([ops.GetById.opcode,reg,0,cache])+struct.pack('<H',sid)
        guards.append(len(translations))
        translations += bytes([ops.JmpFalseLong.opcode,0,0,0,0,reg])
    for reg,key_sid,title_sid in ((1,7156,20356),(1,7163,20357),(2,17308,20359)):
        translations += bytes([ops.LoadConstString.opcode,0])+struct.pack('<H',key_sid)
        translations += bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',title_sid)
        translations += bytes([ops.PutByVal.opcode,reg,0,4])
    for kind,_,_,key_sid,title_sid in ITEMS:
        reg=1 if kind=='brand' else 2
        translations += bytes([ops.LoadConstString.opcode,0])+struct.pack('<H',key_sid)
        translations += bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',title_sid)
        translations += bytes([ops.PutByVal.opcode,reg,0,4])
    end=len(translations)
    translations += bytes([ops.Ret.opcode,3])
    for guard in guards:
        struct.pack_into('<i',translations,guard+1,end-guard)
    translation_body=bytes(translations)
    # Child protection uses the broad `type` only. For our new appliance
    # subtypes, select the same specific icon as the terminal list while
    # leaving the original status-specific icons untouched for all others.
    child=Code(reader)
    absent=child.branch('JEqual',9,16)
    child.emit('GetById',80,9,118,reader.strings.index('typeLevel2'))
    matches=[]
    for item in ITEMS:
        if item[0]=='device':
            child.emit('LoadConstString',81,item[3])
            matches.append(child.branch('JStrictEqualLong',80,81))
    skip=child.branch('JmpLong')
    for branch in matches:child.finish(branch)
    child.emit('GetByVal',52,12,80)
    child.finish(skip);child.finish(absent)
    child_body,_,_=rewrite(reader,25081,{0x22d:bytes(child.raw)},{})
    # StaLogo originally chooses subtype artwork only for appliances.  Keep
    # the broad type as PC (desktop controls depend on it), but let the two
    # explicit computer subtypes take the existing subtype rendering path.
    logo=Code(reader)
    no_device=logo.branch('JEqual',8,7)
    logo.emit('GetById',80,8,119,reader.strings.index('typeLevel2'))
    special=[]
    for item in ITEMS:
        if item[1] in ('linux_pc','mac_pc'):
            logo.emit('LoadConstString',81,item[3])
            special.append(logo.branch('JStrictEqualLong',80,81))
    normal=logo.branch('JmpLong')
    for branch in special:logo.finish(branch)
    logo.emit('Mov',16,4)
    logo.finish(normal);logo.finish(no_device)
    logo_body,_,_=rewrite(reader,22710,{0x133:bytes(logo.raw)},{})
    result, placed=insert_bodies(reader,data,[icon_body,translation_body,child_body,logo_body])
    small=reader.get_small_func_header_reader()
    for fid,location in zip((22183,23580,25081,22710),placed):
        header=small.from_buffer(result,reader.functions_offset+fid*ctypes.sizeof(small))
        if header.overflowed:
            header=reader.get_large_func_header_reader().from_buffer(result,(header.infoOffset<<16)|header.offset)
        header.offset,header.bytecodeSizeInBytes=location
        if fid==23580:
            header.hasExceptionHandler=0
        if fid==25081:
            # Two temporary registers plus the implicit outgoing call frame.
            header.frameSize=max(header.frameSize,96)
            header.highestReadCacheIndex=max(header.highestReadCacheIndex,118)
        if fid==22710:
            header.frameSize=max(header.frameSize,96)
            header.highestReadCacheIndex=max(header.highestReadCacheIndex,119)
        changed.add(fid)
        changes.append({'function':fid,'action':'extend shared device and manufacturer icon catalog'})
        del header
    return result
