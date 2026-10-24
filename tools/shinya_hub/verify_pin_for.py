"""Run the JVM harness that proves each HTTPS entry resolves to its own certificate pin.

The device is not always reachable from this shell, and the real SharedPreferences file needs
a rooted/debuggable target, so this exercises the actual shipped code instead: it compiles the
patch tree together with verify/PinForCheck.java and runs it against a fake SharedPreferences.

Only stage 1 of build_internal.py is reused; nothing is dexed and no APK is touched.
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SDK = Path('D:/Tools/labprobe-ui/android-sdk')
JAVA = Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
CACHE = Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1')
SOURCE = ROOT / 'tools/shinya_hub'

def jar(pattern):
    matches = sorted(CACHE.glob(pattern))
    if not matches:
        raise FileNotFoundError(pattern)
    return matches[-1]

def command(values):
    result = subprocess.run([str(v) for v in values], cwd=ROOT, stdout=sys.stdout,
                            stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit('failed (exit %d): %s' % (result.returncode, values[0]))

android = SDK / 'platforms/android-35/android.jar'
runtime = [android, jar('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'),
           jar('com.squareup.okio/okio-jvm/3.6.0/*/*.jar'),
           jar('org.jetbrains.kotlin/kotlin-stdlib/1.9.23/*/*.jar')]
sources = [SOURCE / name for name in (
    'HubInterceptor.java', 'CachedHubResponses.java', 'HubToolSession.java', 'HubSnapshotKey.java',
    'HubTxtRecord.java', 'HubDns.java', 'HubToolsView.java', 'HubToolsViewManager.java',
    'HubAuthView.java', 'HubAuthViewManager.java', 'HubDialog.java', 'HubDiagnosisModule.java',
    'HubStationModule.java', 'HubPages.java', 'HubPenetrationActivity.java', 'HubDiagnosisView.java',
    'HubDiagnosisViewManager.java')] + list((SOURCE / 'verify').rglob('*.java'))

work = Path(tempfile.mkdtemp(prefix='shinya-pinfor-'))
try:
    stubs = work / 'compile-only'
    stubs.mkdir()
    command([JAVA / 'javac.exe', '-source', '8', '-target', '8', '-classpath', android, '-d', stubs,
             *sorted((SOURCE / 'rn_compile_stubs').rglob('*.java'))])
    classes = work / 'classes'
    classes.mkdir()
    command([JAVA / 'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
             '-classpath', os.pathsep.join(str(p) for p in [*runtime, stubs]), '-d', classes, *sources])
    print('=== running PinForCheck on the JVM ===')
    command([JAVA / 'java.exe', '-cp', os.pathsep.join([str(classes)] + [str(p) for p in runtime] + [str(stubs)]),
             'com.labprobe.shinyahub.PinForCheck'])
finally:
    shutil.rmtree(work, ignore_errors=True)
