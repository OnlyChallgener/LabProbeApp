#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"
DISPLAY = ROOT / "app/src/main/kotlin/com/labprobe/app/DeviceDisplayName.kt"
TEST = ROOT / "app/src/test/kotlin/com/labprobe/app/DeviceDisplayNameTest.kt"
RADAR = ROOT / "app/src/main/res/drawable/ic_launcher_radar.xml"
NETWORK = ROOT / "app/src/main/res/drawable/ic_launcher_network.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one old pattern, found {count}")
    return text.replace(old, new, 1)


def replace_dropdown_surfaces(text: str) -> tuple[str, int]:
    marker = "DropdownMenu("
    cursor = 0
    changed = 0
    output: list[str] = []
    while True:
        index = text.find(marker, cursor)
        if index < 0:
            output.append(text[cursor:])
            break
        before = text[index - 1] if index > 0 else ""
        if before.isalnum() or before == "_":
            output.append(text[cursor:index + len(marker)])
            cursor = index + len(marker)
            continue
        output.append(text[cursor:index])
        start = index
        pos = index + len(marker)
        depth = 1
        quote = ""
        escaped = False
        while pos < len(text) and depth > 0:
            ch = text[pos]
            if quote:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == quote:
                    quote = ""
            else:
                if ch in ('"', "'"):
                    quote = ch
                elif ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
            pos += 1
        if depth != 0:
            raise RuntimeError("unbalanced DropdownMenu call")
        segment = text[start:pos]
        updated = segment.replace("containerColor = LAB_POPUP_SURFACE", "containerColor = LAB_MENU_SURFACE")
        if updated != segment:
            changed += 1
        output.append(updated)
        cursor = pos
    return "".join(output), changed


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '"v$NAME build$CODE · 启动图标中心比例修复" to listOf(',
        '"v$NAME build$CODE · SSH、控件配色与设备名称一致性修复" to listOf(',
        "build178 changelog title",
    )
    text = replace_once(
        text,
        '                "雷达装饰层缩至 0.69，中心图案四周保留更充足的渐变底色",\n'
        '                "节点与连线层缩至 0.72，保持原图形、颜色和完整路径不变",',
        '                "SSH 为旧路由器增加 group14-sha1 安全回退，不启用 group1 和旧 CBC 算法",\n'
        '                "下拉菜单与 NAT 选中按钮统一为蓝白配色，不再显示默认粉紫色",\n'
        '                "首页、设备页和详情卡统一优先显示用户备注名称",',
        "build178 changelog items",
    )

    old_kex = 'cfg["kex"]="curve25519-sha256@libssh.org,curve25519-sha256,ecdh-sha2-nistp256,diffie-hellman-group14-sha256"'
    new_kex = 'cfg["kex"]="curve25519-sha256@libssh.org,curve25519-sha256,ecdh-sha2-nistp256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1"'
    text = replace_once(text, old_kex, new_kex, "SSH group14-sha1 fallback")

    if "private val LAB_MENU_SURFACE" not in text:
        text = replace_once(
            text,
            'private const val DEFAULT_TOKEN = ""\n',
            'private const val DEFAULT_TOKEN = ""\nprivate val LAB_MENU_SURFACE = Color(0xFFF8FBFF)\n',
            "menu surface constant",
        )
    text, dropdown_count = replace_dropdown_surfaces(text)
    if dropdown_count <= 0 and "containerColor = LAB_MENU_SURFACE" not in text:
        raise RuntimeError("no DropdownMenu surface was updated")

    old_chips = '''    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == "RFC5780", onClick = { mode = "RFC5780"; prefs.natMode = mode }, label = { Text("RFC5780 / 8489", fontSize = 12.sp, fontWeight = FontWeight.Black) })
        FilterChip(selected = mode == "RFC3489", onClick = { mode = "RFC3489"; prefs.natMode = mode }, label = { Text("RFC3489 TEST", fontSize = 12.sp, fontWeight = FontWeight.Black) })'''
    new_chips = '''    val natChipColors = FilterChipDefaults.filterChipColors(
        containerColor = Color.White,
        labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .70f),
        selectedContainerColor = Color(0xFFE8F1FF),
        selectedLabelColor = Color(0xFF2563EB)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == "RFC5780", onClick = { mode = "RFC5780"; prefs.natMode = mode }, label = { Text("RFC5780 / 8489", fontSize = 12.sp, fontWeight = FontWeight.Black) }, colors = natChipColors)
        FilterChip(selected = mode == "RFC3489", onClick = { mode = "RFC3489"; prefs.natMode = mode }, label = { Text("RFC3489 TEST", fontSize = 12.sp, fontWeight = FontWeight.Black) }, colors = natChipColors)'''
    text = replace_once(text, old_chips, new_chips, "NAT chip colors")

    raw_title = 'Text(d.name.ifBlank { d.mac },'
    if raw_title in text:
        text = text.replace(raw_title, 'Text(deviceDisplayName(d),')
    text = replace_once(
        text,
        'title = d.remark.ifBlank { d.name.ifBlank { d.mac } },',
        'title = deviceDisplayName(d),',
        "device card display name",
    )

    MAIN.write_text(text, encoding="utf-8")


def verify() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    main = MAIN.read_text(encoding="utf-8")
    display = DISPLAY.read_text(encoding="utf-8")
    tests = TEST.read_text(encoding="utf-8")
    radar = RADAR.read_text(encoding="utf-8")
    network = NETWORK.read_text(encoding="utf-8")

    code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if code_match is None or int(code_match.group(1)) < 178:
        raise RuntimeError("build178 migration requires versionCode >= 178")

    required = (
        (main, "SSH、控件配色与设备名称一致性修复"),
        (main, "diffie-hellman-group14-sha256,diffie-hellman-group14-sha1"),
        (main, 'cfg["StrictHostKeyChecking"]="ask"'),
        (main, "private val LAB_MENU_SURFACE = Color(0xFFF8FBFF)"),
        (main, "containerColor = LAB_MENU_SURFACE"),
        (main, "selectedContainerColor = Color(0xFFE8F1FF)"),
        (main, "selectedLabelColor = Color(0xFF2563EB)"),
        (main, "colors = natChipColors"),
        (main, "deviceDisplayName(d)"),
        (display, "fun deviceDisplayName(device: DeviceItem)"),
        (display, "cleanApiText(remark)"),
        (tests, "userRemarkOverridesRouterName"),
        (tests, "macIsTheFinalFallback"),
        (radar, 'android:scaleX="0.69"'),
        (network, 'android:scaleX="0.72"'),
    )
    missing = [needle for source, needle in required if needle not in source]
    if missing:
        raise RuntimeError(f"build178 verification failed: {missing}")

    forbidden = (
        "diffie-hellman-group1-sha1",
        "3des-cbc",
        "aes128-cbc",
        "aes192-cbc",
        "aes256-cbc",
        'cfg["mac.s2c"]="hmac-sha1',
        'cfg["mac.c2s"]="hmac-sha1',
        'cfg["StrictHostKeyChecking"]="no"',
        'Text(d.name.ifBlank { d.mac },',
        'title = d.remark.ifBlank { d.name.ifBlank { d.mac } },',
    )
    present = [needle for needle in forbidden if needle in main]
    if present:
        raise RuntimeError(f"build178 forbidden compatibility or display paths remain: {present}")

    if main.count("deviceDisplayName(d)") < 3:
        raise RuntimeError("all home/list/detail device titles were not normalized")

    # Menu calls may use the blue-white menu surface, while dialogs and sheets
    # intentionally keep their existing popup surface.
    _, remaining = replace_dropdown_surfaces(main)
    if remaining != 0:
        raise RuntimeError("a DropdownMenu still uses the old popup surface")


def main() -> None:
    patch_main()
    verify()
    print("build178 SSH compatibility, blue-white controls, and device names applied")


if __name__ == "__main__":
    main()
