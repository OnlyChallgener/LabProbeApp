package com.labprobe.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    fun setDeviceBlocked(deviceId: String, blocked: Boolean, onResult: (Result<Unit>) -> Unit = {})
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
        before = { state = state.copy(loading = true, error = "") },
        request = {
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            val caps = api.capabilities()
            val populated = api.devices().map { device ->
                val plans = api.plans(device.summary.deviceId)
                val runtime = api.runtime(device.summary.deviceId)
                val hint = deviceHints.values.firstOrNull { device.summary.matchesChildGuardDevice(it.uid) || device.summary.matchesChildGuardDevice(it.mac) }
                val resolvedIconKey = hint?.iconKey ?: device.summary.iconKey
                // Usage is collected by the relay, aggregated by the Hub and read
                // back here. A missing report must not blank the whole page, so a
                // failure degrades to "no data" for this device only.
                val usage = if (caps.childGuard) {
                    runCatching {
                        api.usageReport(
                            uid = device.summary.deviceId,
                            macs = device.summary.macAddresses,
                            days = USAGE_REPORT_WINDOW_DAYS
                        )
                    }.getOrNull()
                } else null
                device.copy(
                    summary = device.summary.copy(
                        name = device.summary.name.ifBlank { hint?.name.orEmpty() },
                        iconKey = resolvedIconKey,
                        accentArgb = hint?.accentArgb ?: device.summary.accentArgb,
                        status = when {
                            device.summary.status == GuardStatus.BLOCKED || runtime.paused -> GuardStatus.BLOCKED
                            plans.any { it.enabled } -> GuardStatus.GUARDED
                            else -> GuardStatus.UNRESTRICTED
                        },
                        todayMinutes = usage?.today?.totalMinutes ?: 0,
                        hasAttention = (usage?.today?.totalMinutes ?: 0) > 0,
                        appManagementSupported = caps.appManagementSupported,
                        experimentalAppControl = caps.appManagementSupported && isExperimentalAppControlDevice(resolvedIconKey)
                    ),
                    plan = plans.firstOrNull() ?: DeviceGuardPlan(categories = childInternetCatalogCategories()),
                    plans = plans,
                    runtime = runtime,
                    todayUsage = usage?.today ?: InternetUsageSummary(0, empty24HourBars(), emptyList()),
                    recentUsage = usage?.recent ?: InternetUsageSummary(0, emptyList(), emptyList()),
                    usageSource = usage?.source.orEmpty()
                )
            }
            val selectedDeviceShells = deviceHints.values
                .filterNot { hint -> populated.any { it.summary.matchesChildGuardDevice(hint.uid) || it.summary.matchesChildGuardDevice(hint.mac) } }
                .map { it.toDeviceState(caps) }
            ChildInternetOverviewState(
                masterEnabled = populated.flatMap { it.plans }.all { it.enabled },
                devices = populated + selectedDeviceShells, capabilities = caps, loading = false
            )
        },
        success = { state = it },
        failure = { state = state.copy(loading = false, error = it.userMessage()) }
    )

    override fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int) {
        if (deviceId.isBlank()) return
        val uid = childGuardDeviceKey(deviceId)
        val hint = DeviceHint(uid, deviceId, name, iconKey, accentArgb)
        deviceHints[uid] = hint
        // This shell comes from the real device-detail record. It lets an
        // unprotected device create its first child_guard plan without ever
        // falling back to preview/mock repository data.
        if (state.devices.none { it.summary.matchesChildGuardDevice(deviceId) }) {
            state = state.copy(devices = state.devices + hint.toDeviceState(state.capabilities))
        }
        refresh()
    }

    override fun setMasterEnabled(enabled: Boolean) {
        val targets = state.devices.flatMap { device ->
            device.plans.filter { it.id.isNotBlank() }.map { device.summary.deviceId to it.id }
        }
        if (targets.isEmpty()) return
        launch(request = {
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            targets.forEach { (uid, planId) -> api.setPlanEnabled(uid, planId, enabled) }
        }, success = { refresh() }, failure = { state = state.copy(error = it.userMessage()) })
    }

    override fun setDeviceBlocked(deviceId: String, blocked: Boolean, onResult: (Result<Unit>) -> Unit) = launch(
        request = { ChildGuardHubApi(HubApi(prefs), routerId).run { if (blocked) pauseDevice(resolveRouterUid(deviceId)) else resumeDevice(resolveRouterUid(deviceId)) } },
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
    fun pauseDevice(uid: String) = write("$base/devices/${pathPart(uid)}/pause$routerQuery", "POST")
    fun resumeDevice(uid: String) = write("$base/devices/${pathPart(uid)}/resume$routerQuery", "POST")
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
                deviceId = id, name = item.text("name", "displayName", "hostname").ifBlank { id }, iconKey = iconKey,
                accentArgb = item.optInt("accentArgb", 0xFF64748B.toInt()),
                status = if (item.bool("blocked")) GuardStatus.BLOCKED else GuardStatus.UNRESTRICTED,
                todayMinutes = item.optInt("todayMinutes", 0), hasAttention = item.bool("hasAttention"),
                macAddresses = item.stringSet("macs", "mac")
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
            name = item.text("name")
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
private fun Throwable.userMessage() = message?.takeIf(String::isNotBlank) ?: "儿童守护请求失败"

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
    override fun setDeviceBlocked(deviceId: String, blocked: Boolean, onResult: (Result<Unit>) -> Unit) { state = state.copy(devices = state.devices.map { if (it.summary.deviceId == deviceId) it.copy(summary = it.summary.copy(status = if (blocked) GuardStatus.BLOCKED else GuardStatus.GUARDED)) else it }); onResult(Result.success(Unit)) }
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
    val sampleEntries = if (attention) listOf(
        InternetUsageEntry("e1", "抖音系列", "douyin", null, 27, "00:16-00:43", 1),
        InternetUsageEntry("e2", "百度", "baidu", null, 5, "00:50-00:55", 1),
        InternetUsageEntry("e3", "小红书", "rednote", null, 42, "07:27-08:09", 1)
    ) else emptyList()
    val todayBars = if (attention) (0..23).map { hour ->
        UsageBar("${hour}点", when (hour) {
            0 -> 27; 1 -> 12; 2 -> 6; 7 -> 42; 8 -> 18; 13 -> 22; 19 -> 30; 20 -> 14; else -> 0
        })
    } else emptyList()
    val recentBars = if (attention) listOf("周二", "周三", "周四", "周五", "周六", "周日", "周一", "周二", "周三", "今天")
        .mapIndexed { i, label -> UsageBar(label, if (i == 9) minutes else 8 + (i * 13) % 55) }
    else emptyList()
    return ChildInternetDeviceState(
        ProtectedDeviceSummary(id, name, icon, color, if (configured) GuardStatus.GUARDED else GuardStatus.UNRESTRICTED, minutes, attention, true, isExperimentalAppControlDevice(icon)),
        plan,
        plans = if (configured) listOf(plan) else emptyList(),
        todayUsage = InternetUsageSummary(minutes, todayBars, sampleEntries),
        recentUsage = InternetUsageSummary(0, recentBars, sampleEntries),
        attentionEntries = emptyList()
    )
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
internal fun childInternetRdpiIds(name: String): Set<String> = if (name == "微信") setOf("7-1-2-0", "7-1-2-3", "7-1-2-12", "7-1-2-14") else emptySet()

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
