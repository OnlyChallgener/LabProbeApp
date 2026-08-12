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
    '''                val statusLabel = when (status) {\n                    "内网" -> "内网可达"\n                    "外网" -> "外网可达"\n                    else -> "不可达"\n                }''',
    '''                val statusLabel = when (status) {\n                    "内网", "外网" -> "可达"\n                    else -> "不可达"\n                }''',
    "favorite reachability concise label",
)

print("build194 favorite status label applied")
