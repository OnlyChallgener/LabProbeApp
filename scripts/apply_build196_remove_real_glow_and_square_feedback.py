from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_count(path: str, old: str, new: str, expected: int, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{label}: expected {expected} matches, found {count}")
    p.write_text(text.replace(old, new), encoding="utf-8")


# Version only.
replace_once(
    "app/build.gradle.kts",
    "versionCode = 195",
    "versionCode = 196",
    "versionCode",
)

main = "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"

# Home small icons were NOT using LabV2ToolIcon. Flatten the three shared
# HealthMiniCard / HealthSectionTitle accent containers without changing size,
# icon, spacing, typography, card elevation, or click behavior.
replace_count(
    main,
    '''Modifier.size(36.dp).shadow(5.dp, HomeInnerShape, clip = false, ambientColor = accent.copy(alpha = .14f), spotColor = accent.copy(alpha = .20f)).clip(HomeInnerShape).background(Brush.linearGradient(listOf(Color.White.copy(alpha = .96f), accent.copy(alpha = .22f), accent.copy(alpha = .07f))))''',
    '''Modifier.size(36.dp).clip(HomeInnerShape).background(accent.copy(alpha = .10f))''',
    3,
    "flatten home mini/section icon glow",
)

# VPN/STUN header uses a separate cyan version of the same old glow.
replace_once(
    main,
    '''                    .size(36.dp)\n                    .clip(HomeInnerShape)\n                    .shadow(5.dp, HomeInnerShape, clip = false, ambientColor = LabV2.Cyan.copy(alpha = .14f), spotColor = LabV2.Cyan.copy(alpha = .20f))\n                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .96f), LabV2.Cyan.copy(alpha = .22f), LabV2.Cyan.copy(alpha = .07f))))''',
    '''                    .size(36.dp)\n                    .clip(HomeInnerShape)\n                    .background(LabV2.Cyan.copy(alpha = .10f))''',
    "flatten home vpn icon glow",
)

# The large square shown after every copy in the supplied video is the Android
# text Toast, not a card shadow. Keep clipboard behavior, remove only that
# visual overlay.
replace_once(
    main,
    '''fun copy(ctx: Context, text: String) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("极客网探", text)); toast(ctx, "已复制") }''',
    '''fun copy(ctx: Context, text: String) {\n    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)\n        .setPrimaryClip(ClipData.newPlainText("极客网探", text))\n}''',
    "remove copy toast square overlay",
)

# Device card: preserve the requested 4dp floating shadow, but clip the press
# indication to the actual rounded card before combinedClickable.
replace_once(
    main,
    '''            .shadow(\n                4.dp,\n                LabCoreSurface.CardShape,\n                clip = false,\n                ambientColor = LabV2.ShadowAmbient,\n                spotColor = LabV2.ShadowSpot\n            )\n            .combinedClickable(onClick = onOpenDetails, onLongClick = onOpenDetails)''',
    '''            .shadow(\n                4.dp,\n                LabCoreSurface.CardShape,\n                clip = false,\n                ambientColor = LabV2.ShadowAmbient,\n                spotColor = LabV2.ShadowSpot\n            )\n            .clip(LabCoreSurface.CardShape)\n            .combinedClickable(onClick = onOpenDetails, onLongClick = onOpenDetails)''',
    "clip device card press indication",
)

# Toolbox status tile: clickable must be inside the same 14dp shape used by the
# Surface. Copyable value area gets a small rounded clip as well.
replace_once(
    main,
    '''    val m = if (clickable) modifier.clickable { onClick() } else modifier\n    Surface(\n        modifier = m.height(56.dp),\n        shape = RoundedCornerShape(14.dp),''',
    '''    val shape = RoundedCornerShape(14.dp)\n    val m = if (clickable) modifier.clip(shape).clickable { onClick() } else modifier\n    Surface(\n        modifier = m.height(56.dp),\n        shape = shape,''',
    "clip toolbox status tile press indication",
)
replace_once(
    main,
    '''            val valueModifier = Modifier.weight(1f).let { base -> if (onValueClick != null) base.clickable { onValueClick() } else base }''',
    '''            val valueModifier = Modifier.weight(1f).let { base -> if (onValueClick != null) base.clip(RoundedCornerShape(8.dp)).clickable { onValueClick() } else base }''',
    "round toolbox copy press indication",
)

# The record cards use the same un-clipped combinedClickable pattern visible in
# the supplied video. This is the same visual defect; only clip the indication.
replace_once(
    main,
    '''                    .combinedClickable(onClick = { targetOffsetPx = 0f; onSwipeClose(); expanded = !expanded }, onLongClick = { targetOffsetPx = 0f; onSwipeClose(); onCopy() })''',
    '''                    .clip(RoundedCornerShape(14.dp))\n                    .combinedClickable(onClick = { targetOffsetPx = 0f; onSwipeClose(); expanded = !expanded }, onLongClick = { targetOffsetPx = 0f; onSwipeClose(); onCopy() })''',
    "clip expandable event card press indication",
)
replace_once(
    main,
    '''                    .combinedClickable(onClick = { targetOffsetPx = 0f; onSwipeClose(); showDetail = true }, onLongClick = { targetOffsetPx = 0f; onSwipeClose(); onCopy() })''',
    '''                    .clip(RoundedCornerShape(14.dp))\n                    .combinedClickable(onClick = { targetOffsetPx = 0f; onSwipeClose(); showDetail = true }, onLongClick = { targetOffsetPx = 0f; onSwipeClose(); onCopy() })''',
    "clip detail event card press indication",
)
replace_once(
    main,
    '''                    .shadow(0.dp, RoundedCornerShape(14.dp), clip = false)\n                    .combinedClickable(''',
    '''                    .shadow(0.dp, RoundedCornerShape(14.dp), clip = false)\n                    .clip(RoundedCornerShape(14.dp))\n                    .combinedClickable(''',
    "clip selectable event card press indication",
)

# Favorite cards had combinedClickable outside the Surface shape. Keep drag
# shadow and layout exactly as-is, only clip the normal press feedback.
replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''            }\n            .combinedClickable(onClick = onOpen, onLongClick = {})''',
    '''            }\n            .clip(LabV2.CompactCardShape)\n            .combinedClickable(onClick = onOpen, onLongClick = {})''',
    "clip favorite card press indication",
)

print("build196 focused glow/press cleanup applied")
