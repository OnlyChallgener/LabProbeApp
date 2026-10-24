"""Run actual HubToolSession restore/refresh paths with JVM preference/network fakes.

Uses the repository's existing JDK/Android/OkHttp toolchain. No device, APK,
version metadata or installed application is modified.
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/shinya_hub'
JAVA = Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
CACHE = Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1')
ANDROID = Path('D:/Tools/labprobe-ui/android-sdk/platforms/android-35/android.jar')


def jar(pattern):
    paths = sorted(CACHE.glob(pattern))
    if not paths:
        raise FileNotFoundError(pattern)
    return paths[-1]


def command(args):
    subprocess.run([str(value) for value in args], cwd=ROOT, check=True)


def main():
    # org.json's real JVM implementation must precede Android's throwing stubs.
    libraries = [jar('org.json/json/20240303/*/*.jar'),
                 jar('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'),
                 jar('com.squareup.okio/okio-jvm/3.6.0/*/*.jar'),
                 jar('org.jetbrains.kotlin/kotlin-stdlib/1.9.23/*/*.jar'), ANDROID]
    sources = [SOURCE / name for name in ('HubToolSession.java', 'HubSnapshotKey.java',
                                         'HubInterceptor.java', 'CachedHubResponses.java')]
    sources += sorted((SOURCE / 'tests/session_restore').rglob('*.java'))
    work = Path(tempfile.mkdtemp(prefix='shinya-session-restore-'))
    try:
        classpath = os.pathsep.join(str(value) for value in libraries)
        command([JAVA/'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                 '-classpath', classpath, '-d', work, *sources])
        command([JAVA/'java.exe', '-cp', os.pathsep.join([str(work), classpath]),
                 'com.labprobe.shinyahub.RestoreSessionCheck'])
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
