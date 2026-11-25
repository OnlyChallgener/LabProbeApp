"""Compile-only check for the shinya_hub Java patches: stage 1 of build_internal.py and
nothing after it. No dex, no smali, no APK is read or written.

Used to prove an edit type-checks before handing the tree to the user, who builds the APK.
"""
import shutil
import subprocess
import sys
import tempfile
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SDK = Path('D:/Tools/labprobe-ui/android-sdk')
JAVA = Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
CACHE = Path(os.environ.get('SHINYA_JAVA_CACHE', 'C:/Users/admin/.gradle/caches/modules-2/files-2.1'))
SOURCE = ROOT / 'tools/shinya_hub'

def jar(pattern):
    matches = sorted(CACHE.glob(pattern))
    if not matches:
        raise FileNotFoundError(pattern)
    return matches[-1]

def run(values):
    result = subprocess.run([str(v) for v in values], cwd=ROOT, stdout=sys.stdout,
                            stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit('javac failed (exit %d)' % result.returncode)

android = SDK / 'platforms/android-35/android.jar'
libraries = [android, jar('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'),
             jar('com.squareup.okio/okio-jvm/3.6.0/*/*.jar'),
             jar('org.jetbrains.kotlin/kotlin-stdlib/1.9.23/*/*.jar')]
# The same file list build_internal.py compiles, so a stale sibling cannot pass here.
files = ('HubInterceptor.java', 'CachedHubResponses.java', 'HubToolSession.java', 'HubSnapshotKey.java',
         'HubTxtRecord.java', 'HubDns.java', 'HubToolsView.java', 'HubToolsViewManager.java',
         'HubAuthView.java', 'HubAuthViewManager.java', 'HubDialog.java', 'HubMaccDialog.java', 'HubCoverageSettings.java',
         'HubCoverageViewManager.java', 'HubDiagnosisModule.java',
         'HubStationModule.java', 'HubPages.java','HubOfficialFloor.java','HubNotifySettings.java', 'HubProjectPages.java', 'HubPenetrationActivity.java',
         'HubDiagnosisView.java', 'HubDiagnosisViewManager.java', 'HubAssistantFeaturesPage.java',
         'HubAssistantNotifications.java','HubNotificationReceiver.java', 'HubAssistantNotificationService.java', 'HubSpeedLoadingViewManager.java',
         'HubAssistantMessageStore.java', 'HubAssistantNoticeMerge.java')

work = Path(tempfile.mkdtemp(prefix='shinya-javac-'))
try:
    stubs = work / 'compile-only'
    stubs.mkdir()
    run([JAVA / 'javac.exe', '-source', '8', '-target', '8', '-classpath', android, '-d', stubs,
         *sorted((SOURCE / 'rn_compile_stubs').rglob('*.java'))])
    classes = work / 'classes'
    classes.mkdir()
    run([JAVA / 'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
         '-classpath', os_path := str(__import__('os').pathsep.join(str(p) for p in [*libraries, stubs])),
         '-d', classes, *[SOURCE / name for name in files]])
    print('COMPILE OK: %d sources' % len(files))
finally:
    shutil.rmtree(work, ignore_errors=True)
