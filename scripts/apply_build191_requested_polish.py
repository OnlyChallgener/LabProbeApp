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
    "versionCode = 190",
    "versionCode = 191",
    "versionCode",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/MainActivity.kt",
    "fontFamily = if (shown.contains(':') || shown.count { it == '.' } >= 2) FontFamily.Monospace else FontFamily.Default",
    "fontFamily = FontFamily.Default",
    "home address value font",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/MainActivity.kt",
    "Spacer(Modifier.width(10.dp))\n        Column(Modifier.weight(1f)) {\n            Text(deviceDisplayName(d),",
    "Spacer(Modifier.width(10.dp))\n        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {\n            Text(deviceDisplayName(d),",
    "followed device three-line spacing",
)

print("build191 requested-only polish applied")
