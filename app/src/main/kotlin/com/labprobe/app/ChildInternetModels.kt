package com.labprobe.app

enum class ChildInternetTab(val title: String) {
    REPORT("上网报告"),
    PLAN("上网计划"),
    ATTENTION("家长请注意")
}

enum class GuardStatus(val label: String) {
    UNRESTRICTED("当前网络无限制"),
    GUARDED("守护中"),
    BLOCKED("已禁网")
}

data class SelectableAppItem(
    val id: String,
    val name: String,
    val iconKey: String,
    val localIconPath: String? = null,
    val selected: Boolean = true,
    /** A catalog entry may expand to several vendor RDPI signatures. */
    val rdpiIds: Set<String> = emptySet()
)

data class AppCategoryPlan(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val apps: List<SelectableAppItem>
) {
    val allowedCount: Int get() = apps.count { it.selected }
}

data class DeviceGuardPlan(
    val id: String = "",
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val startTime: String = "17:00",
    val endTime: String = "21:30",
    val repeatDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val categories: List<AppCategoryPlan> = emptyList()
) {
    /** The flattened allow-list sent to Hub; do not collapse this to one AppID per UI app. */
    val allowedRdpiIds: Set<String>
        get() = expandRdpiIds(categories.filter { it.enabled }.flatMap { it.apps })
}

internal fun expandRdpiIds(apps: Iterable<SelectableAppItem>): Set<String> =
    apps.filter { it.selected }.flatMap { it.rdpiIds }.toSet()

data class ChildGuardCapabilities(
    val childGuard: Boolean = false,
    val rdpi: Boolean = false,
    val version: String = "",
    val supported: Boolean = childGuard && rdpi
) {
    val appManagementSupported: Boolean get() = childGuard && rdpi && supported
}

enum class RuntimeEffectPolicy {
    NONE,
    ACTIVE,
    UNKNOWN
}

data class ChildGuardRuntimeState(
    val deviceId: String = "",
    val boundPolicyIds: Set<String> = emptySet(),
    val effectPolicyId: String? = null,
    val effectPolicy: RuntimeEffectPolicy = RuntimeEffectPolicy.NONE,
    val paused: Boolean = false
)

data class ProtectedDeviceSummary(
    val deviceId: String,
    val name: String,
    val iconKey: String,
    val accentArgb: Int,
    val status: GuardStatus,
    val todayMinutes: Int,
    val hasAttention: Boolean,
    val appManagementSupported: Boolean = false,
    val experimentalAppControl: Boolean = false,
    /** Router UID and station MAC are not guaranteed to be the same value. */
    val macAddresses: Set<String> = emptySet()
)

data class UsageBar(
    val label: String,
    val minutes: Int
)

data class InternetUsageEntry(
    val id: String,
    val appName: String,
    val iconKey: String,
    val localIconPath: String? = null,
    val durationMinutes: Int,
    val timeRange: String,
    val count: Int
)

data class InternetUsageSummary(
    val totalMinutes: Int,
    val bars: List<UsageBar>,
    val entries: List<InternetUsageEntry>
)

data class ParentAttentionEntry(
    val dayLabel: String,
    val date: String,
    val message: String,
    val normal: Boolean
)

data class ChildInternetDeviceState(
    val summary: ProtectedDeviceSummary,
    val plan: DeviceGuardPlan,
    val plans: List<DeviceGuardPlan> = plan.takeIf { it.configured }?.let(::listOf) ?: emptyList(),
    val runtime: ChildGuardRuntimeState = ChildGuardRuntimeState(deviceId = summary.deviceId),
    val todayUsage: InternetUsageSummary = InternetUsageSummary(0, emptyList(), emptyList()),
    val recentUsage: InternetUsageSummary = InternetUsageSummary(0, emptyList(), emptyList()),
    val attentionEntries: List<ParentAttentionEntry> = emptyList(),
    /**
     * Where [todayUsage]/[recentUsage] came from: `hub` (the router pushed
     * aggregates), `relay` (read live off the router) or `empty`. Diagnostic
     * only — the report page renders the same way for all three.
     */
    val usageSource: String = ""
)

data class ChildInternetOverviewState(
    val masterEnabled: Boolean,
    val devices: List<ChildInternetDeviceState>,
    val capabilities: ChildGuardCapabilities = ChildGuardCapabilities(),
    val loading: Boolean = false,
    val error: String = ""
)

/**
 * A LAN device from the router's DHCP lease table, as returned by the
 * `list_devices` relay command. `guarded` distinguishes already-managed
 * devices (with their `uid`/`name`) from candidates still awaiting selection.
 */
data class ChildGuardDeviceCandidate(
    val mac: String,
    val ip: String,
    val hostname: String,
    val guarded: Boolean,
    val uid: String = "",
    val name: String = ""
) {
    val displayName: String get() = name.ifBlank { hostname.ifBlank { mac } }
}

/**
 * `none` is the firmware's normal inactive value. Any non-empty, unbound PID
 * remains visible as UNKNOWN instead of being presented as an active plan.
 */
internal fun mapRuntimeEffectPolicy(effectPolicyId: String?, boundPolicyIds: Set<String>): RuntimeEffectPolicy {
    val policy = effectPolicyId?.trim().orEmpty()
    return when {
        policy.isBlank() || policy.equals("none", ignoreCase = true) -> RuntimeEffectPolicy.NONE
        policy in boundPolicyIds -> RuntimeEffectPolicy.ACTIVE
        else -> RuntimeEffectPolicy.UNKNOWN
    }
}

internal fun isExperimentalAppControlDevice(iconKey: String): Boolean =
    iconKey.lowercase() in setOf("computer", "desktop", "laptop", "mini_pc", "mac_mini", "all_in_one")
