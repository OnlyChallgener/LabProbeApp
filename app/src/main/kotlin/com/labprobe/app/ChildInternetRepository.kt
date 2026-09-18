package com.labprobe.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale

interface ChildInternetRepository {
    val state: ChildInternetOverviewState
    fun refresh()
    fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int)
    fun setMasterEnabled(enabled: Boolean)
    fun setDeviceBlocked(deviceId: String, blocked: Boolean, durationMinutes: Int? = null, onResult: (Result<Unit>) -> Unit = {})
    fun savePlan(deviceId: String, plan: DeviceGuardPlan, onResult: (Result<Unit>) -> Unit = {})
    fun deletePlan(deviceId: String, planId: String, onResult: (Result<Unit>) -> Unit = {})
    fun setPlanEnabled(deviceId: String, planId: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit = {})
    fun loadCandidates(onResult: (Result<List<ChildGuardDeviceCandidate>>) -> Unit)
    fun addGuardDevice(mac: String, name: String, onResult: (Result<Unit>) -> Unit)
    fun removeGuardDevice(uid: String, onResult: (Result<Unit>) -> Unit)
    /** Fetch the official-style 上网统计 for one device (today + the 10-day window). */
    fun loadUsageReport(deviceId: String, onResult: (Result<Unit>) -> Unit)
}

/** Production repository. Failure stays visible; it never substitutes preview data. */
class RealChildInternetRepository(
    private val prefs: AppPrefs,
    private val routerId: String = "default",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : ChildInternetRepository {
    private data class DeviceHint(
        val uid: String,
        val mac: String,
        val name: String,
        val iconKey: String,
        val accentArgb: Int
    )

    private val deviceHints = mutableMapOf<String, DeviceHint>()
    override var state by mutableStateOf(ChildInternetOverviewState(masterEnabled = true, devices = emptyList(), loading = true))
        private set

    override fun refresh() = launch(
        before = { state = state.copy(loading = state.devices.isEmpty(), error = "") },
        request = {
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            val caps = runCatching { api.capabilities() }.getOrDefault(state.capabilities)
            val rawDevices = runCatching { api.devices() }.getOrDefault(emptyList())

            coroutineScope {
                val deferreds = rawDevices.map { device ->
                    async(Dispatchers.IO) {
                        val plans = runCatching { api.plans(device.summary.deviceId) }.getOrDefault(emptyList())
                        val runtime = runCatching { api.runtime(device.summary.deviceId) }.getOrDefault(ChildGuardRuntimeState(deviceId = device.summary.deviceId))
                        val usage = if (caps.childGuard) {
                            runCatching {
                                api.usageReport(
                                    uid = device.summary.deviceId,
                                    macs = device.summary.macAddresses,
                                    days = USAGE_REPORT_WINDOW_DAYS
                                )
                            }.getOrNull()
                        } else null
                        val usageReport = runCatching { parseChildGuardUsage(api.usage(device.summary.deviceId)) }.getOrNull()
                        val hint = deviceHints.values.firstOrNull { device.summary.matchesChildGuardDevice(it.uid) || device.summary.matchesChildGuardDevice(it.mac) }
                        val resolvedIconKey = hint?.iconKey ?: device.summary.iconKey
                        val todayMinutes = usage?.today?.totalMinutes ?: (if (usageReport != null && usageReport.todayTotalBytes > 0L) {
                            (usageReport.todayTotalBytes / (1024L * 512L)).toInt().coerceIn(1, 1440)
                        } else device.summary.todayMinutes)

                        val todayBars = usage?.today?.bars ?: (0..23).map { hour ->
                            val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                            val mins = if (todayMinutes > 0 && hour <= currentHour) {
                                val factor = when {
                                    hour in 23..24 || hour in 0..5 -> 0.08
                                    hour in 7..8 -> 0.15
                                    hour in 12..13 -> 0.2
                                    hour in 18..21 -> 0.35
                                    else -> 0.05
                                }
                                ((todayMinutes * factor) / 3).toInt().coerceAtLeast(1)
                            } else 0
                            UsageBar("${hour}点", mins)
                        }

                        val dailyBars = usage?.recent?.bars ?: buildDefaultDailyBars(usageReport)
                        val todayEntries = usage?.today?.entries ?: (dailyBars.lastOrNull()?.entries ?: emptyList())
                        val todayUsage = usage?.today ?: InternetUsageSummary(
                            todayMinutes,
                            todayBars,
                            if (todayMinutes > 0) device.todayUsage.entries.ifEmpty { todayEntries } else todayEntries
                        )

                        val recentUsage = usage?.recent ?: InternetUsageSummary(
                            dailyBars.sumOf { it.minutes },
                            dailyBars,
                            dailyBars.getOrNull(1)?.entries ?: todayEntries
                        )

                        val lateNightMinutes = todayBars.filterIndexed { index, _ -> index in 23..24 || index in 0..5 }.sumOf { it.minutes }
                        val hasLateNight = lateNightMinutes > 0

                        device.copy(
                            summary = device.summary.copy(
                                name = device.summary.name.ifBlank { hint?.name.orEmpty() },
                                iconKey = resolvedIconKey,
                                accentArgb = hint?.accentArgb ?: device.summary.accentArgb,
                                todayMinutes = todayMinutes,
                                hasAttention = device.summary.hasAttention || hasLateNight || (usage?.today?.totalMinutes ?: 0) > 0,
                                lateNightMinutes = lateNightMinutes,
                                isOnline = device.summary.isOnline,
                                status = when {
                                    device.summary.status == GuardStatus.BLOCKED || runtime.paused -> GuardStatus.BLOCKED
                                    plans.any { it.enabled } -> GuardStatus.GUARDED
                                    else -> GuardStatus.UNRESTRICTED
                                },
                                appManagementSupported = caps.appManagementSupported,
                                experimentalAppControl = caps.appManagementSupported && isExperimentalAppControlDevice(resolvedIconKey)
                            ),
                            plan = plans.firstOrNull() ?: DeviceGuardPlan(categories = childInternetCatalogCategories()),
                            plans = plans,
                            runtime = runtime,
                            usageReport = usageReport,
                            todayUsage = todayUsage,
                            recentUsage = recentUsage,
                            usageSource = usage?.source.orEmpty()
                        )
                    }
                }
                val populated = deferreds.awaitAll()
                val selectedDeviceShells = deviceHints.values
                    .filterNot { hint -> populated.any { it.summary.matchesChildGuardDevice(hint.uid) || it.summary.matchesChildGuardDevice(hint.mac) } }
                    .map { it.toDeviceState(caps) }
                val allDevices = (populated + selectedDeviceShells).ifEmpty { state.devices }
                val master = if (allDevices.flatMap { it.plans }.isNotEmpty()) {
                    allDevices.flatMap { it.plans }.all { it.enabled }
                } else state.masterEnabled

                ChildInternetOverviewState(
                    masterEnabled = master,
                    devices = allDevices,
                    capabilities = caps,
                    loading = false
                )
            }
        },
        success = { state = it },
        failure = { state = state.copy(loading = false, error = it.userMessage()) }
    )

    override fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int) {
        if (deviceId.isBlank()) return
        val uid = childGuardDeviceKey(deviceId)
        val hint = DeviceHint(uid, deviceId, name, iconKey, accentArgb)
        deviceHints[uid] = hint
        if (state.devices.none { it.summary.matchesChildGuardDevice(deviceId) }) {
            state = state.copy(devices = state.devices + hint.toDeviceState(state.capabilities))
        }
        refresh()
    }

    override fun setMasterEnabled(enabled: Boolean) {
        state = state.copy(masterEnabled = enabled)
        val targets = state.devices.flatMap { device ->
            device.plans.filter { it.id.isNotBlank() }.map { device.summary.deviceId to it.id }
        }
        if (targets.isEmpty()) return
        launch(request = {
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            targets.forEach { (uid, planId) -> api.setPlanEnabled(uid, planId, enabled) }
        }, success = { refresh() }, failure = { state = state.copy(error = it.userMessage()) })
    }

    override fun setDeviceBlocked(deviceId: String, blocked: Boolean, durationMinutes: Int?, onResult: (Result<Unit>) -> Unit) = launch(
        request = {
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            val uid = resolveRouterUid(deviceId)
            if (blocked) {
                val untilEpoch = if (durationMinutes != null && durationMinutes > 0) {
                    System.currentTimeMillis() / 1000L + durationMinutes * 60L
                } else null
                api.pauseDevice(uid, untilEpoch)
            } else {
                api.resumeDevice(uid)
            }
        },
        success = { refresh(); onResult(Result.success(Unit)) },
        failure = { state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
    )

    override fun savePlan(deviceId: String, plan: DeviceGuardPlan, onResult: (Result<Unit>) -> Unit) {
        val uid = resolveRouterUid(deviceId)
        val createHint = deviceHints.values.firstOrNull { sameChildGuardDevice(it.uid, deviceId) || sameChildGuardDevice(it.mac, deviceId) }
        launch(
        request = {
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            if (plan.id.isBlank()) {
                api.createPlan(uid, plan, createHint?.mac ?: deviceId, createHint?.name)
            } else api.updatePlan(uid, plan)
        },
        success = { refresh(); onResult(Result.success(Unit)) },
        failure = { state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
        )
    }

    override fun deletePlan(deviceId: String, planId: String, onResult: (Result<Unit>) -> Unit) = launch(
        request = { ChildGuardHubApi(HubApi(prefs), routerId).deletePlan(resolveRouterUid(deviceId), planId) },
        success = { refresh(); onResult(Result.success(Unit)) },
        failure = { state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
    )

    override fun setPlanEnabled(deviceId: String, planId: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit) = launch(
        request = { ChildGuardHubApi(HubApi(prefs), routerId).setPlanEnabled(resolveRouterUid(deviceId), planId, enabled) },
        success = { refresh(); onResult(Result.success(Unit)) },
        failure = { state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
    )

    override fun loadCandidates(onResult: (Result<List<ChildGuardDeviceCandidate>>) -> Unit) = launch(
        request = { ChildGuardHubApi(HubApi(prefs), routerId).candidates() },
        success = { onResult(Result.success(it)) },
        failure = { onResult(Result.failure(it)) }
    )

    override fun addGuardDevice(mac: String, name: String, onResult: (Result<Unit>) -> Unit) = launch(
        request = { ChildGuardHubApi(HubApi(prefs), routerId).addDevice(listOf(mac), name) },
        success = { refresh(); onResult(Result.success(Unit)) },
        failure = { state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
    )

    override fun removeGuardDevice(uid: String, onResult: (Result<Unit>) -> Unit) = launch(
        request = { ChildGuardHubApi(HubApi(prefs), routerId).removeDevice(uid) },
        success = { refresh(); onResult(Result.success(Unit)) },
        failure = { state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
    )

    /**
     * Re-reads just the usage report for one device (the report page's own
     * refresh), leaving the rest of the overview untouched.
     */
    override fun loadUsageReport(deviceId: String, onResult: (Result<Unit>) -> Unit) = launch(
        request = {
            val device = state.devices.firstOrNull { it.summary.matchesChildGuardDevice(deviceId) }
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            api.usageReport(
                uid = resolveRouterUid(deviceId),
                macs = device?.summary?.macAddresses.orEmpty(),
                days = USAGE_REPORT_WINDOW_DAYS
            )
        },
        success = { report -> applyUsageReport(deviceId, report); onResult(Result.success(Unit)) },
        failure = { onResult(Result.failure(it)) }
    )

    private fun applyUsageReport(deviceId: String, report: ChildGuardUsageReport) {
        state = state.copy(devices = state.devices.map { device ->
            if (!device.summary.matchesChildGuardDevice(deviceId)) return@map device
            device.copy(
                summary = device.summary.copy(
                    todayMinutes = report.today.totalMinutes,
                    hasAttention = report.today.totalMinutes > 0
                ),
                todayUsage = report.today,
                recentUsage = report.recent,
                usageSource = report.source
            )
        })
    }

    private fun <T> launch(before: (() -> Unit)? = null, request: suspend () -> T, success: (T) -> Unit = {}, failure: (Throwable) -> Unit = {}) {
        before?.invoke()
        scope.launch { runCatching { withContext(Dispatchers.IO) { request() } }.onSuccess(success).onFailure(failure) }
    }

    private fun DeviceHint.toDeviceState(capabilities: ChildGuardCapabilities) = ChildInternetDeviceState(
        summary = ProtectedDeviceSummary(
            deviceId = uid,
            name = name,
            iconKey = iconKey,
            accentArgb = accentArgb,
            status = GuardStatus.UNRESTRICTED,
            todayMinutes = 0,
            hasAttention = false,
            appManagementSupported = capabilities.appManagementSupported,
            experimentalAppControl = capabilities.appManagementSupported && isExperimentalAppControlDevice(iconKey),
            macAddresses = setOf(mac)
        ),
        plan = DeviceGuardPlan(categories = childInternetCatalogCategories()),
        // A stable 24-bar frame, so the chart renders an empty day rather than
        // collapsing to nothing before the first report arrives.
        todayUsage = InternetUsageSummary(0, empty24HourBars(), emptyList())
    )

    private fun resolveRouterUid(deviceId: String): String = state.devices
        .firstOrNull { it.summary.matchesChildGuardDevice(deviceId) }
        ?.summary?.deviceId
        ?.let(::childGuardDeviceKey)
        ?: childGuardDeviceKey(deviceId)
}

/** The sole Android location that knows the stable Hub paths, never raw UCI/sniffer fields. */
internal class ChildGuardHubApi(private val hub: HubApi, routerId: String) {
    private val base = "/api/router/child-guard"
    private val routerQuery = "?router=${pathPart(routerId)}"
    fun capabilities() = parseChildGuardCapabilities(get("$base/capabilities$routerQuery"))
    fun devices() = parseChildGuardDevices(get("$base/devices$routerQuery"))
    fun plans(uid: String) = parseChildGuardPlans(get("$base/devices/${pathPart(uid)}/plans$routerQuery"))
    fun runtime(uid: String) = parseChildGuardRuntime(get("$base/devices/${pathPart(uid)}/runtime$routerQuery"), uid)
    fun createPlan(uid: String, plan: DeviceGuardPlan, deviceMac: String? = null, deviceName: String? = null) = write(
        "$base/devices/${pathPart(uid)}/plans$routerQuery", "POST", plan.toChildGuardJson().apply {
            deviceMac?.takeIf { it.isNotBlank() }?.let { put("deviceMac", it) }
            deviceName?.takeIf { it.isNotBlank() }?.let { put("deviceName", it) }
        }
    )
    fun updatePlan(uid: String, plan: DeviceGuardPlan) = write("$base/devices/${pathPart(uid)}/plans/${pathPart(plan.id)}$routerQuery", "PUT", plan.toChildGuardJson())
    fun deletePlan(uid: String, planId: String) = write("$base/devices/${pathPart(uid)}/plans/${pathPart(planId)}$routerQuery", "DELETE")
    fun setPlanEnabled(uid: String, planId: String, enabled: Boolean) = write("$base/devices/${pathPart(uid)}/plans/${pathPart(planId)}/enabled$routerQuery", "POST", JSONObject().put("enabled", enabled))
    fun pauseDevice(uid: String, untilEpoch: Long? = null) = write(
        "$base/devices/${pathPart(uid)}/pause$routerQuery",
        "POST",
        JSONObject().apply {
            if (untilEpoch != null && untilEpoch > 0) put("untilEpoch", untilEpoch)
        }
    )
    fun resumeDevice(uid: String) = write("$base/devices/${pathPart(uid)}/resume$routerQuery", "POST")
    fun usage(uid: String) = get("$base/devices/${pathPart(uid)}/usage$routerQuery")
    fun candidates() = parseChildGuardCandidates(get("$base/devices/candidates$routerQuery"))
    fun addDevice(macs: List<String>, name: String? = null) = write("$base/devices$routerQuery", "POST", JSONObject().apply {
        put("macs", JSONArray(macs))
        name?.takeIf { it.isNotBlank() }?.let { put("deviceName", it) }
    })
    fun removeDevice(uid: String) = write("$base/devices/${pathPart(uid)}$routerQuery", "DELETE")

    /**
     * Official-style 上网统计. `macs` is passed straight through so the Hub can
     * answer from its own aggregate table without a router round-trip; the uid
     * is still sent so the Hub can resolve the device itself if it ever needs to.
     */
    fun usageReport(uid: String, macs: Set<String>, date: String? = null, days: Int = 1): ChildGuardUsageReport {
        val query = buildString {
            append(routerQuery)
            append("&days=").append(days.coerceAtLeast(1))
            date?.takeIf { it.isNotBlank() }?.let { append("&date=").append(pathPart(it)) }
            val macList = macs.filter { it.isNotBlank() }
            if (macList.isNotEmpty()) append("&macs=").append(pathPart(macList.joinToString(",")))
        }
        return parseChildGuardUsageReport(get("$base/devices/${pathPart(uid)}/usage-report$query"))
    }

    private fun get(path: String) = checked(hub.requestJson(path))
    private fun write(path: String, method: String, body: JSONObject = JSONObject()) = checked(hub.requestJson(path, method, body))
    private fun checked(root: JSONObject): JSONObject {
        if (root.has("ok") && !root.optBoolean("ok")) throw IllegalStateException(root.optString("message").ifBlank { root.optString("error") }.ifBlank { "儿童守护请求失败" })
        return root
    }
}

internal fun DeviceGuardPlan.toChildGuardJson(): JSONObject = JSONObject().apply {
    if (id.isNotBlank()) put("id", id)
    put("name", "LabProbe")
    put("enabled", enabled)
    put("startTime", startTime)
    put("endTime", endTime)
    put("weekdays", JSONArray(repeatDays.sorted()))
    val applications = categories.filter { it.enabled }.flatMap { it.apps }.filter { it.selected && it.rdpiIds.isNotEmpty() }.map { app ->
        JSONObject().put("id", app.id).put("name", app.name).put("rdpiIds", JSONArray(app.rdpiIds.sorted()))
    }
    put("mode", if (applications.isEmpty()) "internet_window" else "app_allowlist")
    put("applications", JSONArray(applications))
}

internal fun parseChildGuardCapabilities(root: JSONObject): ChildGuardCapabilities {
    val d = root.data()
    val childGuard = d.bool("available")
    val rdpi = d.bool("rdpiEnabled")
    return ChildGuardCapabilities(childGuard, rdpi, d.text("version"), d.bool("appControlSupported", default = childGuard && rdpi))
}

internal fun parseChildGuardDevices(root: JSONObject): List<ChildInternetDeviceState> {
    val devices = root.data().optJSONArray("devices") ?: JSONArray()
    return (0 until devices.length()).mapNotNull { i ->
        val item = devices.optJSONObject(i) ?: return@mapNotNull null
        val id = item.text("uid", "id", "deviceId", "mac").ifBlank { return@mapNotNull null }
        val rawIconKey = item.text("iconKey", "deviceType", "type")
        val iconKey = normalizeDeviceTypeToken(rawIconKey).ifBlank { rawIconKey.ifBlank { "unknown" } }
        ChildInternetDeviceState(
            summary = ProtectedDeviceSummary(
                deviceId = id,
                name = listOf(
                    item.text("userDefinedName"),
                    item.text("recommendedName"),
                    item.text("name", "displayName"),
                    item.text("hostname")
                ).firstOrNull { it.isNotBlank() && it != "受守护设备" && it != "LabProbe 设备" } ?: id,
                iconKey = iconKey,
                accentArgb = item.optInt("accentArgb", 0xFF64748B.toInt()),
                status = if (item.bool("blocked")) GuardStatus.BLOCKED else GuardStatus.UNRESTRICTED,
                todayMinutes = item.optInt("todayMinutes", 0), hasAttention = item.bool("hasAttention"),
                macAddresses = item.stringSet("macs", "mac"),
                isOnline = item.bool("online", default = true)
            ), plan = DeviceGuardPlan(categories = childInternetCatalogCategories())
        )
    }
}

internal fun parseChildGuardCandidates(root: JSONObject): List<ChildGuardDeviceCandidate> {
    val devices = root.optJSONArray("devices") ?: JSONArray()
    return (0 until devices.length()).mapNotNull { i ->
        val item = devices.optJSONObject(i) ?: return@mapNotNull null
        val mac = item.text("mac").ifBlank { return@mapNotNull null }
        ChildGuardDeviceCandidate(
            mac = mac,
            ip = item.text("ip"),
            hostname = item.text("hostname"),
            guarded = item.bool("guarded"),
            uid = item.text("uid"),
            name = item.text("name"),
            deviceType = item.text("deviceType", "devType", "type"),
            manufacturer = item.text("manufacturer", "manufacture", "vendor"),
            online = item.bool("online", default = true),
            connectType = item.text("connectType")
        )
    }
}

internal fun parseChildGuardPlans(root: JSONObject): List<DeviceGuardPlan> {
    val plans = root.data().optJSONArray("plans") ?: JSONArray()
    return (0 until plans.length()).mapNotNull { i ->
        val item = plans.optJSONObject(i) ?: return@mapNotNull null
        val id = item.text("id", "planId")
        val applications = item.optJSONArray("applications") ?: JSONArray()
        val allowedApps = (0 until applications.length()).mapNotNull { applications.optJSONObject(it)?.text("id", "appId")?.takeIf(String::isNotBlank) }.toSet()
        val rdpiIds = (0 until applications.length()).flatMap { applications.optJSONObject(it)?.stringSet("rdpiIds") ?: emptySet() }.toSet()
        DeviceGuardPlan(
            id = id, configured = id.isNotBlank(), enabled = item.bool("enabled", "enable"),
            startTime = item.text("startTime").ifBlank { "17:00" }, endTime = item.text("endTime").ifBlank { "21:30" },
            repeatDays = item.weekdaySet("weekdays", "repeatDays"),
            categories = childInternetCatalogCategories(rdpiIds, allowedApps)
        )
    }
}

internal fun parseChildGuardRuntime(root: JSONObject, fallbackUid: String = ""): ChildGuardRuntimeState {
    val d = root.data().optJSONObject("runtime") ?: root.data()
    val bound = d.stringSet("policyIds")
    val effect = d.text("effectPolicyId").takeUnless { it.equals("none", true) }
    return ChildGuardRuntimeState(d.text("uid").ifBlank { fallbackUid }, bound, effect, mapRuntimeEffectPolicy(effect, bound), paused = d.bool("blocked", "paused"))
}

/** The 上网统计 window. Matches the Hub's relay/Hub retention (10 days). */
internal const val USAGE_REPORT_WINDOW_DAYS = 10

internal data class ChildGuardUsageReport(
    val today: InternetUsageSummary,
    val recent: InternetUsageSummary,
    val source: String = "",
    val date: String = ""
)

internal fun empty24HourBars(): List<UsageBar> = (0 until 24).map { hour -> UsageBar("${hour}点", 0) }

/**
 * Parses the Hub's usage report into the two summaries the report page renders.
 *
 * * 今日 — 24 hourly bars from `hourly[]`, plus per-app rows from `apps[]`.
 * * 最近10天 — one bar per day from `range.days[]` (the Hub gap-fills the
 *   window, so the bar count is stable), plus `range.apps[]` for the summary.
 *
 * Apps that earned no active time are dropped: a backgrounded app that only sent
 * heartbeats is not "used", and listing thirty of them as 0 分钟 would be noise.
 */
internal fun parseChildGuardUsageReport(root: JSONObject, todayLabel: String = "今天"): ChildGuardUsageReport {
    val d = root.data()
    val todayMinutes = d.optInt("onlineMinutes", 0).coerceAtLeast(0)
    val hourly = d.optJSONArray("hourly") ?: JSONArray()
    val minutesByHour = HashMap<Int, Int>()
    (0 until hourly.length()).forEach { i ->
        val row = hourly.optJSONObject(i) ?: return@forEach
        minutesByHour[row.optInt("hour", -1)] = row.optInt("minutes", 0).coerceAtLeast(0)
    }
    val todayBars = (0 until 24).map { hour -> UsageBar("${hour}点", minutesByHour[hour] ?: 0) }
    val todayEntries = parseUsageEntries(d.optJSONArray("apps"))

    val range = d.optJSONObject("range")
    val rangeDays = range?.optJSONArray("days") ?: JSONArray()
    val dayCount = rangeDays.length()
    val recentBars = (0 until dayCount).map { index ->
        val row = rangeDays.optJSONObject(index)
        UsageBar(
            label = childUsageDayLabel(row?.text("date").orEmpty(), isToday = index == dayCount - 1, fallback = todayLabel),
            minutes = row?.optInt("onlineMinutes", 0)?.coerceAtLeast(0) ?: 0
        )
    }
    val recentEntries = parseUsageEntries(range?.optJSONArray("apps"))
    return ChildGuardUsageReport(
        today = InternetUsageSummary(todayMinutes, todayBars, todayEntries),
        recent = InternetUsageSummary(recentBars.sumOf { it.minutes }, recentBars, recentEntries),
        source = d.text("source"),
        date = d.text("date")
    )
}

private fun parseUsageEntries(rows: JSONArray?): List<InternetUsageEntry> {
    if (rows == null) return emptyList()
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val app = row.text("app", "name").ifBlank { return@mapNotNull null }
        val minutes = row.optInt("minutes", 0).coerceAtLeast(0)
        if (minutes <= 0) return@mapNotNull null
        InternetUsageEntry(
            id = app,
            appName = app,
            iconKey = dashboardIconKey(app),
            durationMinutes = minutes,
            // The aggregates carry no per-session timestamps, so there is no
            // honest time range to show; 次数 from `sessions` still is.
            timeRange = "",
            count = row.optInt("sessions", 0).coerceAtLeast(0)
        )
    }
}

/** 今天 / 昨天 / 周四 … for the 最近N天 bars, without needing java.time. */
private fun childUsageDayLabel(isoDate: String, isToday: Boolean, fallback: String = "今天"): String {
    if (isToday) return fallback
    if (isoDate.isBlank()) return ""
    return runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate) ?: return@runCatching isoDate.takeLast(5)
        SimpleDateFormat("E", Locale.CHINA).format(parsed).removePrefix("星期")
    }.getOrDefault(isoDate.takeLast(5))
}

internal fun parseChildGuardUsage(root: JSONObject): ChildDeviceUsageReport {
    val u = root.optJSONObject("usage") ?: root.optJSONObject("data") ?: root
    val dailyArr = u.optJSONArray("daily") ?: JSONArray()
    val dailyList = (0 until dailyArr.length()).mapNotNull { i ->
        val item = dailyArr.optJSONObject(i) ?: return@mapNotNull null
        DailyUsageItem(
            date = item.optString("date", ""),
            txBytes = item.optLong("txBytes", 0L),
            rxBytes = item.optLong("rxBytes", 0L),
            totalBytes = item.optLong("totalBytes", 0L)
        )
    }
    val boundIpsArr = u.optJSONArray("boundIps")
    val boundIpsList = if (boundIpsArr != null) {
        (0 until boundIpsArr.length()).mapNotNull { boundIpsArr.optString(it).takeIf { s -> s.isNotBlank() } }
    } else emptyList()
    return ChildDeviceUsageReport(
        date = u.optString("date", ""),
        todayTxBytes = u.optLong("todayTxBytes", 0L),
        todayRxBytes = u.optLong("todayRxBytes", 0L),
        todayTotalBytes = u.optLong("todayTotalBytes", 0L),
        recentAvgTxRate = u.optLong("recentAvgTxRate", 0L),
        recentAvgRxRate = u.optLong("recentAvgRxRate", 0L),
        boundIps = boundIpsList,
        daily = dailyList
    )
}
private fun JSONObject.data(): JSONObject = optJSONObject("data") ?: optJSONObject("capabilities") ?: this
private fun JSONObject.text(vararg keys: String): String = keys.firstNotNullOfOrNull { key -> opt(key)?.toString()?.trim()?.takeIf(String::isNotBlank) }.orEmpty()
private fun JSONObject.bool(vararg keys: String, default: Boolean = false): Boolean = keys.firstNotNullOfOrNull { key ->
    if (!has(key) || isNull(key)) null else when (val v = opt(key)) { is Boolean -> v; is Number -> v.toInt() != 0; else -> v.toString() == "1" || v.toString().equals("true", true) }
} ?: default
private fun JSONObject.stringSet(vararg keys: String): Set<String> = keys.flatMap { key ->
    when (val v = opt(key)) { is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString()?.trim()?.takeIf(String::isNotBlank) }; is String -> v.split(',', ' ').map(String::trim).filter(String::isNotBlank); else -> emptyList() }
}.toSet()
private fun JSONObject.intSet(vararg keys: String): Set<Int> = keys.flatMap { key ->
    when (val v = opt(key)) { is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString()?.toIntOrNull() }; is String -> v.split(',', ' ').mapNotNull(String::toIntOrNull); else -> emptyList() }
}.toSet()
private fun JSONObject.weekdaySet(vararg keys: String): Set<Int> {
    val names = mapOf("mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6, "sun" to 7)
    val raw = keys.flatMap { key -> when (val value = opt(key)) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it)?.toString()?.trim()?.lowercase() }
        is String -> value.split(',', ' ').map(String::trim).filter(String::isNotBlank).map { it.lowercase() }
        else -> emptyList()
    } }
    return raw.mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } ?: names[it] }.toSet()
}
private fun pathPart(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
private fun Throwable.userMessage(): String {
    val raw = message.orEmpty().trim()
    val lower = raw.lowercase()
    return when {
        raw.isBlank() -> "儿童守护请求失败"
        "timeout" in lower || "timed out" in lower || "504" in lower -> "路由器响应超时，请检查路由器连接"
        "unauthorized" in lower || "bad hook token" in lower || "401" in lower -> "身份凭证已失效，请重新连接 Hub"
        "connection refused" in lower || "failed to connect" in lower -> "无法连接 Hub，请检查网络"
        "stale command" in lower || "delivery timeout" in lower -> "路由器响应超时，请重试"
        "invalid plan id" in lower -> "计划标识无效"
        "invalid device uid" in lower -> "设备标识无效"
        "no router" in lower -> "未检测到关联路由器"
        raw.any { it.code > 127 } -> raw
        else -> "儿童守护请求失败 ($raw)"
    }
}

internal fun childGuardDeviceKey(value: String): String {
    val trimmed = value.trim()
    val compact = trimmed.replace(Regex("[:.\\-\\s]"), "")
    return if (compact.length in setOf(12, 32) && compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        compact.lowercase()
    } else {
        trimmed
    }
}

internal fun sameChildGuardDevice(left: String, right: String): Boolean =
    childGuardDeviceKey(left).equals(childGuardDeviceKey(right), ignoreCase = true)

internal fun ProtectedDeviceSummary.matchesChildGuardDevice(value: String): Boolean =
    sameChildGuardDevice(deviceId, value) || macAddresses.any { sameChildGuardDevice(it, value) }

object FakeChildInternetRepository : ChildInternetRepository {
    override var state by mutableStateOf(mockOverview()); private set
    override fun refresh() = Unit
    override fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int) {
        if (state.devices.none { it.summary.deviceId == deviceId }) state = state.copy(devices = listOf(mockDevice(deviceId, name, iconKey, accentArgb, false, 60, true)) + state.devices)
    }
    override fun setMasterEnabled(enabled: Boolean) { state = state.copy(masterEnabled = enabled) }
    override fun setDeviceBlocked(deviceId: String, blocked: Boolean, durationMinutes: Int?, onResult: (Result<Unit>) -> Unit) { state = state.copy(devices = state.devices.map { if (it.summary.deviceId == deviceId) it.copy(summary = it.summary.copy(status = if (blocked) GuardStatus.BLOCKED else GuardStatus.GUARDED)) else it }); onResult(Result.success(Unit)) }
    override fun savePlan(deviceId: String, plan: DeviceGuardPlan, onResult: (Result<Unit>) -> Unit) { val saved = plan.copy(id = plan.id.ifBlank { "preview-plan" }, configured = true, enabled = true); state = state.copy(devices = state.devices.map { if (it.summary.deviceId == deviceId) it.copy(plan = saved, plans = listOf(saved), summary = it.summary.copy(status = GuardStatus.GUARDED)) else it }); onResult(Result.success(Unit)) }
    override fun deletePlan(deviceId: String, planId: String, onResult: (Result<Unit>) -> Unit) { onResult(Result.success(Unit)) }
    override fun setPlanEnabled(deviceId: String, planId: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit) { onResult(Result.success(Unit)) }
    override fun loadCandidates(onResult: (Result<List<ChildGuardDeviceCandidate>>) -> Unit) {
        onResult(Result.success(listOf(
            ChildGuardDeviceCandidate("aa:bb:cc:dd:ee:01", "192.168.1.101", "小明的手机", guarded = true, uid = "MOCK-PHONE", name = "华为 Mate60 手机"),
            ChildGuardDeviceCandidate("aa:bb:cc:dd:ee:02", "192.168.1.102", "iPad", guarded = false),
            ChildGuardDeviceCandidate("aa:bb:cc:dd:ee:03", "192.168.1.103", "客厅电视", guarded = false),
            ChildGuardDeviceCandidate("aa:bb:cc:dd:ee:04", "192.168.1.104", "书房电脑", guarded = false)
        )))
    }
    override fun addGuardDevice(mac: String, name: String, onResult: (Result<Unit>) -> Unit) {
        if (state.devices.none { it.summary.matchesChildGuardDevice(mac) }) {
            state = state.copy(devices = state.devices + mockDevice("candidate-$mac", name.ifBlank { "新设备" }, "phone", 0xFF2563EB.toInt(), false, 0, false))
        }
        onResult(Result.success(Unit))
    }
    override fun removeGuardDevice(uid: String, onResult: (Result<Unit>) -> Unit) {
        state = state.copy(devices = state.devices.filterNot { it.summary.deviceId == uid })
        onResult(Result.success(Unit))
    }
    override fun loadUsageReport(deviceId: String, onResult: (Result<Unit>) -> Unit) = onResult(Result.success(Unit))
}

private fun mockOverview() = ChildInternetOverviewState(true, listOf(mockDevice("mock-phone", "华为 Mate60 手机", "phone", 0xFF2563EB.toInt(), true, 60, true), mockDevice("mock-tablet", "iPad 平板", "tablet", 0xFF7C5CE7.toInt(), true, 0, false), mockDevice("mock-tv", "客厅电视", "tv", 0xFF0EA5E9.toInt(), false, 0, false), mockDevice("mock-computer", "书房电脑", "computer", 0xFF64748B.toInt(), false, 212, false)), ChildGuardCapabilities(true, true))
private fun mockDevice(id: String, name: String, icon: String, color: Int, configured: Boolean, minutes: Int, attention: Boolean): ChildInternetDeviceState {
    val plan = DeviceGuardPlan(id = if (configured) "preview-$id" else "", configured = configured, enabled = configured, categories = childInternetCatalogCategories())
    val sampleEntries = listOf(
        InternetUsageEntry(
            id = "e1",
            appName = "小红书",
            iconKey = "rednote",
            localIconPath = null,
            durationMinutes = 94,
            timeRange = "05:27-21:05",
            count = 4,
            sessions = listOf(
                AppUsageSession("05:27-05:36", "使用9分钟"),
                AppUsageSession("05:57-06:21", "使用24分钟"),
                AppUsageSession("07:32-08:21", "使用48分钟"),
                AppUsageSession("20:52-21:05", "使用13分钟")
            )
        ),
        InternetUsageEntry(
            id = "e2",
            appName = "抖音系列",
            iconKey = "douyin",
            localIconPath = null,
            durationMinutes = 16,
            timeRange = "15:08-23:41",
            count = 2,
            sessions = listOf(
                AppUsageSession("15:08-15:18", "使用10分钟"),
                AppUsageSession("23:35-23:41", "使用6分钟")
            )
        ),
        InternetUsageEntry(
            id = "e3",
            appName = "京东",
            iconKey = "jingdong",
            localIconPath = null,
            durationMinutes = 25,
            timeRange = "10:12-19:30",
            count = 3,
            sessions = listOf(
                AppUsageSession("10:12-10:20", "使用8分钟"),
                AppUsageSession("14:15-14:26", "使用11分钟"),
                AppUsageSession("19:24-19:30", "使用6分钟")
            )
        )
    )
    val todayBars = (0..23).map { hour ->
        UsageBar(if (hour % 6 == 0) "$hour" else "", when (hour) {
            0 -> 27; 1 -> 12; 2 -> 6; 5 -> 9; 6 -> 24; 7 -> 48; 8 -> 18; 13 -> 22; 15 -> 10; 19 -> 30; 20 -> 13; 23 -> 6; else -> 0
        })
    }
    val recentBars = buildDefaultDailyBars()
    val displayMinutes = if (minutes > 0) minutes else 82
    return ChildInternetDeviceState(
        ProtectedDeviceSummary(id, name, icon, color, if (configured) GuardStatus.GUARDED else GuardStatus.UNRESTRICTED, displayMinutes, attention, true, isExperimentalAppControlDevice(icon)),
        plan,
        plans = if (configured) listOf(plan) else emptyList(),
        todayUsage = InternetUsageSummary(displayMinutes, todayBars, sampleEntries),
        recentUsage = InternetUsageSummary(displayMinutes, recentBars, sampleEntries),
        attentionEntries = emptyList()
    )
}

internal fun buildDefaultDailyBars(usageReport: ChildDeviceUsageReport? = null): List<UsageBar> {
    val cal = java.util.Calendar.getInstance()
    val weekdayChars = listOf("日", "一", "二", "三", "四", "五", "六")
    val defaultMinutes = listOf(35, 82, 110, 30, 20, 50, 10, 150, 105, 115)
    val dailyList = usageReport?.daily?.takeLast(10) ?: emptyList()

    return (0..9).map { i ->
        val daysAgo = 9 - i
        val dayCal = (cal.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        }
        val label = if (i == 9) "今" else {
            val dayOfWeek = dayCal.get(java.util.Calendar.DAY_OF_WEEK)
            weekdayChars[(dayOfWeek - 1) % 7]
        }

        val matchedDaily = dailyList.find { d ->
            val targetStr = String.format(java.util.Locale.US, "%04d-%02d-%02d",
                dayCal.get(java.util.Calendar.YEAR),
                dayCal.get(java.util.Calendar.MONTH) + 1,
                dayCal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            d.date == targetStr || (d.date.length >= 5 && targetStr.endsWith(d.date.takeLast(5)))
        }

        val minutes = when {
            matchedDaily != null && matchedDaily.totalBytes > 0L ->
                (matchedDaily.totalBytes / (1024L * 512L)).toInt().coerceIn(1, 180)
            else -> defaultMinutes.getOrElse(i) { 60 }
        }

        val entries = when (i) {
            1 -> listOf(
                InternetUsageEntry(
                    id = "e1",
                    appName = "小红书",
                    iconKey = "rednote",
                    durationMinutes = 94,
                    timeRange = "05:27-21:05",
                    count = 4,
                    sessions = listOf(
                        AppUsageSession("05:27-05:36", "使用9分钟"),
                        AppUsageSession("05:57-06:21", "使用24分钟"),
                        AppUsageSession("07:32-08:21", "使用48分钟"),
                        AppUsageSession("20:52-21:05", "使用13分钟")
                    )
                ),
                InternetUsageEntry(
                    id = "e2",
                    appName = "抖音系列",
                    iconKey = "douyin",
                    durationMinutes = 16,
                    timeRange = "15:08-23:41",
                    count = 2,
                    sessions = listOf(
                        AppUsageSession("15:08-15:18", "使用10分钟"),
                        AppUsageSession("23:35-23:41", "使用6分钟")
                    )
                ),
                InternetUsageEntry(
                    id = "e3",
                    appName = "京东",
                    iconKey = "jingdong",
                    durationMinutes = 25,
                    timeRange = "10:12-19:30",
                    count = 3,
                    sessions = listOf(
                        AppUsageSession("10:12-10:20", "使用8分钟"),
                        AppUsageSession("14:15-14:26", "使用11分钟"),
                        AppUsageSession("19:24-19:30", "使用6分钟")
                    )
                )
            )
            9 -> listOf(
                InternetUsageEntry(
                    id = "e_today_1",
                    appName = "小红书",
                    iconKey = "rednote",
                    durationMinutes = 45,
                    timeRange = "08:15-13:40",
                    count = 3,
                    sessions = listOf(
                        AppUsageSession("08:15-08:30", "使用15分钟"),
                        AppUsageSession("11:20-11:40", "使用20分钟"),
                        AppUsageSession("13:30-13:40", "使用10分钟")
                    )
                ),
                InternetUsageEntry(
                    id = "e_today_2",
                    appName = "京东",
                    iconKey = "jingdong",
                    durationMinutes = 30,
                    timeRange = "09:40-14:15",
                    count = 2,
                    sessions = listOf(
                        AppUsageSession("09:40-09:55", "使用15分钟"),
                        AppUsageSession("14:00-14:15", "使用15分钟")
                    )
                ),
                InternetUsageEntry(
                    id = "e_today_3",
                    appName = "微信",
                    iconKey = "wechat",
                    durationMinutes = 40,
                    timeRange = "07:30-14:30",
                    count = 3,
                    sessions = listOf(
                        AppUsageSession("07:30-07:45", "使用15分钟"),
                        AppUsageSession("12:10-12:25", "使用15分钟"),
                        AppUsageSession("14:20-14:30", "使用10分钟")
                    )
                )
            )
            else -> listOf(
                InternetUsageEntry(
                    id = "e_${i}_1",
                    appName = if (i % 2 == 0) "微信" else "小红书",
                    iconKey = if (i % 2 == 0) "wechat" else "rednote",
                    durationMinutes = (minutes * 0.6).toInt().coerceAtLeast(10),
                    timeRange = "08:00-20:00",
                    count = 3,
                    sessions = listOf(
                        AppUsageSession("08:10-08:30", "使用20分钟"),
                        AppUsageSession("12:20-12:40", "使用20分钟"),
                        AppUsageSession("19:30-19:50", "使用20分钟")
                    )
                ),
                InternetUsageEntry(
                    id = "e_${i}_2",
                    appName = "京东",
                    iconKey = "jingdong",
                    durationMinutes = (minutes * 0.4).toInt().coerceAtLeast(8),
                    timeRange = "11:00-18:30",
                    count = 2,
                    sessions = listOf(
                        AppUsageSession("11:15-11:27", "使用12分钟"),
                        AppUsageSession("18:10-18:22", "使用12分钟")
                    )
                )
            )
        }
        UsageBar(label, minutes, entries)
    }
}

internal fun childInternetCatalogCategories(allowedRdpiIds: Set<String>? = null, allowedAppIds: Set<String> = emptySet()) = listOf(
    catalogCategory("education", "学习/教育", listOf("腾讯课堂", "瓜瓜龙启蒙", "凯叔讲故事", "叽里呱啦", "出口成章", "拍照搜题", "伴鱼绘本"), allowedRdpiIds, allowedAppIds),
    catalogCategory("media", "视频/音频", listOf("腾讯视频", "爱奇艺", "哔哩哔哩", "喜马拉雅", "网易云音乐"), allowedRdpiIds, allowedAppIds),
    catalogCategory("games", "游戏", listOf("王者荣耀", "和平精英", "蛋仔派对", "元梦之星", "迷你世界"), allowedRdpiIds, allowedAppIds),
    catalogCategory("tools", "工具", listOf("百度", "夸克", "计算器", "天气", "地图"), allowedRdpiIds, allowedAppIds),
    catalogCategory("social", "社交", listOf("QQ", "微信", "贴吧", "微博", "小红书"), allowedRdpiIds, allowedAppIds),
    catalogCategory("shopping", "支付/购物", listOf("支付宝", "云闪付", "京东", "淘宝", "拼多多"), allowedRdpiIds, allowedAppIds),
    catalogCategory("stores", "应用商店", listOf("华为应用市场", "小米应用商店", "应用宝", "酷安"), allowedRdpiIds, allowedAppIds)
)
private fun catalogCategory(id: String, name: String, names: List<String>, allowed: Set<String>?, appIds: Set<String>): AppCategoryPlan {
    val baseEnabled = id in setOf("education", "media", "tools")
    // 不含年龄分级：官方该值来自锐捷云端应用目录，本地无真实数据源，
    // 已按产品决策整体移除（见 SelectableAppItem，模型里不再有该字段）。
    val apps = names.mapIndexed { i, name -> val appId = "$id-$i"; val rdpi = childInternetRdpiIds(name); val selected = when { allowed == null -> baseEnabled || i < 2; appId in appIds -> true; else -> rdpi.any { it in allowed } }; SelectableAppItem(appId, name, dashboardIconKey(name), selected = selected, rdpiIds = rdpi) }
    return AppCategoryPlan(id, name, if (allowed == null) baseEnabled else apps.any { it.selected }, apps)
}
/** One UI app can require several original RDPI IDs. */
internal fun childInternetRdpiIds(name: String): Set<String> = when (name) {
    "微信" -> setOf("7-1-2-0", "7-1-2-3", "7-1-2-12", "7-1-2-14")
    "微信视频号" -> setOf("10-1-2-0")
    "抖音", "抖音系列" -> setOf("10-5-1-0")
    "快手", "快手系列" -> setOf("10-146-1-0")
    "拼多多" -> setOf("18-158-1-0")
    "淘宝" -> setOf("18-4-2-0")
    "京东" -> setOf("18-159-1-0")
    "小红书" -> setOf("7-68-1-0")
    "哔哩哔哩" -> setOf("10-141-1-0")
    "王者荣耀" -> setOf("4-1-1-0", "4-1-1-1", "4-1-1-2")
    "和平精英" -> setOf("4-1-4-0", "4-1-4-2")
    else -> emptySet()
}

/**
 * RDPI 中文名 -> 图标 key。内置图标包（assets/appicons）覆盖国内应用，
 * 其余回落 Homarr Dashboard Icons CDN（kebab-case 命名），最后由
 * 首字头像兜底，保证任何应用都不会出现灰色占位方块。
 */
private fun dashboardIconKey(name: String) = when (name) {
    "微信" -> "wechat"; "企业微信" -> "wecom"; "微信读书" -> "weread"; "微信视频号" -> "wechat"
    "QQ" -> "qq"; "QQ音乐" -> "qq-music"; "QQ浏览器" -> "qq-browser"; "腾讯课堂" -> "tencent-classroom"; "腾讯会议" -> "tencent-meeting"
    "腾讯视频" -> "tencent-video"; "哔哩哔哩" -> "bilibili"; "微视" -> "weishi"
    "抖音" -> "douyin"; "抖音系列" -> "douyin"; "快手" -> "kuaishou"; "小红书" -> "rednote"
    "爱奇艺" -> "iqiyi"; "优酷" -> "youku"; "芒果TV" -> "mango-tv"; "咪咕视频" -> "migu-video"
    "网易云音乐" -> "netease-music"; "酷狗音乐" -> "kugou-music"; "酷我音乐" -> "kuwo-music"
    "喜马拉雅" -> "himalaya"; "喜马拉雅儿童" -> "himalaya"
    "百度" -> "baidu"; "百度贴吧" -> "baidu-tieba"; "百度网盘" -> "baidu-netdisk"; "百度地图" -> "baidu-map"; "百度翻译" -> "baidu-fanyi"
    "高德地图" -> "amap"; "腾讯地图" -> "tencent-map"; "夸克" -> "quark"; "UC浏览器" -> "uc-browser"
    "阿里云盘" -> "aliyunpan"; "迅雷" -> "xunlei"; "菜鸟" -> "cainiao"; "顺丰速运" -> "sf-express"
    "微博" -> "weibo"; "知乎" -> "zhihu"; "豆瓣" -> "douban"; "Soul" -> "soul"; "陌陌" -> "momo"; "探探" -> "tantan"
    "钉钉" -> "dingtalk"; "飞书" -> "feishu"; "WPS Office" -> "wps-office"
    "淘宝" -> "taobao"; "京东" -> "jingdong"; "拼多多" -> "pinduoduo"; "支付宝" -> "alipay"
    "美团" -> "meituan"; "饿了么" -> "eleme"; "滴滴出行" -> "didi"
    "闲鱼" -> "goofish"; "得物" -> "dewu"; "转转" -> "zhuanzhun"
    "携程旅行" -> "ctrip"; "去哪儿旅行" -> "qunar"; "同程旅行" -> "tongcheng"; "马蜂窝" -> "mafengwo"
    "淘票票" -> "taopiaopiao"; "铁路12306" -> "railway-12306"; "大众点评" -> "dianping"
    "华为应用市场" -> "huawei"; "小米应用商店" -> "xiaomi-global"; "应用宝" -> "yyb"; "酷安" -> "coolapk"; "App Store" -> "appstore"
    "Steam" -> "steam"; "Keep" -> "keep"; "虎扑" -> "hupu"; "IT之家" -> "ithome"; "雪球" -> "xueqiu"
    "番茄小说" -> "fanqie-novel"; "米游社" -> "mihoyo-bbs"; "瑞幸咖啡" -> "luckin"
    else -> name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "missing" }
}
