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
    "versionCode = 192",
    "versionCode = 193",
    "versionCode",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''                val status = accessReport?.let(::favoriteAccessStatus) ?: favoriteServiceStatus(shortcut, mode, mapping, devices)\n                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {''',
    '''                val status = accessReport?.let(::favoriteAccessStatus) ?: favoriteServiceStatus(shortcut, mode, mapping, devices)\n                val statusLabel = when (status) {\n                    "内网" -> "内网可达"\n                    "外网" -> "外网可达"\n                    else -> "不可达"\n                }\n                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {''',
    "favorite reachability label mapping",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''                    Text(\n                        status,\n                        fontSize = 10.5.sp,''',
    '''                    Text(\n                        statusLabel,\n                        fontSize = 10.5.sp,''',
    "favorite reachability label display",
)

print("build193 favorite reachability label applied")
