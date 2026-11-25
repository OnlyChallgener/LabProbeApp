"""Remove audited native login/reporting SDKs from an isolated decoded APK."""
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
PACKAGES = {'Lcom/theweflex/react/WeChatPackage;', 'Lio/sentry/react/RNSentryPackage;',
            'Lcom/RNAppleAuthentication/AppleAuthenticationAndroidPackage;'}
PREFIXES = ('io/sentry', 'com/theweflex/react', 'com/tencent/mm', 'com/RNAppleAuthentication',
            'cn/com/ruijie/shinya2/wxapi')


def trim(decoded):
    decoded = Path(decoded).resolve()
    # This utility only acts on our disposable build directories.
    build_root = Path('D:/Github/LabProbeApp/test/_h3b').resolve()
    assert decoded.is_relative_to(build_root) and decoded.name == 'decoded'
    manifest = decoded / 'AndroidManifest.xml'
    tree = ET.parse(manifest)
    root = tree.getroot()
    removed = []
    for parent in root.iter():
        for child in list(parent):
            name = child.get(ANDROID + 'name', '')
            if name.startswith('io.sentry.') or name.endswith('.wxapi.WXEntryActivity') or name in {
                'com.tencent.mm', 'com.google.android.c2dm.permission.RECEIVE',
                'com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE'}:
                parent.remove(child)
                removed.append(name)
    ET.register_namespace('android', ANDROID[1:-1])
    tree.write(manifest, encoding='utf-8', xml_declaration=True)

    package_file = decoded / 'smali/com/facebook/react/PackageList.smali'
    source = package_file.read_text(encoding='utf8')
    begin = source.index('.method public getPackages()')
    end = source.index('.end method', begin) + len('.end method')
    old = source[begin:end]
    packages = re.findall(r'new-instance v2, (L[^\s]+;)', old)
    assert len(packages) == 30 and PACKAGES.issubset(packages)
    code = ['.method public getPackages()Ljava/util/ArrayList;', '    .locals 3',
            '    new-instance v0, Ljava/util/ArrayList;',
            '    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V']
    for package in packages:
        if package in PACKAGES:
            removed.append(package)
            continue
        code.append('    new-instance v1, ' + package)
        if package == 'Lcom/facebook/react/shell/MainReactPackage;':
            code.extend(['    iget-object v2, p0, Lcom/facebook/react/PackageList;->mConfig:Lcom/facebook/react/shell/MainPackageConfig;',
                         '    invoke-direct {v1, v2}, ' + package + '-><init>(Lcom/facebook/react/shell/MainPackageConfig;)V'])
        else:
            code.append('    invoke-direct {v1}, ' + package + '-><init>()V')
        code.append('    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z')
    code.extend(['    return-object v0', '.end method'])
    package_file.write_text(source[:begin] + '\n'.join(code) + source[end:], encoding='utf8')

    classes = 0
    retired_root = decoded.parent / 'retired-native'
    for smali in decoded.glob('smali*'):
        if not smali.is_dir(): continue
        for prefix in PREFIXES:
            target = (smali / prefix).resolve()
            assert target.is_relative_to(decoded)
            if target.is_dir():
                classes += len(list(target.rglob('*.smali')))
                # Move aside instead of deleting: the APK build only reads the
                # smali* tree, and keeping the audited originals around makes
                # every build reproducible without bulk file deletion.
                archive = retired_root / (str(smali.name) + '-' + prefix.replace('/', '_') + '-' + str(int(__import__('time').time() * 1000)))
                archive.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(target), str(archive))
    libraries = []
    for library in (decoded / 'lib').rglob('*sentry*.so'):
        assert library.resolve().is_relative_to(decoded)
        libraries.append(library.name)
        library.unlink()
    return {'nativePackagesRemoved': len(PACKAGES), 'classesRemoved': classes,
            'librariesRemoved': libraries, 'manifestEntriesRemoved': removed}
