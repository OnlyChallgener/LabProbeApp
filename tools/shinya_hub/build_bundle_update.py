"""Re-sign a bundle-only test iteration with verified, fixed-size manifest edits.

Only use after a full build has compiled the current native Java and resources.
This refuses any native/source changes since the supplied APK was built.
"""
import argparse
import hashlib
import json
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

from build_internal import ROOT, ANALYSIS, JAVA, SDK, jar
from patch_bundle import patch


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('work', type=Path)
    parser.add_argument('--source-name', required=True)
    parser.add_argument('--source-code', type=int, required=True)
    parser.add_argument('--abi', choices=['arm64-v8a','x86_64'], default='x86_64')
    args = parser.parse_args()
    work = args.work.resolve()
    assert work.is_relative_to((ROOT / 'test').resolve())
    source = work / 'unsigned.apk'
    # The full build's unsigned APK must be newer than every compiled source.
    compiled_sources = [*(ROOT / 'tools/shinya_hub').glob('*.java'),
                        *(ROOT / 'tools/shinya_hub/rn_compile_stubs').rglob('*.java')]
    assert all(p.stat().st_mtime < source.stat().st_mtime for p in compiled_sources)
    release = json.loads((ROOT / 'tools/shinya_hub/release.json').read_text(encoding='utf8'))
    old = args.source_name.encode('utf-16-le')
    new = release['versionName'].encode('utf-16-le')
    assert len(old) == len(new), 'String-pool resize requires a full build'
    bundle = work / 'bundle-update.hbc'
    patch(ANALYSIS / 'index.android.bundle', bundle)
    subprocess.run([sys.executable,str(ROOT / 'tools/shinya_hub/verify_identity_table.py'),str(bundle)],check=True)
    unsigned = work / 'unsigned-bundle-update.apk'
    brand_assets={'assets/'+folder+'/'+p.name:p.read_bytes()
                  for folder in ('hub-brandicons','hub-appicons')
                  for p in (ROOT / 'tools/shinya_hub/assets'/folder).glob('*.png')}
    appicon_manifest=ROOT/'tools/shinya_hub/assets/hub-appicons/manifest.json'
    brand_assets['assets/hub-appicons/manifest.json']=appicon_manifest.read_bytes()
    # H5 pages are packaged assets too. Bundle iterations must carry reviewed
    # report/search changes instead of silently retaining the old APK copies.
    ui_root=ROOT / 'tools/shinya_hub/assets/hub-ui'
    brand_assets.update({'assets/hub-ui/'+p.relative_to(ui_root).as_posix():p.read_bytes()
                        for p in ui_root.rglob('*') if p.is_file()})
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(unsigned, 'w') as dst:
        assert {n.split('/')[1] for n in src.namelist() if n.startswith('lib/') and n.endswith('.so')}=={args.abi},'Source APK ABI mismatch'
        manifest = src.read('AndroidManifest.xml')
        old_code = struct.pack('<i', args.source_code)
        assert manifest.count(old) == manifest.count(old_code) == 1
        manifest = manifest.replace(old, new).replace(old_code, struct.pack('<i', release['versionCode']))
        for entry in src.infolist():
            value = manifest if entry.filename == 'AndroidManifest.xml' else (
                bundle.read_bytes() if entry.filename == 'assets/index.android.bundle' else
                brand_assets.get(entry.filename,src.read(entry)))
            dst.writestr(entry, value)
        for name,value in brand_assets.items():
            if name not in src.namelist():dst.writestr(name,value,compress_type=zipfile.ZIP_DEFLATED)
    aligned=work/'aligned-bundle-update.apk'
    aligner=SDK/'build-tools/36.0.0/zipalign.exe'
    subprocess.run([str(aligner),'-P','16','-f','4',str(unsigned),str(aligned)],check=True)
    signed = work / ('shinya-hub-ui-dev-'+args.abi+'.apk')
    subprocess.run([sys.executable, str(ROOT / 'tools/shinya_hub/sign.py'), str(aligned), str(signed),
                    '--java-bin', str(JAVA), '--apksig', str(jar('com.android.tools.build/apksig/9.2.0/*/*.jar')),
                    '--classes', str(ANALYSIS / 'sign-classes')], check=True)
    subprocess.run([str(aligner),'-c','-P','16','4',str(signed)],check=True)
    result = {'stage': 'built-for-validation', 'apk': str(signed), 'abi': args.abi,
              'versionName': release['versionName'], 'versionCode': release['versionCode'],
              'bytes': signed.stat().st_size, 'sha256': hashlib.sha256(signed.read_bytes()).hexdigest(),
              'appUiVerified': False, 'fullFunctionalityVerified': False}
    (work / 'build-result.json').write_text(json.dumps(result, indent=2), encoding='utf8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
