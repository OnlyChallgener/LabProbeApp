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
    "versionCode = 196",
    "versionCode = 197",
    "versionCode",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/MainActivity.kt",
    '''fun copy(ctx: Context, text: String) {\n    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)\n        .setPrimaryClip(ClipData.newPlainText("极客网探", text))\n}''',
    '''fun copy(ctx: Context, text: String) {\n    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)\n        .setPrimaryClip(ClipData.newPlainText("极客网探", text))\n    toast(ctx, "已复制")\n}''',
    "restore copy toast",
)

print("build197 copy Toast restored; rounded press-feedback fixes preserved")
