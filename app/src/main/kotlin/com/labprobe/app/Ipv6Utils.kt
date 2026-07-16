package com.labprobe.app

import java.net.Inet6Address
import java.net.InetAddress
import java.time.Instant
import java.util.Locale

data class Ipv6AddressCandidate(
    val address: String,
    val state: String = "",
    val source: String = "",
    val lastSeenAt: Long? = null,
    val primary: Boolean = false,
    val currentPrefix: Boolean = false,
    val historical: Boolean = false
)

data class Ipv6PickResult(
    val best: String?,
    val total: Int,
    val globalCount: Int,
    val ulaCount: Int,
    val temporaryCount: Int,
    val hiddenCount: Int
)

fun normalizeIpv6(raw: String): String? {
    var value = cleanApiText(raw).trim()
    if (value.isBlank()) return null
    if (value.startsWith("[") && value.contains(']')) value = value.substringAfter('[').substringBefore(']')
    value = value.substringBefore('/').substringBefore('%').trim().lowercase(Locale.ROOT)
    if (!value.contains(':') || value.any { it.isWhitespace() }) return null
    val parsed = runCatching { InetAddress.getByName(value) }.getOrNull() as? Inet6Address ?: return null
    return parsed.hostAddress.substringBefore('%').lowercase(Locale.ROOT)
}

private fun ipv6Bytes(ip: String): ByteArray? {
    val normalized = normalizeIpv6(ip) ?: return null
    return (runCatching { InetAddress.getByName(normalized) }.getOrNull() as? Inet6Address)?.address
}

fun isLinkLocalIpv6(ip: String): Boolean {
    val bytes = ipv6Bytes(ip) ?: return false
    return (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80
}

fun isMulticastIpv6(ip: String): Boolean = ipv6Bytes(ip)?.let { (it[0].toInt() and 0xFF) == 0xFF } == true

fun isIpv4MappedIpv6(ip: String): Boolean {
    val raw = ip.substringBefore('/').substringBefore('%').trim().lowercase(Locale.ROOT)
    if (raw.startsWith("::ffff:")) return true
    val bytes = ipv6Bytes(ip) ?: return false
    return bytes.take(10).all { it.toInt() == 0 } && bytes[10].toInt() == -1 && bytes[11].toInt() == -1
}

fun isUlaIpv6(ip: String): Boolean = ipv6Bytes(ip)?.let { (it[0].toInt() and 0xFE) == 0xFC } == true

fun isGlobalIpv6(ip: String): Boolean = ipv6Bytes(ip)?.let { (it[0].toInt() and 0xE0) == 0x20 } == true

private fun isRejectedIpv6State(state: String?): Boolean {
    val normalized = state.orEmpty().trim().lowercase(Locale.ROOT)
    return normalized == "failed" ||
        normalized == "incomplete" ||
        normalized == "noarp" ||
        normalized.contains("failed") ||
        normalized.contains("incomplete")
}

private fun ipv6StateRank(state: String?): Int {
    if (isRejectedIpv6State(state)) return 0
    val normalized = state.orEmpty().trim().lowercase(Locale.ROOT)
    return when {
        normalized.contains("reachable") -> 4
        normalized.contains("delay") || normalized.contains("probe") -> 3
        normalized.contains("stale") || normalized.contains("permanent") -> 2
        else -> 1
    }
}

private fun ipv6ScopeRank(ip: String): Int = when {
    isGlobalIpv6(ip) -> 2
    isUlaIpv6(ip) -> 1
    else -> 0
}

private fun isVerifiedNeighborIpv6(candidate: Ipv6AddressCandidate): Boolean {
    val sourceText = candidate.source.lowercase(Locale.ROOT)
    val fromNeighborTable = sourceText.contains("ndp") ||
        sourceText.contains("neighbor") ||
        sourceText.contains("neigh")
    return fromNeighborTable && ipv6StateRank(candidate.state) > 1
}

private fun isLoopbackIpv6(ip: String): Boolean = ipv6Bytes(ip)?.let { bytes ->
    bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
} == true

fun isInvalidIpv6(ip: String): Boolean {
    val normalized = normalizeIpv6(ip) ?: return true
    return isLinkLocalIpv6(normalized) ||
        isMulticastIpv6(normalized) ||
        isLoopbackIpv6(normalized) ||
        isIpv4MappedIpv6(ip) ||
        (!isGlobalIpv6(normalized) && !isUlaIpv6(normalized))
}

fun isSuspectedTemporaryIpv6(ip: String, source: String? = null): Boolean {
    val normalized = normalizeIpv6(ip) ?: return false
    if (!isGlobalIpv6(normalized)) return false
    val sourceText = source.orEmpty().lowercase(Locale.ROOT)
    // Stable RFC 7217 addresses also look random. Only explicit metadata may
    // classify an address as temporary/privacy.
    return listOf("temporary", "privacy", " temp", "隐私", "临时").any(sourceText::contains)
}

fun scoreIpv6(ip: String, state: String? = null, source: String? = null): Int {
    val normalized = normalizeIpv6(ip) ?: return Int.MIN_VALUE
    if (isRejectedIpv6State(state)) return Int.MIN_VALUE
    val base = when {
        isGlobalIpv6(normalized) -> 100
        isUlaIpv6(normalized) -> 40
        else -> return Int.MIN_VALUE
    }
    val stateScore = when (state.orEmpty().lowercase(Locale.ROOT)) {
        "reachable" -> 20
        "delay", "probe" -> 12
        "stale" -> 4
        else -> when {
            state.orEmpty().contains("reachable", ignoreCase = true) -> 20
            state.orEmpty().contains("delay", ignoreCase = true) || state.orEmpty().contains("probe", ignoreCase = true) -> 12
            state.orEmpty().contains("stale", ignoreCase = true) -> 4
            else -> 0
        }
    }
    val sourceText = source.orEmpty().lowercase(Locale.ROOT)
    val temporary = isSuspectedTemporaryIpv6(normalized, source)
    val sourceScore = when {
        sourceText.contains("dhcp") -> 10
        !temporary && listOf("slaac", "stable", "static", "manual", "eui").any(sourceText::contains) -> 8
        !temporary -> 8
        else -> 0
    }
    return base + stateScore + sourceScore - if (temporary) 10 else 0
}

private fun scoreIpv6Candidate(candidate: Ipv6AddressCandidate): Int {
    var score = scoreIpv6(candidate.address, candidate.state, candidate.source)
    if (score == Int.MIN_VALUE) return score
    val sourceText = candidate.source.lowercase(Locale.ROOT)
    if (candidate.primary || sourceText.contains("hub_primary")) score += 500
    if (sourceText.contains("hub_local")) score += 250
    if (candidate.currentPrefix) score += 25
    if (candidate.historical) score -= 300
    if (sourceText.contains("crosscheck")) score -= 120
    return score
}

fun parseIpv6Timestamp(raw: Any?): Long? {
    if (raw == null) return null
    if (raw is Number) {
        val value = raw.toLong()
        return if (value in 1..9_999_999_999L) value * 1000L else value.takeIf { it > 0L }
    }
    val text = cleanApiText(raw.toString())
    if (text.isBlank()) return null
    text.toLongOrNull()?.let { return if (it in 1..9_999_999_999L) it * 1000L else it.takeIf { value -> value > 0L } }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

fun mergeIpv6Candidates(vararg groups: List<Ipv6AddressCandidate>): List<Ipv6AddressCandidate> {
    val merged = linkedMapOf<String, Ipv6AddressCandidate>()
    groups.asSequence().flatten().forEach { raw ->
        val normalized = normalizeIpv6(raw.address) ?: return@forEach
        val candidate = raw.copy(address = normalized)
        val old = merged[normalized]
        if (old == null) {
            merged[normalized] = candidate
        } else {
            val oldRejected = isRejectedIpv6State(old.state)
            val candidateRejected = isRejectedIpv6State(candidate.state)
            val oldHasMetadata = old.source.isNotBlank() || old.state.isNotBlank() || old.primary || old.currentPrefix || old.historical
            val candidateHasMetadata = candidate.source.isNotBlank() || candidate.state.isNotBlank() || candidate.primary || candidate.currentPrefix || candidate.historical
            val preferred = when {
                oldRejected && ipv6StateRank(candidate.state) > 1 -> candidate
                candidateRejected && ipv6StateRank(old.state) > 1 -> old
                oldRejected -> old
                candidateRejected -> candidate
                oldHasMetadata && !candidateHasMetadata -> old
                candidateHasMetadata && !oldHasMetadata -> candidate
                scoreIpv6Candidate(candidate) > scoreIpv6Candidate(old) -> candidate
                else -> old
            }
            val currentPrefix = old.currentPrefix || candidate.currentPrefix
            merged[normalized] = preferred.copy(
                lastSeenAt = listOfNotNull(old.lastSeenAt, candidate.lastSeenAt).maxOrNull(),
                primary = old.primary || candidate.primary,
                currentPrefix = currentPrefix,
                historical = !currentPrefix && (old.historical || candidate.historical)
            )
        }
    }
    return merged.values.sortedWith(
        compareByDescending<Ipv6AddressCandidate> { ipv6ScopeRank(it.address) }
            .thenByDescending { ipv6StateRank(it.state) }
            .thenByDescending(::scoreIpv6Candidate)
            .thenByDescending { it.lastSeenAt ?: 0L }
            .thenBy { it.address }
    )
}

fun pickBestIpv6(addresses: List<String>, candidates: List<Ipv6AddressCandidate> = emptyList()): Ipv6PickResult {
    val rawCandidates = candidates + addresses.map { Ipv6AddressCandidate(it) }
    val normalized = mergeIpv6Candidates(rawCandidates)
    val eligible = normalized.filterNot { isInvalidIpv6(it.address) || isRejectedIpv6State(it.state) }
    val explicitPrimary = eligible.filter { it.primary && !it.historical }
    val pickPool = explicitPrimary.ifEmpty { eligible.filter(::isVerifiedNeighborIpv6).ifEmpty { eligible } }
    val latest = eligible.mapNotNull { it.lastSeenAt }.maxOrNull()
    val best = pickPool.maxWithOrNull(
        compareBy<Ipv6AddressCandidate> { ipv6ScopeRank(it.address) }
            .thenBy { ipv6StateRank(it.state) }
            .thenBy { scoreIpv6Candidate(it) + if (latest != null && it.lastSeenAt == latest) 5 else 0 }
            .thenBy { it.lastSeenAt ?: 0L }
            .thenBy { it.address }
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

fun ipv6Summary(result: Ipv6PickResult): String = buildList {
    add("IPv6 共 ${result.total} 个")
    if (result.globalCount > 0) add("公网 ${result.globalCount}")
    if (result.ulaCount > 0) add("内网 ${result.ulaCount}")
    if (result.temporaryCount > 0) add("临时 ${result.temporaryCount}")
}.joinToString(" · ")

fun DeviceItem.pickIpv6(): Ipv6PickResult = pickBestIpv6(ipv6, ipv6Candidates)
