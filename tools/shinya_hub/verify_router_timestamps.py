"""Run the production APP timestamp normalizer on a local JVM; no APK is built."""
import os
import tempfile
from pathlib import Path
from verify_session_restore import JAVA, ANDROID, SOURCE, jar, command


def main():
    libraries = [jar('org.json/json/20240303/*/*.jar'),
                 jar('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'),
                 jar('com.squareup.okio/okio-jvm/3.6.0/*/*.jar'),
                 jar('org.jetbrains.kotlin/kotlin-stdlib/1.9.23/*/*.jar'), ANDROID]
    sources = [SOURCE / name for name in ('HubToolSession.java', 'HubSnapshotKey.java',
                                         'HubInterceptor.java', 'CachedHubResponses.java')]
    sources.append(SOURCE / 'tests/session_restore/HubOfflineTimesCheck.java')
    with tempfile.TemporaryDirectory(prefix='shinya-router-timestamps-') as work:
        stubs = Path(work) / 'compile-only'
        stubs.mkdir()
        (stubs / 'HubAssistantNotifications.java').write_text(
            'package com.labprobe.shinyahub; final class HubAssistantNotifications {'
            'static void stop(android.content.Context c){} static void sessionReady(){} }', encoding='utf-8')
        (stubs / 'HubCoverageSettings.java').write_text(
            'package com.labprobe.shinyahub; final class HubCoverageSettings {'
            'static void bindProject(HubToolSession s,String a,String b,String c){} '
            'static void apply(HubToolSession s,org.json.JSONObject o){} }', encoding='utf-8')
        sources += sorted(stubs.glob('*.java'))
        classpath = os.pathsep.join(str(value) for value in libraries)
        command([JAVA/'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                 '-classpath', classpath, '-d', work, *sources])
        command([JAVA/'java.exe', '-cp', os.pathsep.join([str(work), classpath]),
                 'com.labprobe.shinyahub.HubOfflineTimesCheck'])


if __name__ == '__main__':
    main()
