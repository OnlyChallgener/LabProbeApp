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
    "versionCode = 194",
    "versionCode = 195",
    "versionCode",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/ui/design/LabUiV2.kt",
    '''        modifier\n            .fillMaxWidth()\n            .shadow(4.dp, shape, clip = false, ambientColor = LabV2.ShadowAmbient, spotColor = LabV2.ShadowSpot)\n            .clip(shape)''',
    '''        modifier\n            .fillMaxWidth()\n            .clip(shape)''',
    "remove generic LabV2Card square shadow",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/ui/design/LabUiV2.kt",
    '''                Modifier\n                    .size(38.dp)\n                    .clip(RoundedCornerShape(14.dp))\n                    .shadow(5.dp, RoundedCornerShape(14.dp), clip = false, ambientColor = accent.copy(alpha = .14f), spotColor = accent.copy(alpha = .20f))\n                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .96f), accent.copy(alpha = .22f), accent.copy(alpha = .07f))))\n                    .border(1.dp, Color.White.copy(alpha = .90f), RoundedCornerShape(14.dp)),''',
    '''                Modifier\n                    .size(38.dp)\n                    .clip(RoundedCornerShape(14.dp))\n                    .background(accent.copy(alpha = .10f)),''',
    "flatten section header icon container",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/ui/design/LabUiV2.kt",
    '''    val startAlpha = if (muted) .09f else .16f\n    val endAlpha = if (muted) .04f else .07f\n    Box(\n        modifier\n            .size(size.dp)\n            .clip(RoundedCornerShape((size * .32f).dp))\n            .background(\n                Brush.linearGradient(\n                    listOf(\n                        accent.copy(alpha = startAlpha),\n                        accent.copy(alpha = endAlpha)\n                    )\n                )\n            )\n            .border(1.dp, Color.White.copy(alpha = .88f), RoundedCornerShape((size * .32f).dp)),''',
    '''    val backgroundAlpha = if (muted) .07f else .12f\n    Box(\n        modifier\n            .size(size.dp)\n            .clip(RoundedCornerShape((size * .32f).dp))\n            .background(accent.copy(alpha = backgroundAlpha)),''',
    "flatten shared tool icon container",
)

print("build195 shared visual cleanup applied")
