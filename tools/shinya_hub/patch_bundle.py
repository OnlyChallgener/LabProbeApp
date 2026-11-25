"""Patch the inspected 2026-10-08 Hermes v90 bundle; fail closed on other builds.

No string or function table is resized. Reallocated text uses only storage no
remaining string references. All untouched strings and instruction bodies are
checked after the patch.
"""
import argparse
import hashlib
import json
import os
import struct
import ctypes
from io import BytesIO
from pathlib import Path

from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops

SOURCE_SHA = "673845c79b7a4c4e18aa80d7ead95fd7e06bc3523e29decb2b2ecca064f763a5"


class Reader(HBCReader):
    def read_functions(self):
        self.functions_offset = self.file_buffer.tell()
        super().read_functions()

    def read_small_string_table(self):
        self.align_over_padding()
        self.table_offset = self.file_buffer.tell()
        super().read_small_string_table()

    def read_string_storage(self):
        self.align_over_padding()
        self.storage_offset = self.file_buffer.tell()
        super().read_string_storage()


def load(data):
    reader = Reader()
    reader.read_whole_file(BytesIO(data))
    return reader


def patch(source, output):
    original = source.read_bytes()
    if hashlib.sha256(original).hexdigest() != SOURCE_SHA:
        raise ValueError("APK bundle differs from the inspected build")
    reader = load(original)
    assert reader.header.version == 90
    data = bytearray(original)
    changes = []
    changed_functions = set()

    # The official reverse mapping incorrectly labels 50% power as mode 2.
    # Change only its private literal; the original mode selector stays intact.
    power_map = reader.object_values[98457:98478]
    assert power_map == bytes([0x75])+struct.pack('<5i',3,3,2,2,2)
    references=[]
    for fid,h in enumerate(reader.function_headers):
        for item in parse_hbc_bytecode(h,reader):
            if item.inst.name in {'NewObjectWithBuffer','NewObjectWithBufferLong'} and 98457<=item.arg5<98478:
                references.append(fid)
    assert references==[28287],references
    values_start=original.find(reader.object_values)
    assert values_start>=0 and original.find(reader.object_values,values_start+1)<0
    struct.pack_into('<i',data,values_start+98457+13,1)
    changes.append({'function':28287,'action':'read 50 percent power as the original low-power mode'})

    # ProjectListModal's two-project sheet inherited a 214 dp minimum height,
    # leaving a conspicuous empty block below BE50. Keep a small minimum for a
    # single project while letting the sheet fit the two actual rows.
    project_sheet=reader.object_values[62887:62920]
    assert project_sheet[0]==0x76 and struct.unpack_from('<i',project_sheet,1)[0]==214
    project_refs=[fid for fid,h in enumerate(reader.function_headers)
                  for item in parse_hbc_bytecode(h,reader)
                  if item.inst.name in {'NewObjectWithBuffer','NewObjectWithBufferLong'}
                  and item.arg5==62887]
    assert project_refs==[22055,22095],project_refs  # module initializer and getStyles
    struct.pack_into('<i',data,values_start+62888,160)
    changes.append({'function':22095,'action':'fit project switch sheet to its rows'})

    def instruction(fid, pos, name, expected, replacements):
        header = reader.function_headers[fid]
        inst = next(i for i in parse_hbc_bytecode(header, reader) if i.original_pos == pos)
        assert inst.inst.name == name, (fid, pos, inst)
        assert [getattr(inst, "arg" + str(n + 1)) for n in range(len(expected))] == expected
        start = header.offset + pos
        for byte_index, value in replacements.items():
            data[start + byte_index] = value
        changed_functions.add(fid)
        changes.append({"function": fid, "offset": pos, "action": name})

    # Account/password mode now means the Hub's own account; the official consent and
    # third-party login controls are removed from this private Hub variant.
    instruction(24731, 0xa4, "LoadConstFalse", [21], {0: ops.LoadConstTrue.opcode})
    # r4 is the numeric array index 0, not Boolean false. Passing it to the
    # loading state makes React Native reject accessibilityState.disabled.
    # Keep useState in the unused r24, then use the redundant five-byte lookup
    # to load actual false and move the saved function into r8. The password
    # and consent states stay true; loading starts with a Boolean false.
    instruction(24731, 0x9f, "GetByIdShort", [5, 19, 1, 248], {1: 24})
    instruction(24731, 0xa6, "Call2", [8, 5, 20, 21], {2: 24})
    instruction(24731, 0xe3, "GetByIdShort", [8, 19, 1, 248],
                dict(enumerate([ops.LoadConstFalse.opcode, 21, ops.Mov.opcode, 8, 24])))
    for pos, expected in [(0x6b6, [13, 16, 4]), (0x7c6, [13, 6, 6]), (0xaa2, [13, 9, 7])]:
        instruction(24694, pos, "PutOwnByIndex", expected, {2: 3})
    instruction(24694, 0xaf5, "PutOwnByIndex", [4, 0, 2], {2: 3})  # consent overlay
    # Retain the game tab for UU, application centre and STUN penetration.
    # Retain the original tools tab. Removing it hid the remaining capabilities
    # and made the earlier client appear to be an empty shell after login.
    instruction(23554, 0x67f, "PutOwnByIndex", [4, 0, 1], {2: 3})  # assistant/tutorial promotion
    # Retain tools assistant banner for XiaoXing V2.0 dynamic alerts
    instruction(27915, 0x439, "PutOwnByIndex", [4, 8, 1], {2: 3})  # official password reset button
    # UserInfo maps infoList objects; remove phone/WeChat at the actual source
    # array rather than passing an undefined entry to the list renderer.
    instruction(27915, 0xd3, "NewArray", [7, 4], {2: 2})
    for pos in (0x153, 0x193):
        instruction(27915, pos, "PutOwnByIndex", [7, 17, 2 if pos == 0x153 else 3],
                    dict(enumerate([ops.LoadConstUndefined.opcode, 17,
                                    ops.LoadConstUndefined.opcode, 17])))
    instruction(27915, 0x56d, "PutOwnByIndex", [4, 0, 4], {2: 3})  # delete official account
    # Keep useWirelessConfig and its native query intact. It is enabled by the
    # selected router SN, including remote Hub access. Without it the home card
    # falls back to the AP's name, loses enable/linkSta and hides IoT SSIDs.

    # Stability radar contains only this project's measurements. Remove the
    # nationwide series from the dense dataSets array, including its legend.
    instruction(33205, 0xf4, "NewArray", [3, 2], {2: 1})
    instruction(33205, 0xf8, "PutOwnByIndex", [3, 2, 0],
                dict(enumerate([ops.LoadConstUndefined.opcode, 2,
                                ops.LoadConstUndefined.opcode, 2])))
    instruction(33205, 0x155, "PutOwnByIndex", [3, 2, 1], {3: 0})

    # The current scope retains remote desktop, WEB configuration and WEB sync.
    # Preserve their original dense menu arrays and navigation callbacks.

    def replace_function(fid, code, reason):
        header = reader.function_headers[fid]
        assert len(code) <= header.bytecodeSizeInBytes
        assert not any(i.inst.name == "StartGenerator" for i in parse_hbc_bytecode(header, reader))
        data[header.offset:header.offset + header.bytecodeSizeInBytes] = code + bytes(
            [ops.Unreachable.opcode] * (header.bytecodeSizeInBytes - len(code)))
        changed_functions.add(fid)
        changes.append({"function": fid, "action": reason})

    # Startup originally trusted AsyncStorage.TOKEN before it contacted HUB.
    # Return a bridge handle only after native account/session verification.
    # Startup awaits token, then expiry (F7246). Both must describe the native
    # session: a refreshed token plus expired AsyncStorage time forced sign-in.
    for fid,method in ((8364,'getToken'),(8369,'getExpireTime')):
        if os.environ.get("SHINYA_A_B") == "no-token-bridge":
            break
        method_sid=reader.strings.index(method)
        boot=bytearray([ops.GetGlobalObject.opcode,0,ops.GetById.opcode,1,0,1])+struct.pack('<H',31986)
        boot+=bytes([ops.LoadConstUndefined.opcode,2,ops.LoadConstUInt8.opcode,3,18,ops.Call2.opcode,0,1,2,3])
        for sid in (22921,22266,method_sid):
            boot+=bytes([ops.GetById.opcode,1,0,1])+struct.pack('<H',sid)
            if sid!=method_sid:boot+=bytes([ops.Mov.opcode,0,1])
        boot+=bytes([ops.Call1.opcode,0,1,0,ops.Ret.opcode,0])
        replace_function(fid,bytes(boot),'restore startup '+method+' from the verified native session')

    # When the native rule disappears, the original effect returns early and
    # leaves the last preset selected. Reinitialize the existing state setter
    # to the unlimited option; keep the original custom/preset matching.
    rate_selection=bytearray([ops.GetEnvironment.opcode,0,0,
        ops.LoadFromEnvironment.opcode,1,0,2,ops.JmpFalse.opcode,0,1])
    hidden_jump=len(rate_selection)-3
    rate_selection+=bytes([ops.LoadFromEnvironment.opcode,1,0,15,
        ops.LoadConstZero.opcode,2,ops.JmpFalse.opcode,0,1])
    no_rule_jump=len(rate_selection)-3
    rate_selection+=bytes([ops.LoadFromEnvironment.opcode,3,0,14,
        ops.GetById.opcode,2,3,1])+struct.pack('<H',31391)
    rate_selection+=bytes([ops.CreateEnvironment.opcode,1,
        ops.CreateClosure.opcode,1,1])+struct.pack('<H',33398)
    rate_selection+=bytes([ops.Call2.opcode,2,2,3,1,
        ops.LoadConstInt.opcode,1])+struct.pack('<i',-1)
    rate_selection+=bytes([ops.JStrictNotEqual.opcode,0,2,1])
    matched_jump=len(rate_selection)-4
    rate_selection+=bytes([ops.GetByIdShort.opcode,3,3,2,167,
        ops.LoadConstUInt8.opcode,1,1,ops.Sub.opcode,2,3,1])
    select=len(rate_selection)
    for jump in (no_rule_jump,matched_jump):rate_selection[jump+1]=select-jump
    rate_selection+=bytes([ops.LoadFromEnvironment.opcode,1,0,4,
        ops.LoadConstUndefined.opcode,0,ops.Call2.opcode,0,1,0,2])
    rate_selection[hidden_jump+1]=len(rate_selection)-hidden_jump
    rate_selection+=bytes([ops.LoadConstUndefined.opcode,0,ops.Ret.opcode,0])
    replace_function(33397,bytes(rate_selection),'clear stale preset after removing native rate rule')

    replace_function(10493,bytes([ops.LoadConstUndefined.opcode,0,ops.Ret.opcode,0]),
                     'erase official error-reporting SDK initialization')

    # The remote-games route now hosts the STUN tool directly. Its former
    # SteamLink provider/hook is never mounted or queried.
    # Both legacy route names resolve to the same native STUN screen. r3 is
    # ShinyaLink's module export, before the route object reuses that register.
    instruction(27888,0x50a,'GetByIdShort',[6,5,3,111],{2:3})
    replace_function(32801,bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0]),
                     'remove retired remote-game preparation and download renderer')
    wrapper = bytes([ops.GetEnvironment.opcode,0,0,ops.LoadFromEnvironment.opcode,1,0,2,
        ops.GetByIdShort.opcode,2,1,1,108,ops.LoadFromEnvironment.opcode,4,0,8,
        ops.LoadParam.opcode,5,1,ops.LoadConstUndefined.opcode,3,
        ops.Call3.opcode,0,2,3,4,5,ops.Ret.opcode,0])
    replace_function(32803,wrapper,'remove Steam remote game provider from route')

    def native_surface(rn_slot, react_slot, cache_slot, tag_sid, sn_code=b''):
        code = bytearray([ops.GetEnvironment.opcode,0,0,ops.LoadConstUndefined.opcode,3,
            ops.LoadFromEnvironment.opcode,4,0,cache_slot,ops.JmpTrue.opcode,0,4])
        jump=len(code)-3
        code += bytes([ops.LoadFromEnvironment.opcode,1,0,rn_slot,ops.GetById.opcode,2,1,1])+struct.pack('<H',37159)
        code += bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',tag_sid)
        code += bytes([ops.Call2.opcode,4,2,3,4,ops.StoreToEnvironment.opcode,0,cache_slot,4])
        code[jump+1]=len(code)-jump
        code += bytes([ops.LoadFromEnvironment.opcode,1,0,react_slot,ops.GetByIdShort.opcode,2,1,1,108,
            ops.NewObject.opcode,8,ops.NewObject.opcode,9,ops.LoadConstUInt8.opcode,10,1,
            ops.PutNewOwnByIdShort.opcode,9,10,126,ops.PutNewOwnByIdShort.opcode,8,9,233])
        code += sn_code
        code += bytes([ops.Call3.opcode,0,2,3,4,8,ops.Ret.opcode,0])
        return bytes(code)

    # These two messages belong exclusively to erased assistant functions.
    # The retired service URLs are already reserved for UGREEN/fnOS/NAS;
    # live child-policy text must also keep its own independent IDs.
    login_tag=8096
    stun_tag=8085
    account_tag=next(i for i,s in enumerate(reader.strings) if s.startswith('https://support.qq.com/product/318572/'))
    # Keep the original login hook, Redux token dispatch and navigation. Only
    # its visible form changes. Native credentials never enter the JS store.
    def auth_surface(rn_slot,react_slot,hook_slot,tag_sid,login):
        c=bytearray([ops.CreateEnvironment.opcode,10,ops.GetEnvironment.opcode,0,0,
            ops.LoadConstUndefined.opcode,3,ops.LoadFromEnvironment.opcode,1,0,hook_slot,
            ops.GetByIdShort.opcode,1,1,1,111,ops.Call1.opcode,11,1,3,
            ops.LoadFromEnvironment.opcode,2,0,react_slot,
            ops.GetGlobalObject.opcode,6,ops.LoadConstString.opcode,7])+struct.pack('<H',tag_sid)+bytearray([
            ops.GetByVal.opcode,4,6,7,
            ops.JmpTrue.opcode,0,4])
        jump=len(c)-3
        c+=bytes([ops.LoadFromEnvironment.opcode,1,0,rn_slot,ops.GetById.opcode,1,1,1])+struct.pack('<H',37159)
        c+=bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',tag_sid)
        # Native view registration belongs to the JS runtime, not to a screen
        # instance. A useRef cache is lost on remount and registers the same
        # native view twice. The dynamic global key also avoids a write cache
        # index beyond the original login function's allocated cache capacity.
        c+=bytes([ops.Call2.opcode,4,1,3,4,ops.PutByVal.opcode,6,7,4])
        c[jump+1]=len(c)-jump
        c+=bytes([ops.NewObject.opcode,8,ops.NewObject.opcode,9,ops.LoadConstUInt8.opcode,7,1,
            ops.PutNewOwnByIdShort.opcode,9,7,126,ops.PutNewOwnByIdShort.opcode,8,9,233])
        if login:
            for slot,sid in [(0,40438),(1,25113)]:
                c+=bytes([ops.GetById.opcode,12,11,1])+struct.pack('<H',sid)+bytes([ops.StoreToEnvironment.opcode,10,slot,12])
            c+=bytes([ops.CreateClosure.opcode,12,10])+struct.pack('<H',24709)
            c+=bytes([ops.PutNewOwnById.opcode,8,12])+struct.pack('<H',32965)
            for prop,source in [(27620,44377),(37495,37495)]:
                c+=bytes([ops.GetById.opcode,12,11,1])+struct.pack('<H',source)+bytes([ops.PutNewOwnById.opcode,8,12])+struct.pack('<H',prop)
        else:
            for prop,source in [(32965,42882),(27620,25104)]:
                c+=bytes([ops.GetById.opcode,12,11,1])+struct.pack('<H',source)+bytes([ops.PutNewOwnById.opcode,8,12])+struct.pack('<H',prop)
            c+=bytes([ops.GetById.opcode,12,11,1])+struct.pack('<H',33933)
            c+=bytes([ops.JmpFalse.opcode,13,12,ops.GetById.opcode,12,12,1])+struct.pack('<H',51702)+bytes([ops.PutNewOwnByIdShort.opcode,8,12,163])
        c+=bytes([ops.GetByIdShort.opcode,1,2,1,108,ops.Call3.opcode,0,1,3,4,8,ops.Ret.opcode,0])
        return bytes(c)
    change=bytearray([ops.GetEnvironment.opcode,0,0,ops.LoadParam.opcode,1,1,
        ops.GetByIdShort.opcode,1,1,1,189,ops.LoadConstUndefined.opcode,2])
    for slot,sid in [(0,25096),(1,25884)]:
        change+=bytes([ops.LoadFromEnvironment.opcode,3,0,slot,ops.GetById.opcode,4,1,1])+struct.pack('<H',sid)+bytes([ops.Call2.opcode,5,3,2,4])
    change+=bytes([ops.Ret.opcode,2])
    replace_function(24709,bytes(change),'native login field events update the original account/password hook')
    replace_function(24694,auth_surface(4,3,9,login_tag,True),'HUB address and real account login form')
    replace_function(27915,auth_surface(4,3,8,account_tag,False),'own account details with old-password verification and logout')

    # This string is referenced only by the unmounted SteamLink generator.
    # Keep DiagnoseProvider's current-project selector and the route wrapper.
    diagnosis_tag=18767
    assert reader.strings[diagnosis_tag]=='steamlink://x'
    # useDiagnose's public result omits project. Read the retained provider's
    # context directly, whose project comes from network.curProject. This keeps
    # the hook contract intact and never falls back to another router.
    provider_export = next(i for i in parse_hbc_bytecode(reader.function_headers[27985],reader)
                           if i.original_pos == 0x105)
    assert provider_export.inst.name == 'PutById' and provider_export.arg4 == 111
    c=bytearray([ops.GetEnvironment.opcode,0,0,ops.LoadConstUndefined.opcode,3,
        ops.LoadFromEnvironment.opcode,2,0,2,ops.LoadFromEnvironment.opcode,1,0,0,
        ops.LoadFromEnvironment.opcode,5,0,1,ops.LoadConstUInt8.opcode,4,16,
        ops.GetByVal.opcode,4,5,4,ops.Call2.opcode,4,1,3,4,
        ops.GetByIdShort.opcode,4,4,1,111,ops.GetById.opcode,1,2,1])+struct.pack('<H',50918)
    c+=bytes([ops.Call2.opcode,11,1,3,4,
        ops.GetGlobalObject.opcode,6,ops.LoadConstString.opcode,7])+struct.pack('<H',diagnosis_tag)
    c+=bytes([ops.GetByVal.opcode,4,6,7,ops.JmpTrue.opcode,0,4]);jump=len(c)-3
    c+=bytes([ops.LoadFromEnvironment.opcode,1,0,3,ops.GetById.opcode,1,1,1])+struct.pack('<H',37159)
    c+=bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',diagnosis_tag)
    c+=bytes([ops.Call2.opcode,4,1,3,4,ops.PutByVal.opcode,6,7,4]);c[jump+1]=len(c)-jump
    c+=bytes([ops.NewObject.opcode,8,ops.NewObject.opcode,9,ops.LoadConstUInt8.opcode,7,1,
        ops.PutNewOwnByIdShort.opcode,9,7,126,ops.PutNewOwnByIdShort.opcode,8,9,233,
        ops.GetById.opcode,12,11,1])+struct.pack('<H',22625)
    c+=bytes([ops.GetByIdShort.opcode,12,12,1,210,ops.PutNewOwnByIdShort.opcode,8,12,210,
        ops.GetByIdShort.opcode,1,2,1,108,ops.Call3.opcode,0,1,3,4,8,ops.Ret.opcode,0])
    replace_function(27972,bytes(c),'full-page native firmware test cards in current project')

    # Cache requireNativeComponent in the removed hook's environment slot;
    # registering a native view a second time on re-render is not permitted.
    instruction(32831,0xdb,'StoreToEnvironment',[1,7,4],{3:0})
    sn_code = bytearray([ops.LoadFromEnvironment.opcode,5,0,0,ops.LoadFromEnvironment.opcode,6,0,1,
        ops.LoadConstUInt8.opcode,7,11,ops.GetByVal.opcode,7,6,7,ops.Call2.opcode,7,5,3,7,
        ops.GetById.opcode,7,7,1])+struct.pack('<H',50923)
    sn_code += bytes([ops.Call1.opcode,7,7,3,ops.GetById.opcode,7,7,1])+struct.pack('<H',47927)
    sn_code += bytes([ops.JmpFalse.opcode,12,7,ops.GetByIdShort.opcode,7,7,1,198,
                     ops.PutNewOwnByIdShort.opcode,8,7,198])
    replace_function(32835,native_surface(3,2,7,stun_tag,sn_code),'Star UI with actual STUN rules and actions')

    # Preserve RouterUpgrade, its original styles/animations and the selected
    # project's UpgradeManager. HUB adapts the original detection endpoints to
    # the firmware beta getter; this page must never use an account-wide picker.

    # checkUpgrade's own predicate (F24054) stays original: it counts only the
    # channel the project is on (upDateVersionCode from getBetaStatus), so a
    # beta router is never dotted for an official update. The page row filter
    # (F32277) and upgrade-all filter (F24070) use the same per-channel rule.

    # Use HUB's measured radar dimensions. Unsupported cloud QoE/house scores
    # must not become zero-valued spokes. The original component still renders
    # the returned chart contract, with one local dataset and no national mean.
    radar = bytearray([ops.GetEnvironment.opcode,0,0,
        ops.LoadFromEnvironment.opcode,0,0,0,ops.JmpFalse.opcode,0,0,
        ops.GetByIdShort.opcode,0,0,1,110,ops.JmpTrue.opcode,0,0])
    empty=len(radar)
    radar += bytes([ops.NewObject.opcode,0,ops.NewObject.opcode,1,
                    ops.NewArray.opcode,2,0,0,ops.PutNewOwnById.opcode,1,2])+struct.pack('<H',24665)
    radar += bytes([ops.PutNewOwnByIdShort.opcode,0,1,110,ops.NewObject.opcode,1,
                    ops.PutNewOwnById.opcode,1,2])+struct.pack('<H',51721)
    radar += bytes([ops.PutNewOwnById.opcode,0,1])+struct.pack('<H',35054)
    returned=len(radar)
    radar += bytes([ops.Ret.opcode,0])
    radar[8]=empty-7;radar[16]=returned-15
    replace_function(33205,bytes(radar),'render only observed HUB stability dimensions')

    # Keep the official diagnosis provider, progress animation, result text
    # styles and actions. Only replace its four asynchronous probes. React
    # Native is module 18 in this exact audited Metro bundle.
    def diagnosis_generator(method, threshold=None, result=False):
        c=bytearray([ops.StartGenerator.opcode,ops.ResumeGenerator.opcode,0,1,
                     ops.JmpTrue.opcode,0,1])
        first_jump=4
        c+=bytes([ops.GetEnvironment.opcode,2,1,ops.LoadFromEnvironment.opcode,3,2,0,
                  ops.GetByIdShort.opcode,3,3,1,210,
                  ops.GetEnvironment.opcode,4,2,ops.LoadFromEnvironment.opcode,5,4,0])
        c+=bytes([ops.LoadConstUInt8.opcode,6,18,ops.LoadConstUndefined.opcode,4,
                  ops.Call2.opcode,5,5,4,6,ops.GetById.opcode,5,5,1])+struct.pack('<H',22921)
        c+=bytes([ops.GetById.opcode,5,5,1])+struct.pack('<H',33692)
        c+=bytes([ops.GetById.opcode,6,5,1])+struct.pack('<H',method)
        if threshold is None:c+=bytes([ops.Call2.opcode,0,6,5,3])
        else:c+=bytes([ops.LoadConstUInt8.opcode,4,threshold,ops.Call3.opcode,0,6,5,3,4])
        c+=bytes([ops.SaveGenerator.opcode,4,ops.Ret.opcode,0,
                  ops.ResumeGenerator.opcode,0,1,ops.JmpTrue.opcode,0,1])
        second_jump=len(c)-3
        if result:
            c+=bytes([ops.GetGlobalObject.opcode,3,ops.TryGetById.opcode,4,3,1])+struct.pack('<H',reader.strings.index('JSON'))
            c+=bytes([ops.GetById.opcode,3,4,1])+struct.pack('<H',reader.strings.index('parse'))
            c+=bytes([ops.Call2.opcode,0,3,4,0,
                      ops.LoadFromEnvironment.opcode,3,2,3,ops.GetByIdShort.opcode,3,3,1,109,
                      ops.JmpTrue.opcode,14,3,
                      ops.LoadFromEnvironment.opcode,3,2,2,ops.LoadConstUndefined.opcode,4,
                      ops.Call2.opcode,0,3,4,0])
        done=len(c);c+=bytes([ops.CompleteGenerator.opcode,ops.Ret.opcode,0])
        c[first_jump+1]=done-first_jump;c[second_jump+1]=done-second_jump
        return bytes(c)

    # The original provider executes slots 5, 6, 4, 7 in that order, not the
    # function-ID order. Start the firmware job before polling its progress.
    for fid,method,threshold,result in [(27995,25678,None,False),(27997,27486,40,False),
                                        (27993,27486,100,False),(27999,32404,None,True)]:
        h=reader.function_headers[fid];code=diagnosis_generator(method,threshold,result)
        assert len(code)<=h.bytecodeSizeInBytes,(fid,len(code),h.bytecodeSizeInBytes)
        assert next(iter(parse_hbc_bytecode(h,reader))).inst.name=='StartGenerator'
        data[h.offset:h.offset+h.bytecodeSizeInBytes]=code+bytes([ops.Unreachable.opcode]*(h.bytecodeSizeInBytes-len(code)))
        # The old ping functions catch errors at offsets belonging to their
        # erased bodies. Remove those handlers; native diagnosis always settles
        # its promise, and unexpected JS errors reach the provider's outer catch.
        # Hermes writes the outgoing call frame into the top of this frame.
        # r0-r6 need seven locals plus six frame slots and three arguments.
        # The old 13-slot ping frames let Call2 overwrite the new r5 callee.
        small=reader.get_small_func_header_reader()
        raw=small.from_buffer(data,reader.functions_offset+fid*ctypes.sizeof(small))
        if raw.overflowed:
            location=(raw.infoOffset<<16)|raw.offset
            metadata=reader.get_large_func_header_reader().from_buffer(data,location)
            metadata.hasExceptionHandler=0
            metadata.frameSize=max(metadata.frameSize,17)
        else:
            raw.hasExceptionHandler=0
            raw.frameSize=max(raw.frameSize,17)
        changed_functions.add(fid);changes.append({'function':fid,'action':'use project-scoped firmware diagnosis'})

    # Firmware diagnosis remains available with the retired assistant disabled.
    instruction(24026,0x157,'StrictNeq',[3,3,4],
                dict(enumerate([ops.LoadConstFalse.opcode,3,ops.LoadConstFalse.opcode,3])))

    # Zero is a measured rate. The original `value || '--'` rendered a quiet
    # WAN link as missing data. Keep the original card and use a nullish guard.
    for fid,cut,prop,guard,fallback,value_reg,data_reg,null_reg in (
            (24249,0xa0,0xa4,0xaa,0xad,0,11,10),
            (24250,0x63,0x67,0x6d,0x70,4,12,11)):
        h=reader.function_headers[fid]
        code=bytearray(original[h.offset:h.offset+cut])
        # Reuse the already-loaded speedData, retaining the null register.
        read=bytearray(original[h.offset+prop:h.offset+guard]);read[2]=data_reg
        code+=read+bytes([ops.JNotEqual.opcode,7,value_reg,null_reg])
        code+=original[h.offset+fallback:h.offset+h.bytecodeSizeInBytes]
        branch=0x9c if fid==24249 else 0x5f
        assert code[branch]==ops.JEqual.opcode
        code[branch+1]=10
        replace_function(fid,bytes(code),'display measured zero Mbps without changing speed card layout')

    # Preserve missing curve buckets as gaps. The local native dataset contains
    # finite x/y points split into contiguous segments, rather than plotting
    # missing values as a zero or bridging a collection outage.
    header=reader.function_headers[33165]
    curve=[i for i in parse_hbc_bytecode(header,reader) if 0x248<=i.original_pos<0x24f]
    assert [i.inst.name for i in curve]==['NewObject','PutNewOwnById']
    data[header.offset+0x248:header.offset+0x24f]=bytes([
        ops.GetByIdShort.opcode,14,29,1,110,ops.LoadConstUndefined.opcode,1])
    changed_functions.add(33165)
    changes.append({'function':33165,'action':'render measured curve segments with missing buckets omitted'})

    # Keep original fixed two-decimal GB daily totals with string 724 (GB ).


    # StaItem used childAppReport to enable its bandwidth bar, even when Hub
    # has no verified bandOccupy metric. `${undefined}%` crashes Yoga's native
    # width parser. Render the optional row only when the metric is numeric;
    # retain the original row and actions for measured values (including zero).
    header = reader.function_headers[32927]
    before = [i for i in parse_hbc_bytecode(header, reader)
              if 0x2ce <= i.original_pos < 0x2e4]
    assert [i.inst.name for i in before] == [
        'LoadConstUndefined', 'JEqual', 'GetById', 'StrictEq', 'JmpFalse', 'Mov']
    metric_guard = bytes([ops.GetById.opcode, 17, 14, 37]) + struct.pack('<H',44324)
    metric_guard += bytes([ops.TypeOf.opcode, 35, 17, ops.LoadConstString.opcode, 28])
    metric_guard += struct.pack('<H', reader.strings.index('number'))
    metric_guard += bytes([ops.StrictEq.opcode, 17, 35, 28,
                          ops.LoadConstUndefined.opcode, 35, ops.Mov.opcode, 35, 35])
    assert len(metric_guard) == 0x2e4 - 0x2ce
    data[header.offset+0x2ce:header.offset+0x2e4] = metric_guard
    changed_functions.add(32927)
    changes.append({'function':32927,'action':'guard optional measured bandwidth width'})

    return_null = bytes([ops.LoadConstNull.opcode, 0, ops.Ret.opcode, 0])
    return_undefined = bytes([ops.LoadConstUndefined.opcode, 0, ops.Ret.opcode, 0])
    # The retained React error boundary previously swallowed the exception
    # after vendor crash reporting was removed. Keep its fallback/retry state,
    # and send only the JS exception message and stack to local Android logs.
    diagnostic=bytearray([ops.LoadFromEnvironment.opcode,6,1,0])
    for target,sid in ((4,106),(8,23011)):
        diagnostic+=bytes([ops.GetById.opcode,target,6,1])+struct.pack('<H',sid)
    diagnostic+=bytes([ops.Add.opcode,4,4,8,ops.GetGlobalObject.opcode,7,
        ops.TryGetById.opcode,9,7,1])+struct.pack('<H',47298)
    diagnostic+=bytes([ops.LoadConstUInt8.opcode,10,3,ops.Call3.opcode,4,9,7,4,10,
        ops.LoadFromEnvironment.opcode,8,1,2,ops.Call3.opcode,4,9,7,8,10,
        ops.LoadConstUndefined.opcode,4])
    h=reader.function_headers[13856];start,end=0xb0,0x115
    rest=end-start-len(diagnostic)
    if rest%3==1:diagnostic+=bytes([ops.LoadConstUndefined.opcode,14]*2);rest-=4
    elif rest%3==2:diagnostic+=bytes([ops.LoadConstUndefined.opcode,14]);rest-=2
    diagnostic+=bytes([ops.Mov.opcode,14,14])*(rest//3)
    assert len(diagnostic)==end-start
    data[h.offset+start:h.offset+end]=diagnostic
    changed_functions.add(13856);changes.append({'function':13856,'action':'record handled UI exception locally; retain retry fallback'})
    # Service (23365) is the complete game screen, not an advertisement.
    # Keep its original layout and acceleration/application entry points.
    # The original achievement card must also be present without the vendor's
    # assurance subscription. Keep its renderer and genuine missing-value
    # defaults; do not fabricate a license or enable an unimplemented query.
    instruction(23365,0x2c2,'GetById',[5,23,34,44913],
                dict(enumerate([ops.LoadConstTrue.opcode,5]*3)))
    instruction(23365,0xca2,'JmpFalseLong',[638,6],
                dict(enumerate([ops.LoadConstTrue.opcode,6]*3)))
    instruction(23404,0xb8,'GetById',[6,7,9,44913],
                dict(enumerate([ops.LoadConstTrue.opcode,6]*3)))
    # Reopen a completed result through the corresponding scoped query. The
    # original last-result callback otherwise enters a result view while the
    # query data is still unset, then resumes the wrong optimization timer.
    instruction(35523,0x356,'GetById',[7,7,23,33003],{4:34889&255,5:34889>>8})
    instruction(35523,0x3d7,'GetById',[6,6,23,33003],{4:28687&255,5:28687>>8})

    # The report's ScrollView (36168) owns the chart and app card; the feedback
    # text is its next sibling. Its logContent style (36181) caps that scroll
    # viewport at window height minus 320 dp. The captured 1001x1778 screen
    # has a 1138 px viewport and 152 px below the feedback. Density is 2x, so
    # reclaim 60 dp of the unused space while retaining 16 dp at the bottom.
    # This changes the scroll viewport only: app rows and card content remain
    # intact and the last item is still reachable by scrolling.
    instruction(36181, 0x22, "LoadConstInt", [4, 320],
                {2 + index: value for index, value in enumerate(struct.pack('<i', 260))})

    speed=bytearray([ops.GetEnvironment.opcode,0,1,ops.LoadFromEnvironment.opcode,1,0,12])
    for target,source,sid in [(2,1,22921),(2,2,22266),(1,2,29352)]:
        speed+=bytes([ops.GetById.opcode,target,source,1])+struct.pack('<H',sid)
    speed+=bytes([ops.LoadFromEnvironment.opcode,0,0,5,ops.GetByIdShort.opcode,0,0,1,111,
                 ops.GetById.opcode,0,0,1])+struct.pack('<H',39847)
    speed+=bytes([ops.Call2.opcode,0,1,2,0,ops.LoadConstUndefined.opcode,0,ops.Ret.opcode,0])
    replace_function(23925,bytes(speed),'external speed test with clean virtual-device browser fallback')

    def owned_page_generator(fid,rn_slot,project_slot,ap_slot,method_sid,project_is_id=False):
        c=bytearray([ops.StartGenerator.opcode,ops.ResumeGenerator.opcode,0,1,
                     ops.GetEnvironment.opcode,0,2,ops.LoadFromEnvironment.opcode,1,0,rn_slot])
        for target,source,sid in [(2,1,22921),(2,2,22266),(3,2,method_sid)]:
            if int(reader.string_kinds[sid])==1:
                c+=bytes([ops.GetById.opcode,target,source,1])+struct.pack('<H',sid)
            else:
                assert target!=source
                c+=bytes([ops.LoadConstString.opcode,target])+struct.pack('<H',sid)
                c+=bytes([ops.GetByVal.opcode,target,source,target])
        c+=bytes([ops.GetEnvironment.opcode,0,1,ops.LoadFromEnvironment.opcode,4,0,project_slot])
        if not project_is_id:c+=bytes([ops.GetByIdShort.opcode,4,4,1,210])
        c+=bytes([ops.LoadFromEnvironment.opcode,1,0,ap_slot,
                  ops.GetByIdShort.opcode,5,1,1,198,ops.Call3.opcode,0,3,2,4,5,
                  ops.LoadConstUndefined.opcode,0,ops.CompleteGenerator.opcode,ops.Ret.opcode,0])
        h=reader.function_headers[fid];assert h.hasExceptionHandler==0 and len(c)<=h.bytecodeSizeInBytes
        assert next(iter(parse_hbc_bytecode(h,reader))).inst.name=='StartGenerator'
        data[h.offset:h.offset+h.bytecodeSizeInBytes]=c+bytes([ops.Unreachable.opcode]*(h.bytecodeSizeInBytes-len(c)))
        changed_functions.add(fid);changes.append({'function':fid,'action':'open project-owned local page without vendor-token H5'})
    owned_page_generator(23932,12,3,2,7603)
    owned_page_generator(23930,12,3,2,25647)
    # ChannelPanel is shared by game noise reduction and tool channel
    # optimization. Its project slot already contains the ID, not an object.
    owned_page_generator(32443,5,25,19,25647,project_is_id=True)
    owned_page_generator(23378,6,1,5,24932)
    owned_page_generator(23382,6,1,5,23170)
    # Retain RemotePage and its countdown/copy/OS selector. MACC provides the
    # actual expiration; its usable duration is not the old fixed ten minutes.
    for fid,pos,args in [(32723,0x16f,[1,12,16,28243]),(32726,0x24,[0,6,2,28243])]:
        instruction(fid,pos,'GetById',args,{4:31829&255,5:31829>>8})
    timeleft=bytearray([ops.GetGlobalObject.opcode,0,ops.TryGetById.opcode,1,0,1,14,0,
                       ops.GetByIdShort.opcode,2,1,2,reader.strings.index('now'),
                       ops.Call1.opcode,2,2,1,ops.LoadParam.opcode,1,1,
                       ops.Sub.opcode,2,1,2,ops.LoadConstInt.opcode,1])+struct.pack('<i',1000)
    timeleft+=bytes([ops.Div.opcode,2,2,1,ops.TryGetById.opcode,1,0,3,22,0,
                    ops.GetByIdShort.opcode,0,1,4,reader.strings.index('max'),
                    ops.LoadConstZero.opcode,3,ops.Call3.opcode,0,0,1,3,2,ops.Ret.opcode,0])
    replace_function(32731,bytes(timeleft),'count down actual MACC expiration')
    # Refresh immediately on mount, then use the existing one-second timer and
    # cleanup callback; this also avoids first showing a ten-minute countdown.
    effect=bytearray([ops.CreateEnvironment.opcode,0,ops.GetEnvironment.opcode,1,0,
                     ops.LoadFromEnvironment.opcode,2,1,2,
                     ops.GetGlobalObject.opcode,3,ops.TryGetById.opcode,5,3,1])+struct.pack('<H',40949)
    effect+=bytes([ops.LoadConstUndefined.opcode,4,ops.CreateClosure.opcode,6,0])+struct.pack('<H',32725)
    effect+=bytes([ops.Call1.opcode,7,6,4,ops.LoadConstInt.opcode,3])+struct.pack('<i',1000)
    effect+=bytes([ops.Call3.opcode,3,5,4,6,3,ops.PutById.opcode,2,3,1])+struct.pack('<H',109)
    effect+=bytes([ops.CreateClosure.opcode,0,0])+struct.pack('<H',32727)+bytes([ops.Ret.opcode,0])
    replace_function(32724,bytes(effect),'initialize original RDP countdown immediately')
    for fid in (32724,32731):
        small=reader.get_small_func_header_reader()
        raw=small.from_buffer(data,reader.functions_offset+fid*ctypes.sizeof(small))
        metadata=reader.get_large_func_header_reader().from_buffer(data,(raw.infoOffset<<16)|raw.offset) if raw.overflowed else raw
        metadata.frameSize=max(metadata.frameSize,17)
    # ChildBanner (24155) is the retained parental-control card. Preserve its
    # original component, styles and animations; only 23246 is promotional.
    for fid in (23246,):
        replace_function(fid, return_null, "remove promotional banner")
    # Setting is the entire My-page menu, not a promotional banner. Keep its
    # original row renderer and styling; remove only questionnaire/feedback.
    instruction(23571,0x84,'NewArray',[2,4],{2:2})
    for pos in (0xb5,0xde):
        instruction(23571,pos,'PutOwnByIndex',[2,6,1 if pos==0xb5 else 2],
                    dict(enumerate([ops.LoadConstUndefined.opcode,6,ops.LoadConstUndefined.opcode,6])))
    instruction(23571,0x1d2,'PutOwnByIndex',[2,4,3],{3:1})

    # -------------------------------------------------------------------------
    # XiaoXing Assistant V2.0 & Local Notification Bridge Upgrades
    # -------------------------------------------------------------------------
    # 1. FID 35703: launch native speed test instead of cloud link
    from patch_terminal_ipv6 import Code
    speed_jump=Code(reader)
    speed_jump.emit('GetEnvironment',0,1)
    speed_jump.emit('LoadFromEnvironment',1,0,6)
    speed_jump.get(2,1,'NativeModules')
    speed_jump.get(2,2,'sta')
    speed_jump.emit('LoadConstString',1,6987)
    speed_jump.emit('GetByVal',1,2,1)
    speed_jump.emit('GetEnvironment',3,0)
    speed_jump.emit('LoadFromEnvironment',0,3,23)
    speed_jump.emit('Call2',0,1,2,0)
    speed_jump.emit('LoadConstUndefined',0)
    speed_jump.emit('Ret',0)
    replace_function(35703,bytes(speed_jump.raw),'assistant speed button uses exact local action URI')
    # The replacement reads a captured project ID, so it needs four registers.
    small_headers=reader.get_small_func_header_reader()
    speed_header=small_headers.from_buffer(data,reader.functions_offset+35703*ctypes.sizeof(small_headers))
    if speed_header.overflowed:
        speed_header=reader.get_large_func_header_reader().from_buffer(data,
            (speed_header.infoOffset<<16)|speed_header.offset)
    speed_header.frameSize=16

    # The assistant's feedback shortcut opens the retired cloud survey. Keep
    # the remaining pills in their original order.
    shortcuts=Code(reader)
    shortcuts.emit('NewArray',0,4)
    for index,literal in enumerate((143206,143220,143227,143234)):
        shortcuts.emit('NewObjectWithBufferLong',1,3,3,53638,literal)
        shortcuts.emit('PutOwnByIndex',0,1,index)
    shortcuts.emit('Ret',0)
    replace_function(35770,bytes(shortcuts.raw),'drop the retired feedback shortcut')

    # 3. FID 35723: reduce init timer to 60ms for instant message sync
    instruction(35723, 0x1b, 'LoadConstInt', [0, 1000],
                {2 + idx: b for idx, b in enumerate(struct.pack('<i', 60))})

    # 4. FID 35748: bypass isSameWifi check to allow router diagnosis anytime
    instruction(35748, 0x140, 'LoadFromEnvironment', [13, 3, 26],
                dict(enumerate([ops.LoadConstTrue.opcode, 13, ops.LoadConstTrue.opcode, 13])))

    # The original supported-game card now spans the row; feedback and game
    # tips are erased. Three retained shortcuts belong to the hero footer.
    instruction(23365,0x141b,'NewArray',[20,3],{2:2})
    instruction(23365,0x1454,'LoadConstUInt8',[25,3],{2:1})
    instruction(23365,0x147d,'NewArray',[34,3],{2:2})
    instruction(23365,0x149f,'PutOwnByIndex',[34,25,2],
        dict(enumerate([ops.LoadConstUndefined.opcode,25]*2)))
    instruction(23365,0x159f,'NewArray',[25,2],{2:1})
    # The old feedback renderer is straight-line code and has no branch target.
    h=reader.function_headers[23365];start,end=0x15a7,0x16a7
    padding=bytes([ops.LoadConstUndefined.opcode,22])*((end-start)//2)
    assert len(padding)==end-start
    data[h.offset+start:h.offset+end]=padding;changed_functions.add(23365)
    from patch_game_home import additions as game_home_additions,insert_row,unknown_counts,STRINGS as game_home_strings
    game_home_additions(reader,replace_function)
    insert_row(reader,data,changed_functions,changes)
    unknown_counts(reader,data,replace_function)
    # GameNetwork's switch used the category-specific `game` flag. The HUB
    # projects overview.rcgame_enabled as `enabled`, so the switch follows the
    # global firmware service even when a dedicated device or study keeps it on.
    global_state=reader.strings.index('enabled')
    true_literal=reader.strings.index('true')
    instruction(25772,0x40a,'GetById',[1,6,36,23169],
                {4:global_state & 255,5:global_state >> 8})
    instruction(25772,0x418,'LoadConstString',[0,21354],
                {2:true_literal & 255,3:true_literal >> 8})
    # ServiceWrapper mounts this old feedback component even while invisible.
    # Remove its body as well as its entry so erased captions never reach i18n.
    assert reader.strings[reader.function_headers[23454].functionName]=='GameFeedBack'
    replace_function(23454,return_null,'erase residual mounted game feedback component')

    def game_navigation(route_sid, with_params=False):
        code=bytearray([ops.GetEnvironment.opcode,0,0,
            ops.LoadFromEnvironment.opcode,1,0,6,
            ops.GetByIdShort.opcode,2,1,1,190,
            ops.LoadConstString.opcode,3])+struct.pack('<H',route_sid)
        if with_params:
            code+=bytes([ops.NewObject.opcode,4,ops.LoadConstFalse.opcode,5,
                         ops.PutNewOwnById.opcode,4,5])+struct.pack('<H',49100)
            code+=bytes([ops.Call3.opcode,0,2,1,3,4])
        else:
            code+=bytes([ops.Call2.opcode,0,2,1,3])
        code+=bytes([ops.LoadConstUndefined.opcode,0,ops.Ret.opcode,0])
        return bytes(code)
    replace_function(23043,game_navigation(12595,True),'game card opens router application centre')
    nav_header=reader.get_small_func_header_reader().from_buffer(data,reader.functions_offset+23043*ctypes.sizeof(reader.get_small_func_header_reader()))
    assert not nav_header.overflowed
    nav_header.frameSize=max(nav_header.frameSize,16)
    del nav_header
    replace_function(23048,game_navigation(12370),'game card opens STUN penetration')
    replace_function(23478,game_navigation(12595,True),'UU shortcut shares the router application screen')
    # Qiyou and Gamng are retired. Keep the UU branch and its firmware data;
    # disable both retired queries and their inclusion in the rendered list.
    for pos,args in [(0x1e8,[27,0,32]),(0x3b0,[32,33,32])]:
        instruction(26213,pos,'StrictEq',args,
            dict(enumerate([ops.LoadConstFalse.opcode,args[0],ops.LoadConstFalse.opcode,args[0]])))
    # r27 remains the explicit false from 0x1e8 until both query options.
    for pos,args in [(0x2eb,[1,0,118]),(0x33d,[1,0,118])]:
        instruction(26213,pos,'PutNewOwnByIdShort',args,{2:27})
    instruction(26213,0x81c,'LoadConstString',[26,20903],{2:20430&255,3:20430>>8})
    replace_function(24006, bytes([ops.NewArray.opcode, 0, 0, 0, ops.Ret.opcode, 0]),
                     "remove tool questionnaire menu")
    # Retain both original signal card filters: coverage and floor-plan
    # simulation are requested pages, even while backend adaptation continues.
    # Preserve the async wrapper's generator contract; a normal function does
    # not have .next() and crashes Babel's async-to-generator helper.
    header = reader.function_headers[23042]
    assert next(iter(parse_hbc_bytecode(header, reader))).inst.name == "StartGenerator"
    generator_noop = bytes([ops.StartGenerator.opcode, ops.ResumeGenerator.opcode, 0, 1,
                            ops.CompleteGenerator.opcode, ops.LoadConstUndefined.opcode, 0,
                            ops.Ret.opcode, 0])
    data[header.offset:header.offset + header.bytecodeSizeInBytes] = generator_noop + bytes(
        [ops.Unreachable.opcode] * (header.bytecodeSizeInBytes - len(generator_noop)))
    changed_functions.add(23042)
    changes.append({"function": 23042, "action": "remove questionnaire generator body"})
    # This read also supplies the retained version-history screen. Keep the
    # original getter; HubInterceptor sends it to the selected HUB's own notes.
    replace_function(23580, return_undefined, "no official app upgrade action")
    replace_function(23018, bytes([ops.NewArray.opcode, 0, 0, 0, ops.Ret.opcode, 0]),
                     "no retired tutorial request")
    replace_function(24124, return_undefined, "remove official child privacy link")
    # Remove inspected, feature-exclusive implementation bodies as well as
    # their hidden controls. Do not erase the shared API or DeviceInfo modules:
    # those factories also export retained device and project operations.
    exclusive=set();pending=[20053,23454,23472]
    while pending:
        fid=pending.pop()
        if fid in exclusive:continue
        exclusive.add(fid)
        for inst in parse_hbc_bytecode(reader.function_headers[fid],reader):
            if inst.inst.name in {'CreateClosure','CreateGeneratorClosure','CreateGenerator','CreateAsyncClosure'}:
                pending.append(inst.arg3)
    exclusive.difference_update((23472,23478))
    exclusive.update((20353,20357,20888,21004,24803,24807,24811,24815,
                      24819,24823,24827,24831,24835,24733,33039,36014))
    offsets={reader.function_headers[f].offset for f in exclusive}
    shared={h.offset for fid,h in enumerate(reader.function_headers)
            if fid not in exclusive and h.offset in offsets}
    for fid in sorted(exclusive):
        h=reader.function_headers[fid]
        if h.offset in shared:continue
        first=next(iter(parse_hbc_bytecode(h,reader))).inst.name
        code=generator_noop if first=='StartGenerator' else return_null
        assert len(code)<=h.bytecodeSizeInBytes
        data[h.offset:h.offset+h.bytecodeSizeInBytes]=code+bytes(
            [ops.Unreachable.opcode]*(h.bytecodeSizeInBytes-len(code)))
        small=reader.get_small_func_header_reader()
        raw=small.from_buffer(data,reader.functions_offset+fid*ctypes.sizeof(small))
        if raw.overflowed:
            location=(raw.infoOffset<<16)|raw.offset
            reader.get_large_func_header_reader().from_buffer(data,location).hasExceptionHandler=0
        else:raw.hasExceptionHandler=0
        changed_functions.add(fid)
        changes.append({'function':fid,'action':'erase exclusive retired login/push/tutorial/feedback body'})
    # The hook still returns its original object for home/tools callers. Its
    # fresh environment slot 7 is already undefined; use the redundant store
    # to set an explicit false for React Query's enabled option.
    instruction(23339, 0xaf, "StoreNPToEnvironment", [0, 7, 4],
                dict(enumerate([ops.LoadConstFalse.opcode, 2,
                                ops.LoadConstUndefined.opcode, 4])))
    # Keep the loading-state completion, without the official cloud's automatic
    # child-configuration cleanup when project data finishes loading.
    completion = reader.function_headers[21973]
    replace_function(21973, bytes([ops.LoadConstUndefined.opcode, 0]) +
                     original[completion.offset + 0xd3:completion.offset + completion.bytecodeSizeInBytes],
                     "finish project loading without cloud child cleanup")

    # 100% Pure official UI: Keep all official telemetry, statistics,
    # radar, blacklisting, and child-management strings intact without
    # adding extra custom disclaimer or hint words.
    labels = {
        19052: "儿童设备的规则和必要使用记录保存在您连接的 HUB。请由监护人启用，并在停止守护时删除相关记录。",
        19440: "《儿童个人信息处理规则》"
    }
    labels.update({sid: '' for sid, s in enumerate(reader.strings) if '小星' in s or
                   s.startswith(('wss://shinyaappws.ruijie.com.cn/ws/assistant','wss://sy-manage.ruijie.com.cn/ws/assistant'))})
    labels[login_tag] = 'HubLoginSurface'
    labels[account_tag] = 'HubAccountSurface'
    labels[stun_tag] = 'HubStunSurface'
    labels[diagnosis_tag] = 'HubDiagnosisSurface'
    labels[20095] = '网络诊断'
    labels.update(game_home_strings)
    # The firmware probes measure packet loss, not game-rendered frame drops.
    # Reuse an erased assistant-only caption slot for this one result label.
    assert reader.strings[19366]=='卡帧率'
    labels[19366]='丢包率'
    labels[reader.strings.index('本周最低卡帧率')]='本周最低丢包率'
    # Dedicated retired reporting URL storage; do not alter the shared
    # ten-minute labels used by child policies.
    # NAS already owns 17308. Keep the desktop duration in its own retired slot.
    rdp_duration_sid=10827
    assert reader.strings[rdp_duration_sid]=='noncestr'
    labels[rdp_duration_sid]='3小时'
    instruction(32709,1510,'LoadConstString',[25,19023],
                {2:rdp_duration_sid&255,3:rdp_duration_sid>>8})
    from patch_identity import additions, save_generator, STRINGS as identity_strings
    def edit_generator(fid,code,reason):
        h=reader.function_headers[fid]
        assert len(code)==h.bytecodeSizeInBytes and code[0]==ops.StartGenerator.opcode
        data[h.offset:h.offset+h.bytecodeSizeInBytes]=code
        changed_functions.add(fid);changes.append({'function':fid,'action':reason})
    additions(reader,original,replace_function,edit_generator)
    labels.update(identity_strings)
    from patch_router_tools import additions as router_additions, STRINGS as router_strings
    router_additions(reader,original,replace_function,data)
    labels.update(router_strings)
    from patch_legacy_upgrade_progress import apply as patch_legacy_upgrade_progress
    patch_legacy_upgrade_progress(reader,original,edit_generator)
    # F24074 was scheduled five times during the first minute and advanced the
    # old page by 3% on each timer even before any router status was read.
    replace_function(24074,bytes([ops.LoadConstUndefined.opcode,0,ops.Ret.opcode,0]),
                     'remove original timed synthetic upgrade progress')
    # Helper remains registered; retain its route for the assistant banner.
    # The router-tools patch replaces function 23933 from the original body.
    # Restore the firmware Diagnose route after that replacement; doing it
    # earlier silently left the card navigating to retired Helper.
    instruction(23933,0x1a8,'LoadConstString',[2,473],
                {2:9780&255,3:9780>>8})
    from patch_station_lookup import additions as station_lookup_additions
    station_lookup_additions(reader,original,replace_function,data)
    from patch_offline_duration import additions as offline_additions
    offline_additions(reader,original,replace_function,edit_generator)
    assert len({login_tag,account_tag,stun_tag}) == 3
    assert not {login_tag,account_tag,stun_tag}.intersection(identity_strings)
    # Keep the original modal and refresh/close callbacks. Saving is awaited;
    # failures retain the current selection instead of reporting false success.
    for fid,code in [(33460,save_generator(reader)),
                     (22028,bytes([ops.StartGenerator.opcode,ops.ResumeGenerator.opcode,0,1,
                         ops.NewObject.opcode,0,ops.CompleteGenerator.opcode,ops.Ret.opcode,0])),
                     (22036,bytes([ops.StartGenerator.opcode,ops.ResumeGenerator.opcode,0,1,
                         ops.NewObject.opcode,0,ops.CompleteGenerator.opcode,ops.Ret.opcode,0]))]:
        h=reader.function_headers[fid]
        assert h.hasExceptionHandler==0 and len(code)<=h.bytecodeSizeInBytes
        data[h.offset:h.offset+h.bytecodeSizeInBytes]=code+bytes([ops.Unreachable.opcode]*(h.bytecodeSizeInBytes-len(code)))
        changed_functions.add(fid);changes.append({'function':fid,'action':'use HUB project-owned station identity choices'})
    # A/B bisect: SHINYA_DISABLE_PATCHES=assistant_bridge,measured_status,... skips whole
    # patch modules so a regression that arrived with one of them can be isolated by
    # building the same APK with that patch turned off. Names are comma separated.
    disabled={name.strip() for name in os.environ.get("SHINYA_DISABLE_PATCHES","").split(",") if name.strip()}
    def enabled(name): return name not in disabled
    if enabled("terminal_ipv6"):
        from patch_terminal_ipv6 import additions as terminal_ipv6_additions, STRINGS as terminal_ipv6_strings
        assert not set(terminal_ipv6_strings).intersection(labels), ('IPv6 private string conflict',set(terminal_ipv6_strings).intersection(labels))
        terminal_ipv6_additions(reader, original, replace_function, data)
        labels.update(terminal_ipv6_strings)
    if enabled("duration_refresh"):
        from patch_duration_refresh import additions as duration_refresh_additions
        data=duration_refresh_additions(reader,data,changed_functions,changes)
    if enabled("assistant_bridge"):
        from patch_assistant_bridge import additions as assistant_bridge_additions
        data=assistant_bridge_additions(reader,original,replace_function,data)
    # The trailing hash covers every body edit above, so it must run regardless of which
    # patches were skipped; only measured_status's own rewrite is gated here.
    data[-20:]=hashlib.sha1(data[:-20]).digest()
    if enabled("measured_status"):
        from patch_measured_status import additions as measured_additions
        data=measured_additions(load(bytes(data)),data,changed_functions,changes)
    if enabled("assistant_cards"):
        from patch_assistant_cards import STRINGS as assistant_card_strings
        assert not set(assistant_card_strings).intersection(labels), set(assistant_card_strings).intersection(labels)
        labels.update(assistant_card_strings)
    if enabled("assistant_diagnosis_detail") and enabled("assistant_cards"):
        from patch_assistant_diagnosis_detail import additions as assistant_diagnosis_detail_additions
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_diagnosis_detail_additions(load(bytes(data)),data,changed_functions,changes)
    if enabled("assistant_diagnose_intake") and enabled("assistant_cards"):
        from patch_assistant_diagnose_intake import additions as assistant_diagnose_intake_additions
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_diagnose_intake_additions(load(bytes(data)),data,changed_functions,changes)
    if enabled("assistant_features"):
        from patch_assistant_features import additions as assistant_features_additions
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_features_additions(load(bytes(data)),data,changed_functions,changes)
    if enabled("station_notify"):
        from patch_station_notify import additions as station_notify_additions, STRINGS as station_notify_strings
        assert not set(station_notify_strings).intersection(labels), set(station_notify_strings).intersection(labels)
        labels.update(station_notify_strings)
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=station_notify_additions(load(bytes(data)),data,changed_functions,changes)
    if enabled("assistant_history"):
        from patch_assistant_history import additions as assistant_history_additions, STRINGS as assistant_history_strings
        assert not set(assistant_history_strings).intersection(labels), set(assistant_history_strings).intersection(labels)
        labels.update(assistant_history_strings)
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_history_additions(load(bytes(data)),data,changed_functions,changes)
    if enabled("assistant_messages"):
        from patch_assistant_messages import additions as assistant_messages_additions
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_messages_additions(load(bytes(data)),data,changed_functions,changes)
    if enabled("assistant_notifications"):
        from patch_assistant_notifications import additions as assistant_notifications_additions, STRINGS as assistant_notifications_strings
        assert all(labels.get(sid, '') in ('', value) for sid, value in assistant_notifications_strings.items()), {sid: labels[sid] for sid, value in assistant_notifications_strings.items() if labels.get(sid, '') not in ('', value)}
        labels.update(assistant_notifications_strings)
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_notifications_additions(load(bytes(data)),data,changed_functions,changes)
    if enabled("assistant_diagnosis_heading"):
        from patch_assistant_diagnosis_heading import additions as assistant_diagnosis_heading_additions
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_diagnosis_heading_additions(load(bytes(data)),data,changed_functions,changes,
            placeholder=18524 if enabled("assistant_cards") else 1313)
    # The empty rotated tag cut into the banner's right edge. Keep the original
    # speech arrow and card layout, and render this retired badge as null.
    instruction(24141,0x2de,'Call3',[9,10,3,9,7],
                dict(enumerate([ops.LoadConstNull.opcode,9]*3)))
    if enabled("assistant_banner"):
        from patch_assistant_banner import additions as assistant_banner_additions, STRINGS as assistant_banner_strings
        assert not set(assistant_banner_strings).intersection(labels), set(assistant_banner_strings).intersection(labels)
        labels.update(assistant_banner_strings)
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_banner_additions(load(bytes(data)),data,changed_functions,changes)
    # The entry now reads the original store on click; never inject a hook
    # into Speed's render body (see verify_speed_page_safe).
    if enabled("speed_entry"):
        from patch_speed_entry import additions as speed_entry_additions
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=speed_entry_additions(load(bytes(data)),data,changed_functions,changes)
    from patch_child_report_cache import additions as child_report_cache_additions
    data[-20:]=hashlib.sha1(data[:-20]).digest()
    data=child_report_cache_additions(load(bytes(data)),data,changed_functions,changes)
    from patch_project_lifecycle import additions as project_lifecycle_additions
    data[-20:]=hashlib.sha1(data[:-20]).digest()
    data=project_lifecycle_additions(load(bytes(data)),data,changed_functions,changes)
    from patch_macc_entry import additions as macc_entry_additions, STRINGS as macc_entry_strings
    assert not set(macc_entry_strings).intersection(labels), ('MACC string conflict', set(macc_entry_strings).intersection(labels))
    labels.update(macc_entry_strings)
    data[-20:]=hashlib.sha1(data[:-20]).digest()
    data=macc_entry_additions(load(bytes(data)),data,changed_functions,changes)
    # NOTE: patch_project_entry（主页添加→HUB/MACC 向导）经真机回归确认会劫持
    # 「新增网络」入口，2026-11-13 撤下接线；重新接入前必须与新增网络入口做区分。
    from patch_game_last_result import additions as game_last_result_additions
    data[-20:]=hashlib.sha1(data[:-20]).digest()
    data=game_last_result_additions(load(bytes(data)),data,changed_functions,changes)
    from patch_offline_wake import additions as offline_wake_additions
    data[-20:]=hashlib.sha1(data[:-20]).digest()
    data=offline_wake_additions(load(bytes(data)),data,changed_functions,changes)
    labels[19918]='关于星耀家 HUB'
    # Include every replacement before computing occupied/free string ranges.
    # Late labels would still count as live storage and could overwrite them.
    labels.update({
        20949: '由小星助手V2.0提供服务',
        2911: '{"reboot_count":null}',
        13067: '{"flapCount":null}',
        18903: '{"upLinkSpeed":null}',
        49425: 'assistantSend',
        6987: 'assistantSpeedtest',
        22216: 'openProjectPages',
    })
    from patch_assistant_bridge import BANNER_CACHE_SID
    assert BANNER_CACHE_SID != account_tag, 'assistant cache must not overwrite account component name'
    assert labels[BANNER_CACHE_SID] == '{"reboot_count":null}'
    labels.update({sid:'STUN穿透' for sid,s in enumerate(reader.strings)
                   if s in {'远程游戏','远程玩游戏','远程打游戏','星耀家Link'}})
    labels.update({sid:'内网服务穿透与公网地址管理' for sid,s in enumerate(reader.strings)
                   if s in {'手机/平板远程玩家里电脑','在任何地方，远程玩您家里电脑中的Steam游戏'}})
    labels.update({sid:'信号覆盖情况' for sid,s in enumerate(reader.strings)
                   if s=='我最远，隔两堵墙都不掉线~'})
    from patch_device_catalog import additions as device_catalog_additions, STRINGS as device_catalog_strings
    assert not set(device_catalog_strings).intersection(labels), set(device_catalog_strings).intersection(labels)
    labels.update(device_catalog_strings)
    data[-20:] = hashlib.sha1(data[:-20]).digest()
    data = device_catalog_additions(load(bytes(data)), data, changed_functions, changes)
    # The original Helper header also references string 20357; the identity
    # patch reuses that slot for the fnOS manufacturer. Retarget the actual
    # header instruction, not just the tool-page banner, to the dedicated copy.
    data[-20:] = hashlib.sha1(data[:-20]).digest()
    helper_reader = load(bytes(data))
    helper_header = helper_reader.function_headers[35695]
    titles = [i for i in parse_hbc_bytecode(helper_header, helper_reader)
              if i.inst.name == 'LoadConstString' and i.arg2 == 20357]
    assert len(titles) == 1, 'Helper header title instruction changed'
    struct.pack_into('<H', data, helper_header.offset + titles[0].original_pos + 2, 344)
    changed_functions.add(35695)
    changes.append({'function':35695,'action':'use dedicated assistant header title, independent of fnOS manufacturer'})
    # Assistant cards insert new function bodies; run them after every other
    # body-inserting module so later insertions cannot relocate their headers.
    # Their strings were reserved early so allocation order stays unchanged.
    if enabled("assistant_cards"):
        from patch_assistant_cards import additions as assistant_card_additions
        data[-20:]=hashlib.sha1(data[:-20]).digest()
        data=assistant_card_additions(load(bytes(data)),data,changed_functions,changes)
    retired = {i for i, value in enumerate(reader.strings) if value.startswith(
        ("https://62ec64a915db5157d7b078ac555f0119@", "https://support.qq.com/product/318572/"))}
    changed_strings = set(labels) | retired
    used = bytearray(reader.header.stringStorageSize)
    ranges = {}
    for sid, entry in enumerate(reader.small_string_table):
        info = reader.overflow_string_table[entry.offset] if entry.length == 255 else entry
        length = info.length * (2 if entry.isUTF16 else 1)
        ranges[sid] = (info.offset, length)
        if sid not in changed_strings:
            used[info.offset:info.offset + length] = b"\x01" * length
    # Restrict allocation to storage originally owned by deliberately changed
    # strings. Never overwrite padding or guessed offsets in another section.
    free = bytearray(reader.header.stringStorageSize)
    for sid in changed_strings:
        start, length = ranges[sid]
        for j in range(start, start + length):
            if not used[j]:
                free[j] = 1

    # Hermes permits multiple string table entries to share a storage span.
    # Reuse only known immutable strings or newly allocated spans, with the
    # same encoding and UTF-16 code-unit boundary. This also avoids exhausting
    # the few contiguous retired ranges with duplicate translated labels.
    shared_strings = {}
    allocated_strings = []
    immutable_strings = []
    for sid, value in enumerate(reader.strings):
        if sid not in changed_strings:
            shared_strings.setdefault((value, bool(reader.small_string_table[sid].isUTF16)), ranges[sid][0])
            start, size = ranges[sid]
            immutable_strings.append((bool(reader.small_string_table[sid].isUTF16),
                bytes(data[reader.storage_offset+start:reader.storage_offset+start+size]), start))

    def store_string(sid, value):
        utf16 = any(ord(c) > 127 for c in value)
        encoded = value.encode("utf-16-le" if utf16 else "ascii")
        length = len(encoded) // (2 if utf16 else 1)
        assert length < 255
        start = 0
        if encoded:
            start = shared_strings.get((value, utf16))
            if start is None:
                for pool_utf16, pool_bytes, pool_start in allocated_strings + immutable_strings:
                    if pool_utf16 != utf16:
                        continue
                    inner = pool_bytes.find(encoded)
                    while inner >= 0 and utf16 and inner % 2:
                        inner = pool_bytes.find(encoded, inner + 1)
                    if inner >= 0:
                        start = pool_start + inner
                        break
            if start is None:
                # Best fit preserves larger contiguous holes for later values.
                holes = []; begin = None
                for j, available in enumerate(free):
                    if available and begin is None: begin = j
                    if begin is not None and (not available or j == len(free)-1):
                        end = j if not available else j+1
                        if end-begin >= len(encoded): holes.append((end-begin, begin))
                        begin = None
                if not holes:
                    raise ValueError(f"No exclusive string storage available for {sid} ({len(encoded)} bytes; {sum(free)} free)")
                _, start = min(holes)
                free[start:start + len(encoded)] = b"\x00" * len(encoded)
                data[reader.storage_offset + start:reader.storage_offset + start + len(encoded)] = encoded
                allocated_strings.append((utf16, encoded, start))
            shared_strings.setdefault((value, utf16), start)
        struct.pack_into("<I", data, reader.table_offset + 4 * sid, (length << 24) | (start << 1) | int(utf16))

    for sid in retired:
        store_string(sid, "")
    # Place the longest values first so small labels cannot fragment the few
    # deliberately retired ranges large enough for the original long titles.
    for sid, label in sorted(labels.items(), key=lambda item: len(item[1].encode('utf-16-le' if any(ord(c)>127 for c in item[1]) else 'ascii')), reverse=True):
        store_string(sid, label)
    # A Hermes read-cache slot is tied to one property identifier. Reusing a
    # slot for two properties can return the first property's value when the
    # object's shape is unchanged (for example name-change instead of logout).
    # Give every identifier a stable slot within the original header capacity.
    data[-20:] = hashlib.sha1(data[:-20]).digest()
    interim = load(bytes(data))
    from hermes_identifier_hashes import update as update_identifier_hashes
    identifier_hash_updates = update_identifier_hashes(data, interim)
    read_ops = {'GetByIdShort','GetById','GetByIdLong','TryGetById','TryGetByIdLong'}
    for fid in changed_functions:
        header=interim.function_headers[fid]
        slots={}
        for inst in parse_hbc_bytecode(header,interim):
            if inst.inst.name not in read_ops:continue
            sid=inst.arg4
            if sid not in slots:slots[sid]=len(slots)+1
            assert slots[sid] <= 255, (fid,sid,'read cache capacity')
            data[header.offset+inst.original_pos+3]=slots[sid]
        if len(slots)>header.highestReadCacheIndex:
            small=interim.get_small_func_header_reader()
            raw=small.from_buffer_copy(data,interim.functions_offset+fid*ctypes.sizeof(small))
            if raw.overflowed:
                location=(raw.infoOffset<<16)|raw.offset
                offset=interim.get_large_func_header_reader().highestReadCacheIndex.offset
            else:
                location=interim.functions_offset+fid*ctypes.sizeof(small)
                offset=small.highestReadCacheIndex.offset
            data[location+offset]=len(slots)
            changes.append({'function':fid,'action':'expand read cache for added properties','slots':len(slots)})
    data[-20:] = hashlib.sha1(data[:-20]).digest()
    result = load(bytes(data))
    # Every grown body above must sit inside the region Hermes maps as bytecode.
    # A body behind debugInfoOffset loads as garbage and kills the JS thread.
    from hbc_grow import assert_bodies_mapped
    assert_bodies_mapped(result)
    from hermes_identifier_hashes import verify as verify_identifier_hashes
    verify_identifier_hashes(result)
    from hermes_identifier_hashes import verify_property_operands
    verify_property_operands(result)
    from hermes_call_frames import verify as verify_implicit_call_frames
    verify_implicit_call_frames(reader,result)
    # Helper preserved for XiaoXing Assistant V2.0
    assert reader.object_keys==result.object_keys
    assert result.object_values[:62888]==reader.object_values[:62888]
    assert struct.unpack_from('<i',result.object_values,62888)[0]==160
    assert result.object_values[62892:98470]==reader.object_values[62892:98470]
    assert result.object_values[98474:]==reader.object_values[98474:]
    assert struct.unpack_from('<i',result.object_values,98470)[0]==1
    for sid, value in enumerate(reader.strings):
        assert result.strings[sid] == labels.get(sid, "" if sid in retired else value), sid
    for fid, header in enumerate(reader.function_headers):
        if fid not in changed_functions:
            assert data[header.offset:header.offset + header.bytecodeSizeInBytes] == original[header.offset:header.offset + header.bytecodeSizeInBytes]
        else:
            slots={}
            for inst in parse_hbc_bytecode(result.function_headers[fid], result):
                if inst.inst.name in read_ops:
                    assert slots.setdefault(inst.arg3,inst.arg4)==inst.arg4,(fid,'property cache collision')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    output.with_suffix(".patch.json").write_text(json.dumps({
        "sourceSha256": SOURCE_SHA, "outputSha256": hashlib.sha256(data).hexdigest(),
        "identifierHashUpdates": identifier_hash_updates,
        "changes": changes, "changedStrings": sorted(changed_strings)
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Bundle verified:", len(changed_functions), "functions;", len(changed_strings), "strings changed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    patch(args.source, args.output)
