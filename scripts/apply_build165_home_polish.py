#!/usr/bin/env python3
"""Build165 follow-up: compact home cards, fast rearranging and rounded touch feedback."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
MAIN = SRC / "MainActivity.kt"
ROUTER_CONTROL = SRC / "RouterControlUi.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build165 home anchor: {label}")
    return text.replace(old, new, 1)


def replace_section(text: str, start: str, end: str, new: str, label: str) -> str:
    begin = text.find(start)
    finish = text.find(end, begin + len(start))
    if begin < 0 or finish < 0:
        raise RuntimeError(f"missing build165 home section: {label}")
    return text[:begin] + new + text[finish:]


def patch_main(text: str) -> str:
    # Every clickable health card clips the indication before clickable is installed.
    text = text.replace(
        "HealthCard(Modifier.clickable { onClick() })",
        "HealthCard(Modifier.clip(RoundedCornerShape(30.dp)).clickable { onClick() })",
    )
    text = text.replace(
        "modifier = Modifier.weight(1f).clickable { onNavigate(\"devices\") }",
        "modifier = Modifier.weight(1f).clip(RoundedCornerShape(30.dp)).clickable { onNavigate(\"devices\") }",
        1,
    )
    text = text.replace(
        "modifier = Modifier.weight(1f).clickable { onNavigate(\"events\") }",
        "modifier = Modifier.weight(1f).clip(RoundedCornerShape(30.dp)).clickable { onNavigate(\"events\") }",
        1,
    )

    # Faster long-press rearrangement response and less delayed placement animation.
    text = text.replace(
        ".animateContentSize(animationSpec = tween(220))",
        ".animateContentSize(animationSpec = tween(120))",
        1,
    )
    text = text.replace(
        "val scale by animateFloatAsState(if (dragging) 0.982f else 1f, animationSpec = tween(180), label = \"home-card-scale\")",
        "val scale by animateFloatAsState(if (dragging) 0.986f else 1f, animationSpec = tween(90), label = \"home-card-scale\")",
        1,
    )
    text = text.replace(
        "val thresholdPx = with(LocalDensity.current) { 128.dp.toPx() }",
        "val thresholdPx = with(LocalDensity.current) { 72.dp.toPx() }",
        1,
    )

    # SSH is intentionally a quiet light-grey tool card, distinct from blue network tools.
    tile = '''@Composable
fun ToolMosaicTile(item: ToolMosaicItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val tileBrush = if (item.route == "tool_ssh") {
        Brush.verticalGradient(listOf(Color(0xFFF5F7FA), Color(0xFFE9EDF3)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFCFEFF), item.color.copy(alpha = .055f)))
    }
    val tileBorder = if (item.route == "tool_ssh") Color(0xFFD7DEE8) else LabV2.Border.copy(alpha = .86f)
    Column(
        modifier
            .clip(shape)
            .background(tileBrush)
            .border(1.dp, tileBorder, shape)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LabV2ToolIcon(item.icon, item.color, size = 44)
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            fontSize = 10.2.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            color = LabV2.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

'''
    text = replace_section(
        text,
        "@Composable\nfun ToolMosaicTile(",
        "@Composable\nfun ToolEntry(",
        tile,
        "ToolMosaicTile",
    )

    # Put realtime status at the score card's upper-right and remove the extra status row.
    score_old = '''                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("网络健康得分", Modifier.weight(1f), fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1)
                        Surface(shape = RoundedCornerShape(99.dp), color = scoreColor.copy(alpha = .10f)) {
                            Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(13.dp), tint = scoreColor)
                                Text(scoreLabel, fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = scoreColor)
                            }
                        }
                    }
                    Text(message.replace("刷新成功：", "最后刷新 ").ifBlank { lastRefresh.ifBlank { "等待刷新" } }, fontSize = 10.8.sp, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)'''
    score_new = '''                    Row(verticalAlignment = Alignment.Top) {
                        Text("网络健康得分", Modifier.weight(1f).padding(top = 2.dp), fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1)
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Surface(shape = RoundedCornerShape(99.dp), color = scoreColor.copy(alpha = .10f)) {
                                Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(13.dp), tint = scoreColor)
                                    Text(scoreLabel, fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = scoreColor)
                                }
                            }
                            Text(
                                message.replace("刷新成功：", "").ifBlank { lastRefresh.ifBlank { "等待同步" } },
                                fontSize = 8.8.sp,
                                lineHeight = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LabV2.InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }'''
    text = replace_once(text, score_old, score_new, "score realtime status")
    text = text.replace("            Spacer(Modifier.height(11.dp))\n            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {", "            Spacer(Modifier.height(8.dp))\n            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {", 1)

    # Today overview updates local counts immediately, bounds the network wait, and keeps sync text at top-right.
    today = '''@Composable
fun HealthTodayCard(prefs: AppPrefs, state: AppState, lastRefresh: String, onClick: () -> Unit = {}) {
    val today = todayDateString()
    val fallback = remember(state.events, today) { homeDailyFromEvents(state.events, today) }
    var snapshot by remember(today, prefs.hub, prefs.token) { mutableStateOf(fallback) }

    LaunchedEffect(fallback) {
        snapshot = fallback
    }
    LaunchedEffect(today, prefs.hub, prefs.token, lastRefresh) {
        if (prefs.hub.isBlank()) return@LaunchedEffect
        val remote = kotlinx.coroutines.withTimeoutOrNull(2_500L) {
            runCatching { HubApi(prefs).getDaily(today) }.getOrNull()
        }
        if (remote != null) {
            snapshot = homeDailyFromApi(remote, today, fallback)
        } else if (snapshot == fallback) {
            snapshot = fallback.copy(source = "本地事件缓存")
        }
    }

    val syncLabel = if (snapshot.source.startsWith("已同步")) "实时同步" else "本地缓存"
    val syncColor = if (syncLabel == "实时同步") Color(0xFF16A34A) else Color(0xFF64748B)
    HealthCard(Modifier.clip(RoundedCornerShape(30.dp)).clickable { onClick() }, verticalPadding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2563EB).copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CalendarMonth, null, tint = Color(0xFF2563EB), modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("今日概览", fontSize = 17.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), maxLines = 1)
                Text("设备、VPN 与 DDNS 今日变化", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B), maxLines = 1)
            }
            Surface(shape = RoundedCornerShape(99.dp), color = syncColor.copy(alpha = .10f)) {
                Text(syncLabel, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 9.2.sp, lineHeight = 10.sp, fontWeight = FontWeight.Black, color = syncColor)
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthStatusBadge("设备上线", "${snapshot.up} 次", Color(0xFF16A34A), Modifier.weight(1f))
            HealthStatusBadge("设备下线", "${snapshot.down} 次", Color(0xFFEF4444), Modifier.weight(1f))
            HealthStatusBadge("VPN-STUN", "${snapshot.vpn} 次", Color(0xFF7C3AED), Modifier.weight(1f))
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthStatusBadge("DDNS", "${snapshot.ddns} 次", Color(0xFF0EA5E9), Modifier.weight(1f))
            HealthStatusBadge("备注", if (snapshot.hasNote) "1 条" else "0 条", Color(0xFF64748B), Modifier.weight(1f))
        }
    }
}

'''
    text = replace_section(
        text,
        "@Composable\nfun HealthTodayCard(",
        "@Composable\nfun HomeReorderableCard(",
        today,
        "HealthTodayCard",
    )
    return text


def patch_router_control(text: str) -> str:
    card = '''@Composable
private fun NativePortRuleCard(rule: NativePortMapRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(rule.ruleName) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, RouterBlue.copy(alpha = .10f)),
        shadowElevation = 1.5.dp
    ) {
        Row(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(RouterBlue.copy(alpha = .038f), Color.Transparent))).padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RouterGlyphIcon(RouterGlyph.Port, RouterBlue, Modifier.size(27.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.ruleName, Modifier.weight(1f), fontSize = 12.6.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TinyBadge(rule.proto.uppercase(), RouterBlue)
                }
                Text("WAN ${rule.srcPort}  →  ${rule.destIp}:${rule.destPort}", fontSize = 10.7.sp, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, null, Modifier.size(17.dp), tint = RouterMuted) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(22.dp),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 9.dp
                ) {
                    DropdownMenuItem(text = { Text("编辑", fontSize = 11.8.sp, fontWeight = FontWeight.SemiBold) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(15.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", fontSize = 11.8.sp, fontWeight = FontWeight.SemiBold, color = RouterRed) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(15.dp), tint = RouterRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

'''
    text = replace_section(
        text,
        "@Composable\nprivate fun NativePortRuleCard(",
        "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun NativePortEditorSheet(",
        card,
        "rounded NativePortRuleCard",
    )
    return text


def verify(main: str, router: str) -> None:
    required = (
        'item.route == "tool_ssh"',
        'Color(0xFFE9EDF3)',
        'val thresholdPx = with(LocalDensity.current) { 72.dp.toPx() }',
        'animationSpec = tween(90)',
        'val syncLabel = if (snapshot.source.startsWith("已同步")) "实时同步" else "本地缓存"',
        'kotlinx.coroutines.withTimeoutOrNull(2_500L)',
        'HealthCard(Modifier.clip(RoundedCornerShape(30.dp)).clickable',
        'Surface(\n        onClick = onEdit,',
    )
    combined = main + "\n" + router
    missing = [item for item in required if item not in combined]
    if missing:
        raise RuntimeError(f"build165 home verification failed: {missing}")
    if 'HealthCard(Modifier.clickable { onClick() })' in main:
        raise RuntimeError("square health-card touch indication remains")


def apply() -> None:
    main = patch_main(MAIN.read_text(encoding="utf-8"))
    router = patch_router_control(ROUTER_CONTROL.read_text(encoding="utf-8"))
    MAIN.write_text(main, encoding="utf-8")
    ROUTER_CONTROL.write_text(router, encoding="utf-8")
    verify(main, router)
    print("Build165 light-grey SSH tile, compact realtime labels and rounded interactions prepared")


if __name__ == "__main__":
    apply()
