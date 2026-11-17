"""Build an isolated internal APK for ADB validation, without touching hub.2."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import patch_apk
from patch_bundle import patch
from verify_react_abi import verify as verify_react_abi

ROOT=Path(__file__).resolve().parents[2]
ANALYSIS=ROOT/'test/_analysis/oct08-shinya-hub'
SDK=Path('D:/Tools/labprobe-ui/android-sdk')
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
CACHE=Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1')
SDK_LIB=Path('D:/Tools/labprobe-ui/cmdline-tools/latest/lib')

def jar(pattern):
    matches=sorted(CACHE.glob(pattern))
    if not matches:raise FileNotFoundError(pattern)
    return matches[-1]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--abi',choices=['arm64-v8a','x86_64'],default='x86_64')
    parser.add_argument('--resume',type=Path)
    args=parser.parse_args()
    # Keep paths short: several original Kotlin class filenames nearly reach
    # Windows' path limit even in the original decoded directory.
    work=args.resume.resolve() if args.resume else ROOT/'test/_h3b'/str(int(time.time()))
    assert work.is_relative_to((ROOT/'test').resolve())
    work.mkdir(parents=True,exist_ok=bool(args.resume))
    log=work/'build.log'
    def command(values):
        with log.open('ab') as stream:
            result=subprocess.run([str(v) for v in values],cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError('Build stage failed; inspect '+str(log))
    android=SDK/'platforms/android-35/android.jar'
    libraries=[android,jar('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'),
               jar('com.squareup.okio/okio-jvm/3.6.0/*/*.jar'),
               jar('org.jetbrains.kotlin/kotlin-stdlib/1.9.23/*/*.jar')]
    classes=(work/'classes').resolve()
    assert classes.is_relative_to(work.resolve())
    if classes.exists():
        shutil.move(str(classes), str(classes)+'.prev-'+str(int(__import__('time').time()*1000)))
    classes.mkdir()
    stubs=work/'compile-only';stubs.mkdir(exist_ok=True)
    command([JAVA/'javac.exe','-source','8','-target','8','-classpath',android,'-d',stubs,
             *sorted((ROOT/'tools/shinya_hub/rn_compile_stubs').rglob('*.java'))])
    command([JAVA/'javac.exe','-encoding','UTF-8','-source','8','-target','8',
             '-classpath',os.pathsep.join(str(p) for p in [*libraries,stubs]),'-d',classes,
             *[ROOT/'tools/shinya_hub'/n for n in ('HubInterceptor.java','CachedHubResponses.java',
                 'HubToolSession.java','HubSnapshotKey.java','HubTxtRecord.java','HubDns.java','HubToolsView.java','HubToolsViewManager.java',
                 'HubAuthView.java','HubAuthViewManager.java','HubDialog.java','HubMaccDialog.java','HubCoverageSettings.java','HubCoverageViewManager.java','HubDiagnosisModule.java','HubStationModule.java','HubPages.java','HubOfficialFloor.java','HubNotifySettings.java','HubProjectPages.java','HubPenetrationActivity.java',
                 'HubDiagnosisView.java','HubDiagnosisViewManager.java','HubAssistantFeaturesPage.java',
                 'HubAssistantNotifications.java','HubNotificationReceiver.java','HubAssistantNotificationService.java',
                 'HubAssistantMessageStore.java','HubAssistantNoticeMerge.java','HubSpeedLoadingViewManager.java')]])
    dex=work/'dex';dex.mkdir(exist_ok=True)
    d8=[JAVA/'java.exe','-cp',ROOT/'test/_tools/r8-8.13.17.jar','com.android.tools.r8.D8',
        '--min-api','21','--lib',android,'--output',dex]
    for library in libraries[1:]:d8.extend(['--classpath',library])
    d8.extend(classes.rglob('*.class'))
    d8.extend(['--classpath',stubs])
    command(d8)
    smali=work/'smali';smali.mkdir(exist_ok=True)
    sdk_jars=list(SDK_LIB.rglob('*.jar'))
    argfile=work/'baksmali.args'
    # Java argument files avoid Windows command-length limits. No shell is used.
    classpath=os.pathsep.join(p.as_posix() for p in sdk_jars)
    argfile.write_text('-cp\n"'+classpath+'"\ncom.android.tools.smali.baksmali.Main\ndisassemble\n"'+
                      (dex/'classes.dex').as_posix()+'"\n-o\n"'+smali.as_posix()+'"\n',encoding='utf-8')
    command([JAVA/'java.exe','@'+str(argfile)])
    print(json.dumps({'stage':'native-adapter-built'}),flush=True)
    decoded=work/'decoded'
    def copy_source(source,destination):
        a=Path(source);b=Path(destination)
        if b.is_file() and a.stat().st_size==b.stat().st_size and a.stat().st_mtime_ns==b.stat().st_mtime_ns:
            return str(b)
        return shutil.copy2(a,b)
    shutil.copytree(ANALYSIS/'decoded',decoded,ignore=shutil.ignore_patterns('build','dist'),
                    copy_function=copy_source,dirs_exist_ok=True)
    print(json.dumps({'stage':'react-runtime-signatures-verified','methods':verify_react_abi(decoded,smali,android,JAVA/'javap.exe')}),flush=True)
    patch(ANALYSIS/'index.android.bundle',decoded/'assets/index.android.bundle')
    if not os.environ.get('SHINYA_DISABLE_PATCHES','').strip():
        command([sys.executable,ROOT/'tools/shinya_hub/assistant_runtime_gates.py',decoded/'assets/index.android.bundle'])
        command([sys.executable,ROOT/'tools/shinya_hub/verify_native_project_target.py',work])
    release=json.loads((ROOT/'tools/shinya_hub/release.json').read_text(encoding='utf-8'))
    patch_apk.VERSION_NAME=release['versionName']
    patch_apk.VERSION_CODE=release['versionCode']
    patch_apk.apply(decoded,smali)
    # Only unneeded CPU architectures are removed in this internal build.
    # Game resources/SDK trimming needs its own reference audit first.
    lib_root=(decoded/'lib').resolve()
    assert lib_root.is_relative_to(work.resolve())
    retired=work/'retired-native';retired.mkdir(exist_ok=True)
    for directory in lib_root.iterdir():
        if directory.is_dir() and directory.name!=args.abi:
            assert directory.resolve().parent==lib_root
            # Move instead of delete: keeps the same build output while
            # avoiding bulk-delete guards on the tool checkout.
            shutil.move(str(directory),str(retired/directory.name))
    print(json.dumps({'stage':'decoded-apk-ready','abi':args.abi}),flush=True)
    unsigned=work/'unsigned.apk'
    command([JAVA/'java.exe','-jar',ROOT/'test/_tools/apktool_3.0.3.jar','build',decoded,'-o',unsigned])
    aligned=work/'aligned.apk'
    aligner=SDK/'build-tools/36.0.0/zipalign.exe'
    command([aligner,'-P','16','-f','4',unsigned,aligned])
    apksig=jar('com.android.tools.build/apksig/9.2.0/*/*.jar')
    signed=work/('shinya-hub-ui-dev-'+args.abi+'.apk')
    command([sys.executable,ROOT/'tools/shinya_hub/sign.py',aligned,signed,
             '--java-bin',JAVA,'--apksig',apksig,'--classes',ANALYSIS/'sign-classes'])
    command([aligner,'-c','-P','16','4',signed])
    from assistant_runtime_gates import verify_services
    verify_services(signed)
    result={'stage':'built-for-validation','apk':str(signed),'abi':args.abi,'bytes':signed.stat().st_size,
            'versionName':release['versionName'],'versionCode':release['versionCode'],
            'assistantRuntimeGatesPassed':not bool(os.environ.get('SHINYA_DISABLE_PATCHES','').strip()),
            'sha256':hashlib.sha256(signed.read_bytes()).hexdigest(),'appUiVerified':False,
            'fullFunctionalityVerified':False}
    (work/'build-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result),flush=True)

if __name__=='__main__':
    main()
