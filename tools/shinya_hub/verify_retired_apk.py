"""Inspect the built APK for audited native SDKs and executable retired calls."""
import json
from pathlib import Path
import sys
import zipfile
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

apk = Path(sys.argv[1])
with zipfile.ZipFile(apk) as package:
    names = package.namelist()
    dex = b''.join(package.read(name) for name in names
                   if name.startswith('classes') and name.endswith('.dex'))
    for prefix in (b'Lio/sentry/', b'Lcom/theweflex/react/', b'Lcom/tencent/mm/',
                   b'Lcom/RNAppleAuthentication/', b'Lcn/com/ruijie/shinya2/wxapi/'):
        assert prefix not in dex, 'Retired native descriptor remains: ' + prefix.decode()
    assert not any('sentry' in name and name.endswith('.so') for name in names)
    for retained in (b'Lcom/google/mlkit/vision/barcode/', b'Lcom/reactnativecommunity/webview/',
                     b'Lcom/github/wuxudong/rncharts/', b'Lcom/labprobe/shinyahub/HubDiagnosisModule;',
                     b'Lcom/labprobe/shinyahub/HubStationModule;'):
        assert retained in dex, 'Retained native dependency missing: ' + retained.decode()
    r = load(package.read('assets/index.android.bundle'))
    original = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
    child = r.function_headers[24155]
    original_child = original.function_headers[24155]
    assert r.file_buffer.getvalue()[child.offset:child.offset+child.bytecodeSizeInBytes] == original.file_buffer.getvalue()[original_child.offset:original_child.offset+original_child.bytecodeSizeInBytes], 'Original ChildBanner component changed or removed'
    assert r.strings[8096] == 'HubLoginSurface'
    assert r.strings[8085] == 'HubStunSurface'
    assert r.strings[17308] == 'nas'
    assert r.strings[7156] == 'ugreen'
    assert r.strings[19440] == '《儿童个人信息处理规则》'
    # Diagnose reads project from the retained provider context. The public
    # useDiagnose result has no project field and must remain untouched.
    diagnosis = list(parse_hbc_bytecode(r.function_headers[27972], r))
    assert any(i.inst.name == 'GetById' and i.arg4 == 50918 for i in diagnosis), 'Diagnosis must use the current-project context'
    assert not any(i.inst.name == 'LoadFromEnvironment' and i.arg3 == 8 for i in diagnosis), 'useDiagnose omits project'
    hook = r.function_headers[27982]
    old_hook = original.function_headers[27982]
    assert r.file_buffer.getvalue()[hook.offset:hook.offset+hook.bytecodeSizeInBytes] == original.file_buffer.getvalue()[old_hook.offset:old_hook.offset+old_hook.bytecodeSizeInBytes], 'Original useDiagnose contract changed'
    for fid in (20353,20357,20888,21004,24803,24807,24811,24815,
                24819,24823,24827,24831,24835,24733,33039,36014):
        seq = list(parse_hbc_bytecode(r.function_headers[fid], r))
        assert seq[0].inst.name == 'StartGenerator', fid
        assert all(i.inst.name not in ('Call', 'Call1', 'Call2', 'Call3', 'Call4',
                                       'LoadConstString', 'LoadConstStringLong') for i in seq), fid
    assert next(iter(parse_hbc_bytecode(r.function_headers[20053], r))).inst.name == 'LoadConstNull'

report = {'apk': str(apk.resolve()), 'nativeSdkDescriptorsAbsent': True,
          'retiredNativeLibrariesAbsent': True, 'retainedDependenciesPresent': True,
          'retiredCallsErased': True, 'startupVerified': False,
          'allFeaturesVerified': False}
apk.with_suffix('.retirement.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
print(json.dumps(report, ensure_ascii=False))
