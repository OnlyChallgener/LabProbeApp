from pathlib import Path

stun_path = Path('app/src/main/kotlin/com/labprobe/app/StunPenetration.kt')
stun = stun_path.read_text(encoding='utf-8')

old = '        if (listenPort.isNotBlank()) put("listenPort", listenPort.toIntOrNull() ?: 0)\n'
new = '        put("listenPort", listenPort.toIntOrNull() ?: 0)\n'
assert old in stun, 'STUN listenPort serialization anchor missing'
stun = stun.replace(old, new, 1)

old = '''                        val targetText = if (rule.targetType == "router_self") "路由器本机:${rule.targetPort}" else "${rule.targetIpv4}:${rule.targetPort}"
                        Text(
                            if (!agentOnline && endpoint.isNotBlank()) "上次映射至 $targetText · 当前未验证"
                            else if (rule.usesRouterNativeMapping) "路由器直连至 $targetText · 外网可达性取决于上级 NAT"
                            else "LabRelay 本地代理至 $targetText",
                            color = LabV2.InkMuted,
                            fontSize = LabTypography.Caption.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
'''
new = '''                        val targetText = if (rule.targetType == "router_self") "路由器本机:${rule.targetPort}" else "${rule.targetIpv4}:${rule.targetPort}"
                        val channelText = "中间端口 ${rule.listenPort}"
                        Text(
                            if (!agentOnline && endpoint.isNotBlank()) "$channelText · 上次映射至 $targetText · 当前未验证"
                            else if (rule.usesRouterNativeMapping) "$channelText → $targetText · 路由器直连"
                            else "$channelText → $targetText · LabRelay 本地代理",
                            color = LabV2.InkMuted,
                            fontSize = LabTypography.Caption.fontSize,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
'''
assert old in stun, 'STUN card target anchor missing'
stun = stun.replace(old, new, 1)
stun_path.write_text(stun, encoding='utf-8')

ipv6_path = Path('app/src/main/kotlin/com/labprobe/app/Ipv6Utils.kt')
ipv6 = ipv6_path.read_text(encoding='utf-8')

old = '''private fun isRejectedIpv6State(state: String?): Boolean {
    val normalized = state.orEmpty().trim().lowercase(Locale.ROOT)
    return normalized == "failed" ||
        normalized == "incomplete" ||
        normalized == "noarp" ||
        normalized.contains("failed") ||
        normalized.contains("incomplete")
}
'''
new = '''private fun isRejectedIpv6State(state: String?): Boolean {
    val normalized = state.orEmpty().trim().lowercase(Locale.ROOT)
    return normalized == "failed" ||
        normalized == "incomplete" ||
        normalized == "noarp" ||
        normalized.contains("failed") ||
        normalized.contains("incomplete") ||
        normalized.contains("tentative") ||
        normalized.contains("dadfailed") ||
        normalized.contains("deprecated") ||
        normalized.contains("duplicate") ||
        normalized.contains("invalid")
}
'''
assert old in ipv6, 'IPv6 rejected-state anchor missing'
ipv6 = ipv6.replace(old, new, 1)

anchor = '''private fun ipv6ScopeRank(ip: String): Int = when {
    isGlobalIpv6(ip) -> 2
    isUlaIpv6(ip) -> 1
    else -> 0
}
'''
addition = anchor + '''
private fun ipv6CompactnessRank(ip: String): Int {
    val bytes = ipv6Bytes(ip) ?: return Int.MIN_VALUE
    var zeroGroups = 0
    var longestZeroRun = 0
    var currentZeroRun = 0
    for (index in bytes.indices step 2) {
        val zero = bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0
        if (zero) {
            zeroGroups += 1
            currentZeroRun += 1
            longestZeroRun = maxOf(longestZeroRun, currentZeroRun)
        } else {
            currentZeroRun = 0
        }
    }
    return zeroGroups * 16 + longestZeroRun
}
'''
assert anchor in ipv6, 'IPv6 scope-rank anchor missing'
ipv6 = ipv6.replace(anchor, addition, 1)

start = ipv6.index('fun pickBestIpv6(')
end = ipv6.index('\nfun ipv6Summary(', start)
new_func = '''fun pickBestIpv6(addresses: List<String>, candidates: List<Ipv6AddressCandidate> = emptyList()): Ipv6PickResult {
    val rawCandidates = candidates + addresses.map { Ipv6AddressCandidate(it) }
    val normalized = mergeIpv6Candidates(rawCandidates)
    val eligible = normalized.filterNot { isInvalidIpv6(it.address) || isRejectedIpv6State(it.state) }

    // Prefer live/current records first, then public scope. Within a usable
    // public set, prefer a compact/stable address (for example ::1c3b)
    // instead of a long privacy-style IID. Reachability and recency remain
    // tie-breakers, and rejected states never enter the pool.
    val active = eligible.filterNot { it.historical }.ifEmpty { eligible }
    val publicPool = active.filter { isGlobalIpv6(it.address) }.ifEmpty { active }
    val prefixPool = publicPool.filter { it.currentPrefix }.ifEmpty { publicPool }
    val stablePool = prefixPool.filterNot { isSuspectedTemporaryIpv6(it.address, it.source) }.ifEmpty { prefixPool }
    val best = stablePool.maxWithOrNull(
        compareBy<Ipv6AddressCandidate> { ipv6CompactnessRank(it.address) }
            .thenBy { ipv6StateRank(it.state) }
            .thenBy { if (isVerifiedNeighborIpv6(it)) 1 else 0 }
            .thenBy { if (it.primary) 1 else 0 }
            .thenBy(::scoreIpv6Candidate)
            .thenBy { it.lastSeenAt ?: 0L }
    )
    return Ipv6PickResult(
        best = best?.address,
        total = eligible.size,
        globalCount = eligible.count { isGlobalIpv6(it.address) },
        ulaCount = eligible.count { isUlaIpv6(it.address) },
        temporaryCount = eligible.count { isSuspectedTemporaryIpv6(it.address, it.source) },
        hiddenCount = (normalized.size - eligible.size).coerceAtLeast(0)
    )
}
'''
ipv6 = ipv6[:start] + new_func + ipv6[end:]
ipv6_path.write_text(ipv6, encoding='utf-8')

test_path = Path('app/src/test/kotlin/com/labprobe/app/Ipv6UtilsTest.kt')
test = test_path.read_text(encoding='utf-8')
marker = '\n}\n'
assert test.endswith(marker), 'IPv6 test class ending missing'
tests = '''

    @Test
    fun compactCurrentGlobalAddressWinsOverLongPrimaryAddress() {
        val short = "2409:8a50:2e41:b700::1c3b"
        val long = "2409:8a50:2e41:b700:8401:3f89:8f04:e660"
        val result = pickBestIpv6(
            emptyList(),
            listOf(
                Ipv6AddressCandidate(long, state = "REACHABLE", source = "hub_primary", primary = true),
                Ipv6AddressCandidate(short, state = "STALE", source = "router_ndp", currentPrefix = true),
            ),
        )
        assertEquals(normalizeIpv6(short), result.best)
    }

    @Test
    fun tentativeAndDeprecatedAddressesAreExcluded() {
        val badShort = "2409:8a50:2e41:b700::1c3b"
        val good = "2409:8a50:2e41:b700:7e2b:e1ff:fe13:bef4"
        val result = pickBestIpv6(
            emptyList(),
            listOf(
                Ipv6AddressCandidate(badShort, state = "TENTATIVE", source = "router_ndp", currentPrefix = true),
                Ipv6AddressCandidate(good, state = "REACHABLE", source = "router_ndp", currentPrefix = true),
            ),
        )
        assertEquals(normalizeIpv6(good), result.best)
        assertEquals(1, result.hiddenCount)
    }
'''
test = test[:-len(marker)] + tests + marker
test_path.write_text(test, encoding='utf-8')

stun_test_path = Path('app/src/test/kotlin/com/labprobe/app/StunModelTest.kt')
stun_test = stun_test_path.read_text(encoding='utf-8')
assert 'assertEquals(7, json.length())' in stun_test
stun_test = stun_test.replace('assertEquals(7, json.length())', 'assertEquals(8, json.length())', 1)
old = '        assertFalse(automatic.has("listenPort"))\n'
new = '        assertTrue(automatic.has("listenPort"))\n        assertEquals(0, automatic.getInt("listenPort"))\n'
assert old in stun_test, 'STUN automatic-port test anchor missing'
stun_test = stun_test.replace(old, new, 1)
stun_test_path.write_text(stun_test, encoding='utf-8')
