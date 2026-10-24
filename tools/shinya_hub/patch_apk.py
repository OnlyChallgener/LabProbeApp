"""Apply the native/package changes to an apktool-decoded Shinya APK."""
import argparse
import re
import shutil
from pathlib import Path
import xml.etree.ElementTree as ET
from trim_retired_native import trim

OLD_PACKAGE = "cn.com.ruijie.shinya2"
NEW_PACKAGE = "com.labprobe.shinyahub"
VERSION_NAME = "1.2.4-hub.2"
VERSION_CODE = 183249837
ANDROID = "{http://schemas.android.com/apk/res/android}"


def apply(decoded: Path, native_smali: Path):
    manifest = decoded / "AndroidManifest.xml"
    ET.register_namespace("android", ANDROID[1:-1])
    tree = ET.parse(manifest)
    root = tree.getroot()
    assert root.attrib["package"] in (OLD_PACKAGE, NEW_PACKAGE)
    root.set("package", NEW_PACKAGE)
    app = root.find("application")
    app.set(ANDROID + "label", "星耀家 Hub")
    if not any(node.get(ANDROID+'name')=='com.labprobe.shinyahub.HubPenetrationActivity' for node in app.findall('activity')):
        ET.SubElement(app,'activity',{ANDROID+'name':'com.labprobe.shinyahub.HubPenetrationActivity',
            ANDROID+'exported':'false',ANDROID+'theme':'@android:style/Theme.Material.Light.NoActionBar',
            ANDROID+'windowSoftInputMode':'adjustResize'})
    for node in root.iter():
        for name, value in list(node.attrib.items()):
            if name == ANDROID + "authorities" or node.tag in {"permission", "uses-permission"}:
                node.set(name, value.replace(OLD_PACKAGE, NEW_PACKAGE))
            elif name == ANDROID + "label" and value == "@string/app_name":
                node.set(name, "星耀家 Hub")
    # Keep native class names intact: JNI and React Native entrypoints use them.
    tree.write(manifest, encoding="utf-8", xml_declaration=True)

    config = decoded / "smali/cn/com/ruijie/shinya2/BuildConfig.smali"
    text = config.read_text(encoding="utf-8")
    text = text.replace('APPLICATION_ID:Ljava/lang/String; = "' + OLD_PACKAGE + '"',
                        'APPLICATION_ID:Ljava/lang/String; = "' + NEW_PACKAGE + '"')
    text = re.sub(r'VERSION_NAME:Ljava/lang/String; = "[^"]+"',
                  'VERSION_NAME:Ljava/lang/String; = "' + VERSION_NAME + '"', text)
    text = re.sub(r"VERSION_CODE:I = 0x[0-9a-fA-F]+", "VERSION_CODE:I = " + hex(VERSION_CODE), text)
    config.write_text(text, encoding="utf-8")

    application = decoded / "smali/cn/com/ruijie/shinya2/MainApplication.smali"
    text = application.read_text(encoding="utf-8")
    initialize = "    invoke-static {p0}, Lcom/labprobe/shinyahub/HubInterceptor;->init(Landroid/content/Context;)V"
    anchor = "    invoke-super {p0}, Landroid/app/Application;->onCreate()V"
    if initialize not in text:
        assert text.count(anchor) == 1
        text = text.replace(anchor, anchor + "\n\n" + initialize)
    application.write_text(text, encoding="utf-8")

    provider = decoded / "smali/com/facebook/react/modules/network/OkHttpClientProvider.smali"
    text = provider.read_text(encoding="utf-8")
    method_start = text.index(".method public static createClientBuilder()")
    method_end = text.index(".end method", method_start)
    part = text[method_start:method_end]
    extension = """    invoke-static {v0}, Lcom/labprobe/shinyahub/HubInterceptor;->configureClient(Lokhttp3/OkHttpClient$Builder;)V

"""
    if "configureClient(" not in part:
        if "HubInterceptor;" in part:
            part = re.sub(r"    new-instance v1, Lcom/labprobe/shinyahub/HubInterceptor;.*?followSslRedirects\(Z\)Lokhttp3/OkHttpClient\$Builder;\n\n", extension, part, flags=re.S)
        else:
            assert part.count("    return-object v0") == 1
            part = part.replace("    return-object v0", extension + "    return-object v0")
        text = text[:method_start] + part + text[method_end:]
    provider.write_text(text, encoding="utf-8")

    # An empty RadarData has no max-entry dataset. The bundled MPAndroidChart
    # dereferences that null dataset before it can draw the ordinary empty state.
    # Keep valid chart data unchanged; use the native no-data state while loading.
    charts = decoded / "smali_classes2/com/github/wuxudong/rncharts/charts/ChartBaseManager.smali"
    text = charts.read_text(encoding="utf-8")
    begin = text.index(".method public setData(")
    end = text.index(".end method", begin)
    part = text[begin:end]
    if ":hub_chart_empty" not in part:
        anchor = "    invoke-virtual {p1, p2}, Lcom/github/mikephil/charting/charts/Chart;->setData(Lcom/github/mikephil/charting/data/ChartData;)V"
        assert part.count(anchor) == 1
        guard = '''    if-eqz p2, :hub_chart_empty
    invoke-virtual {p2}, Lcom/github/mikephil/charting/data/ChartData;->getEntryCount()I
    move-result v0
    if-lez v0, :hub_chart_empty

''' + anchor + '''
    return-void

    :hub_chart_empty
    const-string v0, "暂无已采集数据"
    invoke-virtual {p1, v0}, Lcom/github/mikephil/charting/charts/Chart;->setNoDataText(Ljava/lang/String;)V
    invoke-virtual {p1}, Lcom/github/mikephil/charting/charts/Chart;->clear()V'''
        text = text[:begin] + part.replace(anchor, guard) + text[end:]
        charts.write_text(text, encoding="utf-8")

    analytics = decoded / "smali/cn/com/ruijie/shinya2/rnmodules/ShinyaDataAnalytics.smali"
    text = analytics.read_text(encoding="utf-8")
    for name in ("initialModule", "cleanIdentities", "set", "trackEvent"):
        pattern = re.compile(r"(\.method public " + name + r"\([^\n]*\)V)\n(.*?)\.end method", re.S)
        def without_tracking(match):
            annotations = "\n".join(re.findall(r"    \.annotation .*?    \.end annotation", match[2], re.S))
            return match[1] + "\n    .locals 0\n" + annotations + "\n    return-void\n.end method"
        text, count = pattern.subn(without_tracking, text)
        assert count == 1, name
    analytics.write_text(text, encoding="utf-8")
    package = decoded / "smali/cn/com/ruijie/shinya2/rnmodules/MyAppPackage.smali"
    text = package.read_text(encoding="utf-8")
    begin=text.index('.method public createNativeModules(')
    end=text.index('.end method',begin)
    part=text[begin:end]
    if 'HubStationModule;' not in part:
        part=part.replace('    return-object v0', '''    new-instance v1, Lcom/labprobe/shinyahub/HubStationModule;
    invoke-direct {v1, p1}, Lcom/labprobe/shinyahub/HubStationModule;-><init>(Lcom/facebook/react/bridge/ReactApplicationContext;)V
    invoke-interface {v0, v1}, Ljava/util/List;->add(Ljava/lang/Object;)Z

    return-object v0''')
    if 'HubDiagnosisModule;' not in part:
        part=part.replace('    return-object v0', '''    new-instance v1, Lcom/labprobe/shinyahub/HubDiagnosisModule;
    invoke-direct {v1, p1}, Lcom/labprobe/shinyahub/HubDiagnosisModule;-><init>(Lcom/facebook/react/bridge/ReactApplicationContext;)V
    invoke-interface {v0, v1}, Ljava/util/List;->add(Ljava/lang/Object;)Z

    return-object v0''')
    text=text[:begin]+part+text[end:]
    begin = text.index('.method public createViewManagers(')
    end = text.index('.end method', begin) + len('.end method')
    text = text[:begin] + '''.method public createViewManagers(Lcom/facebook/react/bridge/ReactApplicationContext;)Ljava/util/List;
    .locals 3
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    new-instance v1, Lcom/labprobe/shinyahub/HubToolsViewManager;
    const-string v2, "stun"
    invoke-direct {v1, v2}, Lcom/labprobe/shinyahub/HubToolsViewManager;-><init>(Ljava/lang/String;)V
    invoke-interface {v0, v1}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    new-instance v1, Lcom/labprobe/shinyahub/HubToolsViewManager;
    const-string v2, "beta"
    invoke-direct {v1, v2}, Lcom/labprobe/shinyahub/HubToolsViewManager;-><init>(Ljava/lang/String;)V
    invoke-interface {v0, v1}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    new-instance v1, Lcom/labprobe/shinyahub/HubAuthViewManager;
    const-string v2, "login"
    invoke-direct {v1, v2}, Lcom/labprobe/shinyahub/HubAuthViewManager;-><init>(Ljava/lang/String;)V
    invoke-interface {v0, v1}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    new-instance v1, Lcom/labprobe/shinyahub/HubAuthViewManager;
    const-string v2, "account"
    invoke-direct {v1, v2}, Lcom/labprobe/shinyahub/HubAuthViewManager;-><init>(Ljava/lang/String;)V
    invoke-interface {v0, v1}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    new-instance v1, Lcom/labprobe/shinyahub/HubDiagnosisViewManager;
    invoke-direct {v1}, Lcom/labprobe/shinyahub/HubDiagnosisViewManager;-><init>()V
    invoke-interface {v0, v1}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    return-object v0
.end method''' + text[end:]
    package.write_text(text, encoding="utf-8")
    shutil.copytree(native_smali, decoded / "smali_classes4", dirs_exist_ok=True)
    # Only supplementary icons absent from the official App are added. Keep
    # the official artwork/URI mappings and shared child-control icons intact.
    extra_icons = Path(__file__).parent / "assets/hub-appicons"
    if extra_icons.is_dir():
        destination = (decoded / "assets/hub-appicons").resolve()
        assert destination.is_relative_to(decoded.resolve())
        if destination.is_dir():
            expected = {p.name for p in extra_icons.glob('*.png')}
            for asset in destination.glob('*.png'):
                if asset.name not in expected:
                    assert asset.resolve().parent == destination
                    asset.unlink()
        shutil.copytree(extra_icons, decoded / "assets/hub-appicons", dirs_exist_ok=True)
    brand_icons = Path(__file__).parent / "assets/hub-brandicons"
    if brand_icons.is_dir():
        shutil.copytree(brand_icons, decoded / "assets/hub-brandicons", dirs_exist_ok=True)
    # Runtime artwork follows the user's latest official-tools style reference.
    # Design source images and notes stay outside the APK assets directory.
    ui_icons = Path(__file__).parent / "assets/hub-ui"
    if ui_icons.is_dir():
        shutil.copytree(ui_icons, decoded / "assets/hub-ui", dirs_exist_ok=True)

    # Only the retired feedback asset used by the STUN game-tab tile changes.
    # Retain its registered 97x96 dimensions and the original Image component.
    stun_bolts=Path(__file__).parent / "assets/stun-gold-bolts.png"
    if stun_bolts.exists():
        shutil.copy2(stun_bolts,decoded / "res/drawable-mdpi/src_assets_image_newgame_v124_feedback.png")

    # Copy custom rendered assets
    custom_dir = Path(__file__).parent / "assets/custom_icons"
    if custom_dir.is_dir():
        mdpi = decoded / "res/drawable-mdpi"
        nodpi = decoded / "res/drawable-nodpi"
        mdpi.mkdir(parents=True, exist_ok=True)
        nodpi.mkdir(parents=True, exist_ok=True)
        for src, dst in [
            (custom_dir / "manufacturer_ugreen_73x73.png", mdpi / "src_assets_image_manufacturer_ugreen.png"),
            (custom_dir / "manufacturer_fnos_73x73.png", mdpi / "src_assets_image_manufacturer_fnos.png"),
            (custom_dir / "device_nas_128x128.png", mdpi / "src_assets_image_common_icon_statype_nas.png"),
            (custom_dir / "device_nas_128x128.png", mdpi / "src_assets_image_common_icon_child_statype_nas.png"),
            (custom_dir / "device_nas_256x256.png", nodpi / "device_nas.png"),
        ]:
            if src.exists():
                shutil.copy2(src, dst)

    yaml_path = decoded / "apktool.yml"
    yaml = yaml_path.read_text(encoding="utf-8")
    yaml = re.sub(r"  versionCode: \d+", "  versionCode: " + str(VERSION_CODE), yaml)
    yaml = re.sub(r"  versionName: .*", "  versionName: " + VERSION_NAME, yaml)
    yaml_path.write_text(yaml, encoding="utf-8")
    print("Retired native SDK removal:", trim(decoded))
    print("Independent package and Hub routing installed:", NEW_PACKAGE)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded", type=Path)
    parser.add_argument("native_smali", type=Path)
    args = parser.parse_args()
    apply(args.decoded, args.native_smali)
