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
    val rdpiIds: Set<String> = emptySet(),
    /** 特征库 note 原文：这条特征还包含哪些子应用（抖音系列 -> 抖音、抖音极速版…）。 */
    val note: String = ""
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
    val paused: Boolean = false,
    val blockedUntilEpoch: Long = 0L,
    /** 临时放行的截止时间（epoch 秒）。0 = 没在放行。固件的 skip 通道。 */
    val passUntilEpoch: Long = 0L
)

/**
 * 身份、守护状态与在线状态。统计数字一律不在这里：
 * 今日分钟数、深夜分钟数、提醒只由上网报告写入（见 `ChildUsageStats`），
 * 总览聚合只写 `presence`，两个写入者不会互相覆盖。
 */
data class ProtectedDeviceSummary(
    val deviceId: String,
    val name: String,
    val iconKey: String,
    val accentArgb: Int,
    val status: GuardStatus,
    val appManagementSupported: Boolean = false,
    val experimentalAppControl: Boolean = false,
    /** Router UID and station MAC are not guaranteed to be the same value. */
    val macAddresses: Set<String> = emptySet(),
    /** 在线 = 设备连着路由器；与「正在上网」是两回事。 */
    val isOnline: Boolean = false,
    val blockedUntilEpoch: Long = 0L,
    /** 临时放行截止时间；Hub 在放行期间已经把 `status`/禁网时间算成「此刻能不能上网」。 */
    val passUntilEpoch: Long = 0L
)

/** `/child-guard/overview` 独有的存在性字段，唯一写入者是总览聚合。 */
data class ChildGuardPresence(
    val online: Boolean,
    /** 正在上网 = 当前或上一个自然分钟有真实业务流量；null = 服务器没说。 */
    val activeNow: Boolean? = null,
    val lastSeenAtEpoch: Long? = null,
    val updatedAtEpoch: Long? = null
)

/**
 * Hub 用缓存的上网计划算出来的「此刻生效态」，唯一写入者也是总览聚合。
 *
 * 时段和「多久之后变」都是服务器给的：App 只把 `nextChangeAtEpoch` 换算成还剩
 * 多久，绝不自己从当前时间推任何一段时间。
 */
data class ChildGuardSchedule(
    /** unrestricted / allowed / partial / blocked；空或 unknown = Hub 还没读过这台设备的计划。 */
    val state: String = "",
    val planCount: Int = 0,
    val currentStart: String = "",
    val currentEnd: String = "",
    val nextChangeAtEpoch: Long? = null,
    val minutesToChange: Int? = null
) {
    /** 「不知道」和「不受限」是两句话，界面对这两句的处理也不同。 */
    val known: Boolean get() = state.isNotBlank() && state != "unknown"
}

/** 「最近10天」逐日详情：那一天的报告，和它是不是还在抓。 */
data class ChildGuardDayUsage(
    val date: String,
    val usage: InternetUsageSummary,
    val loading: Boolean = false
)

data class UsageBar(
    val label: String,
    val minutes: Int,
    val entries: List<InternetUsageEntry> = emptyList(),
    val date: String = "",
    val lateNightMinutes: Int? = null,
    val lateNightWindows: List<LateNightWindow> = emptyList(),
    val hasData: Boolean = false,
    /** 小时桶才有值（-1 = 自然日柱），界面按它标注「N点」，不再自己补 24 格。 */
    val hour: Int = -1
)

/** One concrete 00:00–06:00 usage window, for the 家长请注意 red ranges. */
data class LateNightWindow(
    val app: String,
    val rangeText: String,
    val minutes: Int
)

data class AppUsageSession(
    val timeRange: String,
    val durationText: String
)

data class InternetUsageEntry(
    val id: String,
    val appName: String,
    val iconKey: String,
    val localIconPath: String? = null,
    val durationMinutes: Int,
    val timeRange: String,
    val count: Int,
    val sessions: List<AppUsageSession> = emptyList(),
    /** 该应用自己的小时分布（Hub `apps[].hourlyMinutes`）；缺字段就是空，不补零。 */
    val hourlyMinutes: Map<Int, Int> = emptyMap()
)

/** 一台设备某一天的真实统计。唯一写入者是上网报告（`applyUsage`），总览只读不写。 */
data class ChildUsageStats(
    val date: String = "",
    /** null = Hub 还没有这一天的统计（渲染 `--`）；0 = 真的 0 分钟（无上网记录）。 */
    val onlineMinutes: Int? = null,
    val lateNightMinutes: Int? = null,
    val attention: ChildAttentionState = ChildAttentionState.UNKNOWN,
    val hasData: Boolean = false,
    val generatedAtEpoch: Long? = null,
    val lastSampleAtEpoch: Long? = null,
    val stale: Boolean = false
)

/** `totalMinutes` 为 null 表示 Hub 这一天没有统计，界面显示 `--`，不是 0分钟。 */
data class InternetUsageSummary(
    val totalMinutes: Int?,
    val bars: List<UsageBar>,
    val entries: List<InternetUsageEntry>
)

/** Hub 的 attention.state：`unknown` 与 `none` 绝不混同，前者只能显示数据同步中。 */
enum class ChildAttentionState { NONE, NOTICE, ALERT, UNKNOWN }

data class ParentAttentionEntry(
    val dayLabel: String,
    val date: String,
    val message: String,
    val normal: Boolean,
    val hasData: Boolean = true,
    val windows: List<LateNightWindow> = emptyList(),
    val state: ChildAttentionState = ChildAttentionState.NONE
)

/** 设备总流量取自固件计数器，不是应用分类流量之和；Hub 不发 traffic 块时为 null。 */
data class ChildDeviceUsageReport(
    val todayTxBytes: Long = 0L,
    val todayRxBytes: Long = 0L,
    val todayTotalBytes: Long = 0L,
    val daily: List<DailyUsageItem> = emptyList()
)

data class DailyUsageItem(
    val date: String,
    val txBytes: Long,
    val rxBytes: Long,
    val totalBytes: Long
)

data class ChildInternetDeviceState(
    val summary: ProtectedDeviceSummary,
    val plan: DeviceGuardPlan,
    val plans: List<DeviceGuardPlan> = plan.takeIf { it.configured }?.let(::listOf) ?: emptyList(),
    val runtime: ChildGuardRuntimeState = ChildGuardRuntimeState(deviceId = summary.deviceId),
    val todayUsage: InternetUsageSummary = InternetUsageSummary(null, emptyList(), emptyList()),
    val recentUsage: InternetUsageSummary = InternetUsageSummary(null, emptyList(), emptyList()),
    val usageReport: ChildDeviceUsageReport? = null,
    val usageSource: String = "",
    /** 报告口径的今日统计；null = 本机这一天从没被统计过。 */
    val usage: ChildUsageStats? = null,
    /** 家长请注意：只有服务器逐日返回的记录，缺就是空列表。 */
    val attentionEntries: List<ParentAttentionEntry> = emptyList(),
    /** 总览聚合写入的存在性快照，唯一写入者是 `/child-guard/overview`。 */
    val presence: ChildGuardPresence? = null,
    /** 此刻的计划生效态，同样只由总览聚合写入（Hub 本地算，不碰路由器）。 */
    val schedule: ChildGuardSchedule = ChildGuardSchedule(),
    /** 「最近10天」里选中那一天的逐日详情；日期不符就是还没抓到。 */
    val dayUsage: ChildGuardDayUsage? = null,
    /** A usage refresh is in flight; the page keeps showing cached data meanwhile. */
    val usageLoading: Boolean = false
) {
    /** 报告覆盖的那一天；没有报告就没有日期可谈。 */
    val usageDate: String get() = usage?.date.orEmpty()
    val usageHasData: Boolean get() = usage?.hasData == true
}

data class ChildInternetOverviewState(
    val masterEnabled: Boolean,
    val devices: List<ChildInternetDeviceState>,
    val capabilities: ChildGuardCapabilities = ChildGuardCapabilities(),
    val loading: Boolean = false,
    val error: String = "",
    val pendingDeviceIds: Set<String> = emptySet(),
    /** 进行中的写操作该显示成哪句状态（配置中…/删除中…）；没有写操作时为空。 */
    val pendingHud: String = "",
    /** 后台静默刷新中：页面保留原内容，只多一个「同步中」。 */
    val refreshing: Boolean = false,
    val generatedAtEpoch: Long? = null,
    val lastSampleAtEpoch: Long? = null,
    val stale: Boolean = false
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
    val name: String = "",
    val deviceType: String = "",
    val manufacturer: String = "",
    val online: Boolean = false,
    val connectType: String = ""
) {
    val displayName: String
        get() = name.takeIf { it.isNotBlank() && it != "受守护设备" && it != "LabProbe 设备" }
            ?: hostname.ifBlank { mac }
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
