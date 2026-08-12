from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/build.gradle.kts",
    "versionCode = 193",
    "versionCode = 194",
    "versionCode",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {''',
    '''        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {''',
    "favorite card aligned min content height",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''                val statusLabel = when (status) {\n                    "内网" -> "内网可达"\n                    "外网" -> "外网可达"\n                    else -> "不可达"\n                }''',
    '''                val statusLabel = when (status) {\n                    "内网", "外网" -> "可达"\n                    else -> "不可达"\n                }''',
    "favorite reachability concise label",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''                shortcut.description.takeIf { it.isNotBlank() && it != shortcut.serviceType }?.let { description ->\n                    Text(description, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)\n                }\n''',
    '''''',
    "remove redundant favorite third description row",
)

print("build194 favorite card alignment applied")
