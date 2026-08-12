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
    "versionCode = 191",
    "versionCode = 192",
    "versionCode",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''        if (index >= 0) {
            val old = current[index]
            val wanUrl = mergeWebhookFavoriteUrl(generatedWanUrl, old.wanUrl)
            val description = old.description.takeUnless { it == "Webhook 自动同步" }.orEmpty()
            if (old.id != generatedId || old.title != title || old.wanUrl != wanUrl || old.description != description) {
                current[index] = old.copy(id = generatedId, title = title, description = description, wanUrl = wanUrl)
                changes++
            }
        } else {
            current += FavoriteShortcut(
                id = generatedId,
                title = title,
                description = "",
                iconType = "builtin",
                iconValue = webhookBuiltinIcon(title),
                lanUrl = "",
                wanUrl = generatedWanUrl,
                order = current.size
            )
            changes++
        }
''',
    '''        if (index >= 0) {
            val old = current[index]
            val previousRemote = old.remoteEndpoint.ifBlank { old.wanUrl }
            val wanUrl = mergeWebhookFavoriteUrl(generatedWanUrl, previousRemote)
            val description = old.description.takeUnless { it == "Webhook 自动同步" }.orEmpty()
            if (
                old.id != generatedId || old.title != title || old.wanUrl != wanUrl ||
                old.remoteEndpoint != wanUrl || old.description != description
            ) {
                current[index] = old.copy(
                    id = generatedId,
                    title = title,
                    description = description,
                    wanUrl = wanUrl,
                    remoteEndpoint = wanUrl,
                )
                changes++
            }
        } else {
            current += FavoriteShortcut(
                id = generatedId,
                title = title,
                description = "",
                iconType = "builtin",
                iconValue = webhookBuiltinIcon(title),
                lanUrl = "",
                wanUrl = generatedWanUrl,
                order = current.size,
                remoteEndpoint = generatedWanUrl,
            )
            changes++
        }
''',
    "webhook favorite keeps remote endpoint in sync",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''    if (changes > 0) saveFavoriteShortcuts(current)
    return changes
}

private fun webhookFavoriteMarker(event: EventItem): Pair<String, String>? {
''',
    '''    if (changes > 0) saveFavoriteShortcuts(current)
    return changes
}

/**
 * Event-driven favorite refresh for the same VPN/STUN rows shown on Home.
 * No polling is introduced: callers invoke this only after a successful data sync.
 * Only webhook-managed favorites with an exact service-title match are updated,
 * preserving any user-entered path/query/fragment suffix.
 */
fun AppPrefs.syncHomeVpnFavoriteShortcuts(rows: List<Pair<String, String>>): Int {
    if (rows.isEmpty()) return 0
    val liveByTitle = LinkedHashMap<String, String>()
    rows.forEach { (title, address) ->
        val key = title.trim().lowercase(Locale.ROOT)
        val value = address.trim()
        if (key.isNotBlank() && value.isNotBlank()) liveByTitle[key] = value
    }
    if (liveByTitle.isEmpty()) return 0

    val current = favoriteShortcuts().toMutableList()
    var changes = 0
    current.forEachIndexed { index, old ->
        if (!old.id.startsWith("webhook-")) return@forEachIndexed
        val liveAddress = liveByTitle[old.title.trim().lowercase(Locale.ROOT)] ?: return@forEachIndexed
        val liveOrigin = webhookFavoriteOrigin(liveAddress)
        if (liveOrigin.isBlank()) return@forEachIndexed
        val previousRemote = old.remoteEndpoint.ifBlank { old.wanUrl }
        val syncedRemote = mergeWebhookFavoriteUrl(liveOrigin, previousRemote)
        if (syncedRemote.isBlank()) return@forEachIndexed
        val updated = old.copy(remoteEndpoint = syncedRemote, wanUrl = syncedRemote)
        if (updated != old) {
            current[index] = updated
            changes++
        }
    }
    if (changes > 0) saveFavoriteShortcuts(current)
    return changes
}

private fun webhookFavoriteMarker(event: EventItem): Pair<String, String>? {
''',
    "home vpn favorite sync helper",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/MainActivity.kt",
    '''    private fun finishSuccessfulSync(previousEventKeys: Set<String>, dataChanged: Boolean, silent: Boolean) {
        if (dataChanged && prefs.syncWebhookFavoriteShortcuts(events) > 0) favoriteSyncVersion++
        CertificateReminderCenter.notifyDue(appContext, prefs)
''',
    '''    private fun finishSuccessfulSync(previousEventKeys: Set<String>, dataChanged: Boolean, silent: Boolean) {
        if (dataChanged) {
            val webhookFavoriteChanges = prefs.syncWebhookFavoriteShortcuts(events)
            val currentData = status?.optJSONObject("data") ?: status
            val currentNas = currentData?.optJSONObject("nas")
            val currentRouter = currentData?.optJSONObject("router")
            val currentNasV6 = safeNasIpv6ForUi(currentNas, currentRouter)
            val liveVpnRows = buildVpnRowsForHome(currentData, currentNasV6, events)
            val liveFavoriteChanges = prefs.syncHomeVpnFavoriteShortcuts(liveVpnRows)
            if (webhookFavoriteChanges + liveFavoriteChanges > 0) favoriteSyncVersion++
        }
        CertificateReminderCenter.notifyDue(appContext, prefs)
''',
    "successful sync refreshes linked favorite endpoints",
)

replace_once(
    "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt",
    '''        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            FavoriteIcon(shortcut.iconType, shortcut.iconValue, 38)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(shortcut.title, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortcut.serviceType.ifBlank { shortcut.description.ifBlank { "网页入口" } }, fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = accessReport?.let(::favoriteAccessStatus) ?: favoriteServiceStatus(shortcut, mode, mapping, devices)
                Text(status, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = when {
                    status == "内网" -> LabV2.Green
                    status == "外网" -> LabV2.Primary
                    else -> LabV2.Red
                }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(18.dp), tint = LabV2.InkMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(12.dp), containerColor = Color.White) {
                    DropdownMenuItem(text = { Text("编辑", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("复制地址", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(17.dp)) }, onClick = { menu = false; onCopyAddress() })
                    if (shortcut.mappingId != null) {
                        DropdownMenuItem(text = { Text("查看关联映射", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(17.dp)) }, onClick = { menu = false; onViewMapping() })
                    }
                    DropdownMenuItem(text = { Text("删除", color = LabV2.Red, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = LabV2.Red) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(30.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary.copy(alpha = .10f), contentColor = LabV2.Primary), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text("打开", fontSize = 10.8.sp, fontWeight = FontWeight.SemiBold)
        }
''',
    '''        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FavoriteIcon(shortcut.iconType, shortcut.iconValue, 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(shortcut.title, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = accessReport?.let(::favoriteAccessStatus) ?: favoriteServiceStatus(shortcut, mode, mapping, devices)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        shortcut.serviceType.ifBlank { "网页入口" },
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = LabV2.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.size(3.dp).clip(CircleShape).background(LabV2.InkMuted.copy(alpha = .45f)))
                    Text(
                        status,
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            status == "内网" -> LabV2.Green
                            status == "外网" -> LabV2.Primary
                            else -> LabV2.Red
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                shortcut.description.takeIf { it.isNotBlank() && it != shortcut.serviceType }?.let { description ->
                    Text(description, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(18.dp), tint = LabV2.InkMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(12.dp), containerColor = Color.White) {
                    DropdownMenuItem(text = { Text("编辑", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("复制地址", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(17.dp)) }, onClick = { menu = false; onCopyAddress() })
                    if (shortcut.mappingId != null) {
                        DropdownMenuItem(text = { Text("查看关联映射", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(17.dp)) }, onClick = { menu = false; onViewMapping() })
                    }
                    DropdownMenuItem(text = { Text("删除", color = LabV2.Red, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = LabV2.Red) }, onClick = { menu = false; onDelete() })
                }
            }
        }
''',
    "favorite card whole-card open layout",
)

print("build192 favorite sync and card polish applied")
