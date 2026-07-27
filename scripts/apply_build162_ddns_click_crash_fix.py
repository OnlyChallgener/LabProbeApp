#!/usr/bin/env python3
"""Build162: keep DDNS editor out of the scroll tree and isolate row actions."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
CONTROL = SRC / "RouterControlUi.kt"
MAIN = SRC / "MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"
VERIFIER = ROOT / "scripts/verify_build154_sources.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build162 anchor: {label}")
    return text.replace(old, new, 1)


def patch_ddns_editor(text: str) -> str:
    old = '''    if(adding||editing!=null)DdnsEditorPage(editing?:DdnsRecord(),onBack={adding=false;editing=null}){record,password->scope.launch{
        val result=if(editing==null)repository.addDdns(record,password.orEmpty())else repository.updateDdns(record,password)
        result.onSuccess{adding=false;editing=null;actionError=""}.onFailure{actionError=it.message.orEmpty()}
    }}'''
    new = '''    if (adding || editing != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { adding = false; editing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = RouterPage) {
                DdnsEditorPage(
                    initial = editing ?: DdnsRecord(),
                    onBack = { adding = false; editing = null },
                ) { record, password ->
                    scope.launch {
                        val result = if (editing == null) {
                            repository.addDdns(record, password.orEmpty())
                        } else {
                            repository.updateDdns(record, password)
                        }
                        result.onSuccess {
                            adding = false
                            editing = null
                            actionError = ""
                        }.onFailure {
                            actionError = it.message.orEmpty()
                        }
                    }
                }
            }
        }
    }'''
    return replace_once(text, old, new, "full-screen DDNS editor dialog")


def patch_ddns_card(text: str) -> str:
    start_marker = "@Composable\nprivate fun DdnsCard(record:DdnsRecord,onEdit:()->Unit,onToggle:()->Unit,onDelete:()->Unit){"
    end_marker = "\n@Composable\nprivate fun DdnsEditorPage"
    start = text.find(start_marker)
    end = text.find(end_marker, start + len(start_marker))
    if start < 0 or end < 0:
        if "Modifier.weight(1f).clickable(onClick=onEdit)" in text and "PremiumCard(accent){" in text:
            return text
        raise RuntimeError("missing build162 anchor: DDNS card function")
    replacement = '''@Composable
private fun DdnsCard(record:DdnsRecord,onEdit:()->Unit,onToggle:()->Unit,onDelete:()->Unit){
    // Editing, switching and the overflow menu are separate hit targets.  The
    // overflow icon must never bubble into the card's edit action.
    val accent=RouterCyan
    val warning=record.status.contains("error",true)||record.status.contains("fail",true)
    var menu by remember(record.serviceId){mutableStateOf(false)}
    PremiumCard(accent){
        Row(verticalAlignment=Alignment.CenterVertically){
            Row(
                modifier=Modifier.weight(1f).clickable(onClick=onEdit),
                verticalAlignment=Alignment.CenterVertically,
            ){
                RouterGlyphIcon(RouterGlyph.Ddns,accent,Modifier.size(27.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(record.domain,fontSize=11.9.sp,fontWeight=FontWeight.Black,color=RouterInk,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text("${record.provider} · ${if(record.useIpv6)"IPv6" else "IPv4"} · ${record.interfaceName.uppercase()}",fontSize=9.5.sp,fontWeight=FontWeight.Bold,color=RouterMuted)
                    Text(record.ip.ifBlank{record.status.ifBlank{"等待更新"}},fontSize=9.8.sp,fontWeight=FontWeight.SemiBold,color=if(warning)RouterAmber else if(record.ip.isBlank())RouterMuted else RouterBlue,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            Switch(checked=record.enabled,onCheckedChange={onToggle()},modifier=Modifier.scale(.76f),colors=SwitchDefaults.colors(checkedTrackColor=accent))
            Box{
                IconButton(onClick={menu=true},modifier=Modifier.size(28.dp)){
                    Icon(Icons.Rounded.MoreVert,"更多操作",Modifier.size(16.dp),tint=RouterMuted)
                }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    DropdownMenuItem(text={Text("编辑",fontSize=11.5.sp)},onClick={menu=false;onEdit()})
                    DropdownMenuItem(text={Text("删除",fontSize=11.5.sp,color=RouterRed)},onClick={menu=false;onDelete()})
                }
            }
        }
    }
}
'''
    return text[:start] + replacement.rstrip() + text[end:]


def patch_version() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    gradle = gradle.replace("versionCode = 161", "versionCode = 162")
    gradle = gradle.replace('versionName = "0.10.19"', 'versionName = "0.10.20"')
    GRADLE.write_text(gradle, encoding="utf-8")

    main = MAIN.read_text(encoding="utf-8")
    main = main.replace(
        '"v$NAME build$CODE · 终端历史与映射持久化"',
        '"v$NAME build$CODE · DDNS 页面点击闪退修复"',
    )
    MAIN.write_text(main, encoding="utf-8")


def patch_verifier() -> None:
    text = VERIFIER.read_text(encoding="utf-8")
    text = text.replace("APP v0.10.18 build160", "APP v0.10.20 build162")
    text = text.replace("'versionCode = 160', 'versionName = \"0.10.18\"'", "'versionCode = 162', 'versionName = \"0.10.20\"'")
    text = text.replace("'versionCode = 161', 'versionName = \"0.10.19\"'", "'versionCode = 162', 'versionName = \"0.10.20\"'")
    text = text.replace("'终端历史与映射持久化',", "'DDNS 页面点击闪退修复',")
    marker = "    forbid(PORTMAP, 'while (true) {\\n            delay(10_000)')\n"
    checks = '''    require(
        ROUTER_CONTROL,
        'androidx.compose.ui.window.Dialog(',
        'DialogProperties(usePlatformDefaultWidth = false)',
        'Modifier.weight(1f).clickable(onClick=onEdit)',
        'Icon(Icons.Rounded.MoreVert,"更多操作"',
    )
    forbid(ROUTER_CONTROL, 'PremiumCard(accent,Modifier.clickable(onClick=onEdit))')
'''
    if checks not in text:
        if marker not in text:
            raise RuntimeError("missing build162 verifier insertion point")
        text = text.replace(marker, marker + checks, 1)
    text = text.replace(
        "print('build161 realtime, durable mapping and history presentation verified')",
        "print('build162 DDNS navigation and isolated action targets verified')",
    )
    VERIFIER.write_text(text, encoding="utf-8")


def verify() -> None:
    control = CONTROL.read_text(encoding="utf-8")
    combined = "\n".join(path.read_text(encoding="utf-8") for path in (CONTROL, MAIN, GRADLE, VERIFIER))
    required = (
        "androidx.compose.ui.window.Dialog(",
        "DialogProperties(usePlatformDefaultWidth = false)",
        "Modifier.weight(1f).clickable(onClick=onEdit)",
        'Icon(Icons.Rounded.MoreVert,"更多操作"',
        "versionCode = 162",
        'versionName = "0.10.20"',
        "DDNS 页面点击闪退修复",
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build162 verification failed: {missing}")
    forbidden = (
        "PremiumCard(accent,Modifier.clickable(onClick=onEdit))",
        "if(adding||editing!=null)DdnsEditorPage",
    )
    remaining = [value for value in forbidden if value in control]
    if remaining:
        raise RuntimeError(f"DDNS crash path remains: {remaining}")


def apply() -> None:
    text = CONTROL.read_text(encoding="utf-8")
    text = patch_ddns_editor(text)
    text = patch_ddns_card(text)
    CONTROL.write_text(text, encoding="utf-8")
    patch_version()
    patch_verifier()
    verify()
    print("build162 DDNS card, overflow menu and editor navigation crash fixed")


if __name__ == "__main__":
    apply()
