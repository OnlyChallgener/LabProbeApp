"""Refresh only Hermes code in an already compiled internal APK, then re-sign."""
import argparse,hashlib,json,subprocess,sys,zipfile
from pathlib import Path
from build_internal import ROOT,ANALYSIS,SDK,JAVA,jar
from patch_bundle import patch

p=argparse.ArgumentParser();p.add_argument('work',type=Path);p.add_argument('--abi',choices=['x86_64','arm64-v8a'],required=True)
args=p.parse_args();work=args.work.resolve();assert work.is_relative_to((ROOT/'test').resolve())
bundle=work/'current.bundle';patch(ANALYSIS/'index.android.bundle',bundle)
source=work/'unsigned.apk';target=work/'bundle-unsigned.apk'
assert source.exists()
asset_root=ROOT/'tools/shinya_hub/assets'
assets={'assets/'+folder+'/'+p.name:p.read_bytes()
        for folder in ('hub-brandicons','hub-appicons','hub-ui') for p in (asset_root/folder).iterdir() if p.is_file()}
with zipfile.ZipFile(source) as z,zipfile.ZipFile(target,'w') as out:
    for info in z.infolist():
        out.writestr(info,bundle.read_bytes() if info.filename=='assets/index.android.bundle'
                     else assets.get(info.filename,z.read(info.filename)))
aligned=work/'bundle-aligned.apk';aligner=SDK/'build-tools/36.0.0/zipalign.exe'
subprocess.run([str(aligner),'-P','16','-f','4',str(target),str(aligned)],check=True)
signed=work/('shinya-hub-ui-dev-'+args.abi+'.apk')
subprocess.run([sys.executable,str(ROOT/'tools/shinya_hub/sign.py'),str(aligned),str(signed),
               '--java-bin',str(JAVA),'--apksig',str(jar('com.android.tools.build/apksig/9.2.0/*/*.jar')),
               '--classes',str(ANALYSIS/'sign-classes')],check=True)
subprocess.run([str(aligner),'-c','-P','16','4',str(signed)],check=True)
result=json.loads((work/'build-result.json').read_text());result.update(bytes=signed.stat().st_size,sha256=hashlib.sha256(signed.read_bytes()).hexdigest())
(work/'build-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(result))
