"""Run the APK's own RN view-config export in an isolated Android process.

Does not install, stop or launch the app. Uses only a dedicated /data/local/tmp
directory. --compile-only prepares the harness without touching a device.
"""
import argparse
import json
import subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
SDK=Path('D:/Tools/labprobe-ui/android-sdk')
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
ADB=Path('D:/Program Files (x86)/pcsuite/adb_41/adb.exe')
REMOTE='/data/local/tmp/shinya-coverage-export-check'
HARNESS=r'''import java.lang.reflect.*;
import java.util.*;
public final class CoverageApkCheck {
 public static void main(String[] args) throws Exception {
  boolean expectedReject=args.length>0 && args[0].equals("reject");
  try {
   Class<?> vm=Class.forName("com.facebook.react.uimanager.ViewManager");
   Class<?> cls=Class.forName("com.labprobe.shinyahub.HubCoverageViewManager");
   Object manager=cls.getConstructor().newInstance();
   Class<?> helper=Class.forName("com.facebook.react.uimanager.UIManagerModuleConstantsHelper");
   Method defaults=helper.getDeclaredMethod("getDefaultExportableEventTypes");defaults.setAccessible(true);
   Map events=(Map)defaults.invoke(null);
   Method build=helper.getDeclaredMethod("createConstantsForViewManager",vm,Map.class,Map.class,Map.class,Map.class);build.setAccessible(true);
   Map config=(Map)build.invoke(null,manager,events.get("bubblingEventTypes"),events.get("directEventTypes"),new HashMap(),new HashMap());
   Map props=(Map)config.get("NativeProps"),direct=(Map)config.get("directEventTypes");
   if(props==null||!props.containsKey("projectId")||!props.containsKey("sn")||!props.containsKey("type"))throw new AssertionError("coverage props missing");
   Map change=direct==null?null:(Map)direct.get("topChange");
   if(change==null||!"onChange".equals(change.get("registrationName")))throw new AssertionError("coverage event missing");
   if(expectedReject)throw new AssertionError("old APK unexpectedly accepted RN merge");
   System.out.println("PASS APK actual RN config export; NativeProps projectId/sn/type; topChange/onChange; default direct events");
  } catch(InvocationTargetException ex) {
   Throwable failure=ex.getCause();
   while(failure instanceof InvocationTargetException)failure=((InvocationTargetException)failure).getCause();
   if(expectedReject && failure instanceof UnsupportedOperationException){System.out.println("PASS old APK rejected actual RN default-event merge: UnsupportedOperationException");return;}
   throw new RuntimeException("APK config export failed",failure);
  }
 }
}
'''

def run(args, timeout=45):
    p=subprocess.run([str(a) for a in args],cwd=ROOT,capture_output=True,text=True,timeout=timeout)
    if p.returncode:raise RuntimeError((p.stdout+p.stderr)[-3500:])
    return p.stdout.strip()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk',type=Path)
    parser.add_argument('--device',default='127.0.0.1:5555')
    parser.add_argument('--expect-reject',action='store_true')
    parser.add_argument('--compile-only',action='store_true')
    args=parser.parse_args()
    work=ROOT/'test/_h3b/coverage-runtime-check';work.mkdir(parents=True,exist_ok=True)
    source=work/'CoverageApkCheck.java';source.write_text(HARNESS,encoding='utf8')
    run([JAVA/'javac.exe','-encoding','UTF-8','-source','8','-target','8','-classpath',SDK/'platforms/android-35/android.jar','-d',work,source])
    run([JAVA/'java.exe','-cp',ROOT/'test/_tools/r8-8.13.17.jar','com.android.tools.r8.D8','--min-api','21','--lib',SDK/'platforms/android-35/android.jar','--output',work,work/'CoverageApkCheck.class'])
    if args.compile_only:print('PASS isolated Android harness compiles');return
    if not args.apk or not args.apk.is_file():parser.error('--apk must name an existing local APK')
    adb=[ADB,'-s',args.device]
    run(adb+['shell','mkdir','-p',REMOTE])
    run(adb+['push',args.apk,REMOTE+'/target.apk'],90)
    run(adb+['push',work/'classes.dex',REMOTE+'/check.dex'])
    command='CLASSPATH='+REMOTE+'/check.dex:'+REMOTE+'/target.apk /system/bin/app_process /system/bin CoverageApkCheck '+('reject' if args.expect_reject else 'accept')
    result=run(adb+['shell',command],30)
    (work/('reject-result.json' if args.expect_reject else 'accept-result.json')).write_text(json.dumps({'apk':str(args.apk.resolve()),'result':result},indent=2),encoding='utf8')
    print(result)

if __name__=='__main__':main()
