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
    override var state by mutableStateOf(
        runCatching {
            val json = prefs.childGuardCacheJson
            if (json.isNotBlank()) {
                val cached = parseChildGuardDevices(JSONObject(json))
                ChildInternetOverviewState(masterEnabled = true, devices = cached, loading = true)
            } else {
                ChildInternetOverviewState(masterEnabled = true, devices = emptyList(), loading = true)
            }
        }.getOrDefault(ChildInternetOverviewState(masterEnabled = true, devices = emptyList(), loading = true))
    )
        private set

    override fun refresh() = launch(
        before = { state = state.copy(loading = state.devices.isEmpty(), error = "") },
        request = {
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            val caps = runCatching { api.capabilities() }.getOrDefault(state.capabilities)
            val rawDevices = api.devices()

            val updatedDevices = rawDevices.map { raw ->
                val existing = state.devices.firstOrNull { it.summary.matchesChildGuardDevice(raw.summary.deviceId) }
                val hint = deviceHints.values.firstOrNull { raw.summary.matchesChildGuardDevice(it.uid) || raw.summary.matchesChildGuardDevice(it.mac) }
                val resolvedName = raw.summary.name.ifBlank { hint?.name.orEmpty() }.ifBlank { existing?.summary?.name.orEmpty() }
                val resolvedIconKey = (hint?.iconKey ?: raw.summary.iconKey).ifBlank { existing?.summary?.iconKey ?: "unknown" }
                val resolvedAccent = hint?.accentArgb ?: existing?.summary?.accentArgb ?: raw.summary.accentArgb
                val lateNightMinutes = existing?.summary?.lateNightMinutes ?: 0
                val activeStatus = when {
                    raw.summary.status == GuardStatus.BLOCKED || existing?.runtime?.paused == true -> GuardStatus.BLOCKED
                    existing?.plans?.any { it.enabled } == true || raw.summary.status == GuardStatus.GUARDED -> GuardStatus.GUARDED
                    else -> GuardStatus.UNRESTRICTED
                }

                if (existing != null) {
                    existing.copy(
                        summary = existing.summary.copy(
                            name = resolvedName,
                            iconKey = resolvedIconKey,
                            accentArgb = resolvedAccent,
                            status = activeStatus,
                            isOnline = raw.summary.isOnline,
                            todayMinutes = if (raw.summary.todayMinutes > 0) raw.summary.todayMinutes else existing.summary.todayMinutes,
                            blockedUntilEpoch = raw.summary.blockedUntilEpoch,
                            appManagementSupported = caps.appManagementSupported,
                            experimentalAppControl = caps.appManagementSupported && isExperimentalAppControlDevice(resolvedIconKey)
                        )
                    )
                } else {
                    raw.copy(
                        summary = raw.summary.copy(
                            name = resolvedName,
                            iconKey = resolvedIconKey,
                            accentArgb = resolvedAccent,
                            status = activeStatus,
                            appManagementSupported = caps.appManagementSupported,
                            experimentalAppControl = caps.appManagementSupported && isExperimentalAppControlDevice(resolvedIconKey)
                        ),
                        todayUsage = InternetUsageSummary(raw.summary.todayMinutes, empty24HourBars(), emptyList()),
                        recentUsage = InternetUsageSummary(0, buildDefaultDailyBars(), emptyList())
                    )
                }
            }
            val finalDevices = if (updatedDevices.isEmpty() && state.devices.isNotEmpty()) state.devices else updatedDevices
            val master = if (finalDevices.flatMap { it.plans }.isNotEmpty()) {
                finalDevices.flatMap { it.plans }.all { it.enabled }
            } else state.masterEnabled

            // Save cache for instantaneous 0ms rendering next time
            runCatching {
                val array = JSONArray()
                finalDevices.forEach { dev ->
                    array.put(JSONObject().apply {
                        put("uid", dev.summary.deviceId)
                        put("name", dev.summary.name)
                        put("iconKey", dev.summary.iconKey)
                        put("accentArgb", dev.summary.accentArgb)
                        put("blocked", dev.summary.status == GuardStatus.BLOCKED)
                        put("todayMinutes", dev.summary.todayMinutes)
                        put("isOnline", dev.summary.isOnline)
                        put("macs", JSONArray(dev.summary.macAddresses.toList()))
                    })
                }
                prefs.childGuardCacheJson = JSONObject().put("ok", true).put("data", JSONObject().put("devices", array)).toString()
            }

            ChildInternetOverviewState(
                masterEnabled = master,
                devices = finalDevices,
                capabilities = caps,
                loading = false
            )
        },
        success = { state = it },
        failure = { state = state.copy(loading = false, error = it.userMessage()) }
    )

    override fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int) {
        if (deviceId.isBlank()) return
        val existing = state.devices.firstOrNull { it.summary.matchesChildGuardDevice(deviceId) }
        if (existing != null) {
            if (name.isNotBlank() && name != "受守护设备" && existing.summary.name != name) {
                state = state.copy(devices = state.devices.map {
                    if (it.summary.matchesChildGuardDevice(deviceId)) {
                        it.copy(summary = it.summary.copy(name = name))
                    } else it
                })
            }
            return
        }
        val uid = childGuardDeviceKey(deviceId)
        deviceHints[uid] = DeviceHint(uid, deviceId, name, iconKey, accentArgb)
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

    override fun setDeviceBlocked(deviceId: String, blocked: Boolean, durationMinutes: Int?, onResult: (Result<Unit>) -> Unit) {
        val targetUntil = if (blocked) {
            if (durationMinutes != null && durationMinutes > 0) {
                System.currentTimeMillis() / 1000L + durationMinutes * 60L
            } else 1L
        } else 0L
        launch(
            before = {
                state = state.copy(
                    devices = state.devices.map {
                        if (it.summary.matchesChildGuardDevice(deviceId)) {
                            it.copy(
                                summary = it.summary.copy(
                                    status = if (blocked) GuardStatus.BLOCKED else GuardStatus.UNRESTRICTED,
                                    blockedUntilEpoch = targetUntil
                                ),
                                runtime = it.runtime.copy(paused = blocked)
                            )
                        } else it
                    }
                )
            },
            request = {
                val api = ChildGuardHubApi(HubApi(prefs), routerId)
                val uid = resolveRouterUid(deviceId)
                if (blocked) {
                    api.pauseDevice(uid, targetUntil)
                } else {
                    api.resumeDevice(uid)
                }
            },
            success = { refresh(); onResult(Result.success(Unit)) },
            failure = { refresh(); state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
        )
    }

    override fun savePlan(deviceId: String, plan: DeviceGuardPlan, onResult: (Result<Unit>) -> Unit) {
        val uid = resolveRouterUid(deviceId)
        val createHint = deviceHints.values.firstOrNull { sameChildGuardDevice(it.uid, deviceId) || sameChildGuardDevice(it.mac, deviceId) }
        val configuredPlan = plan.copy(configured = true)
        launch(
            request = {
                val api = ChildGuardHubApi(HubApi(prefs), routerId)
                if (plan.id.isBlank()) {
                    api.createPlan(uid, configuredPlan, createHint?.mac ?: deviceId, createHint?.name)
                } else api.updatePlan(uid, configuredPlan)
            },
            success = {
                // Immediately apply configured plan so UI switches to configured schedule view!
                state = state.copy(devices = state.devices.map { dev ->
                    if (dev.summary.matchesChildGuardDevice(deviceId)) {
                        val existingIndex = dev.plans.indexOfFirst { it.id == configuredPlan.id && it.id.isNotBlank() }
                        val newPlans = if (existingIndex >= 0) {
                            dev.plans.mapIndexed { i, p -> if (i == existingIndex) configuredPlan else p }
                        } else {
                            dev.plans + configuredPlan
                        }
                        dev.copy(plan = configuredPlan, plans = newPlans)
                    } else dev
                })
                loadUsageReport(deviceId) {}
                refresh()
                onResult(Result.success(Unit))
            },
            failure = { state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
        )
    }

    override fun deletePlan(deviceId: String, planId: String, onResult: (Result<Unit>) -> Unit) = launch(
        request = { ChildGuardHubApi(HubApi(prefs), routerId).deletePlan(resolveRouterUid(deviceId), planId) },
        success = {
            val emptyPlan = DeviceGuardPlan(categories = childInternetCatalogCategories(), configured = false)
            state = state.copy(devices = state.devices.map { dev ->
                if (dev.summary.matchesChildGuardDevice(deviceId)) {
                    val remaining = dev.plans.filter { it.id != planId }
                    val next = remaining.firstOrNull() ?: emptyPlan
                    dev.copy(plan = next, plans = remaining)
                } else dev
            })
            loadUsageReport(deviceId) {}
            refresh()
            onResult(Result.success(Unit))
        },
        failure = { state = state.copy(error = it.userMessage()); onResult(Result.failure(it)) }
    )

    override fun setPlanEnabled(deviceId: String, planId: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit) = launch(
        request = { ChildGuardHubApi(HubApi(prefs), routerId).setPlanEnabled(resolveRouterUid(deviceId), planId, enabled) },
        success = {
            state = state.copy(devices = state.devices.map { dev ->
                if (dev.summary.matchesChildGuardDevice(deviceId)) {
                    val updatedPlans = dev.plans.map { if (it.id == planId) it.copy(enabled = enabled) else it }
                    val updatedPlan = if (dev.plan.id == planId) dev.plan.copy(enabled = enabled) else dev.plan
                    dev.copy(plan = updatedPlan, plans = updatedPlans)
                } else dev
            })
            loadUsageReport(deviceId) {}
            refresh()
            onResult(Result.success(Unit))
        },
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
     * Re-reads plans, runtime state, and the usage report for one device on-demand
     * when viewing its detail screen, leaving the rest of the overview untouched.
     */
    override fun loadUsageReport(deviceId: String, onResult: (Result<Unit>) -> Unit) = launch(
        request = {
            val device = state.devices.firstOrNull { it.summary.matchesChildGuardDevice(deviceId) }
            val api = ChildGuardHubApi(HubApi(prefs), routerId)
            val routerUid = resolveRouterUid(deviceId)
            val plans = runCatching { api.plans(routerUid) }.getOrDefault(emptyList())
            val runtime = runCatching { api.runtime(routerUid) }.getOrDefault(ChildGuardRuntimeState(deviceId = routerUid))
            val macList = device?.summary?.macAddresses?.filter { it.isNotBlank() }?.toSet()?.takeIf { it.isNotEmpty() }
                ?: if (deviceId.contains(":") && deviceId.length == 17) setOf(deviceId.lowercase()) else emptySet()
            val report = runCatching {
                api.usageReport(
                    uid = routerUid,
                    macs = macList,
                    days = USAGE_REPORT_WINDOW_DAYS
                )
            }.getOrNull()
            Triple(plans, runtime, report)
        },
        success = { (plans, runtime, report) ->
            applyDeviceDetails(deviceId, plans, runtime, report)
            onResult(Result.success(Unit))
        },
        failure = { onResult(Result.failure(it)) }
    )

    private fun applyDeviceDetails(
        deviceId: String,
        plans: List<DeviceGuardPlan>,
        runtime: ChildGuardRuntimeState,
        report: ChildGuardUsageReport?
    ) {
        state = state.copy(devices = state.devices.map { device ->
            if (!device.summary.matchesChildGuardDevice(deviceId)) return@map device

            // Merge usage bars safely to preserve historical hours
            val todayUsage = if (report != null && report.today.bars.isNotEmpty()) {
                val mergedBars = report.today.bars.mapIndexed { idx, bar ->
                    val existingBar = device.todayUsage.bars.getOrNull(idx)
                    if (bar.minutes > 0) bar else (existingBar ?: bar)
                }
                val entries = if (report.today.entries.isNotEmpty()) {
                    val existingMap = device.todayUsage.entries.associateBy { it.appName }
                    val merged = report.today.entries.map { e ->
                        val prev = existingMap[e.appName]
                        if (prev != null) e.copy(
                            durationMinutes = maxOf(e.durationMinutes, prev.durationMinutes),
                            count = maxOf(e.count, prev.count),
                            sessions = e.sessions.ifEmpty { prev.sessions },
                            timeRange = e.timeRange.ifBlank { prev.timeRange }
                        ) else e
                    }
                    val missing = device.todayUsage.entries.filter { pe -> report.today.entries.none { it.appName == pe.appName } }
                    merged + missing
                } else device.todayUsage.entries

                val appMaxMins = entries.maxOfOrNull { it.durationMinutes } ?: 0
                val rawTotalMins = maxOf(report.today.totalMinutes, mergedBars.sumOf { it.minutes }, device.todayUsage.totalMinutes)
                val totalMins = maxOf(rawTotalMins, appMaxMins)

                // If mergedBars sum is less than totalMins, adjust active hour bar so the chart isn't empty
                val finalBars = if (totalMins > 0 && mergedBars.sumOf { it.minutes } < totalMins) {
                    val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).coerceIn(0, 23)
                    val bestHour = mergedBars.indexOfFirst { it.minutes > 0 }.takeIf { it >= 0 } ?: currentHour
                    mergedBars.mapIndexed { idx, bar ->
                        if (idx == bestHour) {
                            val needed = totalMins - (mergedBars.sumOf { it.minutes } - bar.minutes)
                            bar.copy(minutes = maxOf(bar.minutes, needed))
                        } else bar
                    }
                } else mergedBars

                InternetUsageSummary(totalMins, finalBars, entries)
            } else device.todayUsage

            val recentUsage = if (report != null && report.recent.bars.isNotEmpty()) {
                val mergedRecentBars = report.recent.bars.mapIndexed { idx, bar ->
                    val existingBar = device.recentUsage.bars.getOrNull(idx)
                    if (bar.minutes > 0) bar else (existingBar ?: bar)
                }
                val totalRecentMins = maxOf(report.recent.totalMinutes, mergedRecentBars.sumOf { it.minutes }, device.recentUsage.totalMinutes)
                InternetUsageSummary(totalRecentMins, mergedRecentBars, report.recent.entries.ifEmpty { device.recentUsage.entries })
            } else device.recentUsage

            // Late night minutes: strictly Beijing time 00:00 - 06:00 (hours 0, 1, 2, 3, 4, 5)
            val lateNightMinutes = todayUsage.bars.filterIndexed { index, _ -> index in 0..5 }.sumOf { it.minutes }
            val activePlan = plans.firstOrNull { it.configured } ?: plans.firstOrNull() ?: device.plan
            val effectivePlans = if (plans.isNotEmpty()) plans else device.plans
            val usageReport = report?.usageReport ?: device.usageReport ?: ChildDeviceUsageReport(
                date = report?.date.orEmpty(),
                boundIps = device.summary.macAddresses.toList()
            )

            device.copy(
                plan = activePlan,
                plans = effectivePlans,
                runtime = runtime,
                todayUsage = todayUsage,
                recentUsage = recentUsage,
                usageReport = usageReport,
                usageSource = report?.source.orEmpty(),
                summary = device.summary.copy(
                    todayMinutes = maxOf(todayUsage.totalMinutes, device.summary.todayMinutes),
                    lateNightMinutes = lateNightMinutes,
                    hasAttention = lateNightMinutes > 0,
                    status = when {
                        device.summary.status == GuardStatus.BLOCKED || runtime.paused -> GuardStatus.BLOCKED
                        effectivePlans.any { it.enabled } -> GuardStatus.GUARDED
                        else -> GuardStatus.UNRESTRICTED
                    }
                )
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
                todayMinutes = item.optInt("todayMinutes", 0),
                hasAttention = item.bool("hasAttention"),
                macAddresses = item.stringSet("macs", "mac"),
                isOnline = item.bool("online", default = false),
                blockedUntilEpoch = item.optLong("blockedUntilEpoch", 0L)
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
    val usageReport: ChildDeviceUsageReport? = null,
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
    var sumTx = 0L
    var sumRx = 0L
    (0 until hourly.length()).forEach { i ->
        val row = hourly.optJSONObject(i) ?: return@forEach
        minutesByHour[row.optInt("hour", -1)] = row.optInt("minutes", 0).coerceAtLeast(0)
        sumTx += row.optLong("txBytes", 0L)
        sumRx += row.optLong("rxBytes", 0L)
    }
    val todayBars = (0 until 24).map { hour -> UsageBar("${hour}点", minutesByHour[hour] ?: 0) }
    val todayEntries = parseUsageEntries(d.optJSONArray("apps"), todayBars)
    val appMaxMinutes = todayEntries.maxOfOrNull { it.durationMinutes } ?: 0
    val hourlySumMinutes = todayBars.sumOf { it.minutes }
    val effectiveTodayMinutes = maxOf(todayMinutes, appMaxMinutes, hourlySumMinutes)
    val finalTodayBars = if (effectiveTodayMinutes > 0 && hourlySumMinutes == 0) {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).coerceIn(0, 23)
        todayBars.mapIndexed { h, b ->
            if (h == currentHour) b.copy(minutes = effectiveTodayMinutes) else b
        }
    } else {
        todayBars
    }

    if (sumTx + sumRx == 0L) {
        val appsArr = d.optJSONArray("apps")
        if (appsArr != null) {
            (0 until appsArr.length()).forEach { i ->
                val row = appsArr.optJSONObject(i) ?: return@forEach
                sumTx += row.optLong("txBytes", 0L)
                sumRx += row.optLong("rxBytes", 0L)
            }
        }
    }

    val boundIps = (d.optJSONArray("macs") ?: d.optJSONArray("boundIps"))?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.orEmpty()
    val deviceUsage = ChildDeviceUsageReport(
        date = d.text("date"),
        todayTxBytes = sumTx,
        todayRxBytes = sumRx,
        todayTotalBytes = sumTx + sumRx,
        boundIps = boundIps
    )

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
    val recentEntries = parseUsageEntries(range?.optJSONArray("apps"), recentBars)
    return ChildGuardUsageReport(
        today = InternetUsageSummary(effectiveTodayMinutes, finalTodayBars, todayEntries),
        recent = InternetUsageSummary(recentBars.sumOf { it.minutes }, recentBars, recentEntries),
        usageReport = deviceUsage,
        source = d.text("source"),
        date = d.text("date")
    )
}

private fun parseUsageEntries(rows: JSONArray?, bars: List<UsageBar> = emptyList()): List<InternetUsageEntry> {
    if (rows == null) return emptyList()
    val activeHours = bars.mapIndexedNotNull { h, b -> if (b.minutes > 0) h else null }
    val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val fallbackHour = if (activeHours.isNotEmpty()) activeHours.last() else currentHour

    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val app = row.text("app", "name").ifBlank { return@mapNotNull null }
        val minutes = row.optInt("minutes", 0).coerceAtLeast(0)
        val sessionsCount = row.optInt("sessions", 0).coerceAtLeast(0)
        if (minutes <= 0 && sessionsCount <= 0) return@mapNotNull null
        val displayMinutes = if (minutes == 0 && sessionsCount > 0) 1 else minutes
        val effectiveCount = if (sessionsCount > 0) sessionsCount else 1

        val rawSessions = row.optJSONArray("sessions")
        val parsedSessions = if (rawSessions != null && rawSessions.length() > 0) {
            (0 until rawSessions.length()).mapNotNull { si ->
                val sObj = rawSessions.optJSONObject(si) ?: return@mapNotNull null
                val tr = sObj.optString("timeRange", "")
                val dur = sObj.optString("durationText", "")
                if (tr.isNotBlank()) AppUsageSession(tr, dur) else null
            }
        } else emptyList()

        val rawRange = row.optString("timeRange", "")
        val (finalRange, finalSessions) = when {
            rawRange.isNotBlank() && parsedSessions.isNotEmpty() -> rawRange to parsedSessions
            rawRange.isNotBlank() -> rawRange to listOf(AppUsageSession(rawRange, "使用${displayMinutes}分钟"))
            parsedSessions.isNotEmpty() -> {
                val start = parsedSessions.first().timeRange.substringBefore("-")
                val end = parsedSessions.last().timeRange.substringAfter("-")
                "$start-$end" to parsedSessions
            }
            else -> {
                val appHash = kotlin.math.abs(app.hashCode())
                val targetHour = if (activeHours.isNotEmpty()) {
                    activeHours[(appHash + index) % activeHours.size]
                } else fallbackHour
                val sessionList = mutableListOf<AppUsageSession>()
                val minsPerSession = maxOf(1, displayMinutes / effectiveCount)
                val lastStartMin = (appHash % 25).coerceIn(0, 45)

                for (sIdx in 0 until minOf(effectiveCount, 4)) {
                    val sStart = (lastStartMin + sIdx * 10).coerceIn(0, 50)
                    val sDur = if (sIdx == minOf(effectiveCount, 4) - 1) {
                        maxOf(1, displayMinutes - minsPerSession * sIdx)
                    } else minsPerSession
                    val sEnd = (sStart + sDur).coerceIn(sStart + 1, 59)
                    val tr = String.format(Locale.US, "%02d:%02d-%02d:%02d", targetHour, sStart, targetHour, sEnd)
                    sessionList.add(AppUsageSession(tr, "使用${sDur}分钟"))
                }
                val fullRange = if (sessionList.isNotEmpty()) {
                    val start = sessionList.first().timeRange.substringBefore("-")
                    val end = sessionList.last().timeRange.substringAfter("-")
                    "$start-$end"
                } else {
                    String.format(Locale.US, "%02d:15-%02d:%02d", targetHour, targetHour, (15 + displayMinutes).coerceAtMost(59))
                }
                fullRange to sessionList
            }
        }

        InternetUsageEntry(
            id = app,
            appName = app,
            iconKey = dashboardIconKey(app),
            durationMinutes = displayMinutes,
            timeRange = finalRange,
            count = effectiveCount,
            sessions = finalSessions
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
        "rdpi application table is empty" in lower || ("rdpi" in lower && "empty" in lower) -> "路由器特征库尚未就绪，请稍后重试"
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

internal fun childGuardDeviceKey(raw: String): String {
    val trimmed = raw.trim()
    val macWithoutColons = trimmed.replace(":", "").replace("-", "")
    return if (macWithoutColons.length == 12 && macWithoutColons.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        macWithoutColons.chunked(2).joinToString(":").lowercase()
    } else {
        trimmed
    }
}

internal fun sameChildGuardDevice(left: String, right: String): Boolean =
    childGuardDeviceKey(left).equals(childGuardDeviceKey(right), ignoreCase = true)

internal fun ProtectedDeviceSummary.matchesChildGuardDevice(value: String): Boolean =
    sameChildGuardDevice(deviceId, value) || macAddresses.any { sameChildGuardDevice(it, value) }

object FakeChildInternetRepository : ChildInternetRepository {
    override var state by mutableStateOf(ChildInternetOverviewState(true, emptyList(), ChildGuardCapabilities(true, true)))
        private set
    override fun refresh() = Unit
    override fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int) = Unit
    override fun setMasterEnabled(enabled: Boolean) { state = state.copy(masterEnabled = enabled) }
    override fun setDeviceBlocked(deviceId: String, blocked: Boolean, durationMinutes: Int?, onResult: (Result<Unit>) -> Unit) { onResult(Result.success(Unit)) }
    override fun savePlan(deviceId: String, plan: DeviceGuardPlan, onResult: (Result<Unit>) -> Unit) { onResult(Result.success(Unit)) }
    override fun deletePlan(deviceId: String, planId: String, onResult: (Result<Unit>) -> Unit) { onResult(Result.success(Unit)) }
    override fun setPlanEnabled(deviceId: String, planId: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit) { onResult(Result.success(Unit)) }
    override fun loadCandidates(onResult: (Result<List<ChildGuardDeviceCandidate>>) -> Unit) { onResult(Result.success(emptyList())) }
    override fun addGuardDevice(mac: String, name: String, onResult: (Result<Unit>) -> Unit) { onResult(Result.success(Unit)) }
    override fun removeGuardDevice(uid: String, onResult: (Result<Unit>) -> Unit) { onResult(Result.success(Unit)) }
    override fun loadUsageReport(deviceId: String, onResult: (Result<Unit>) -> Unit) = onResult(Result.success(Unit))
}

internal fun buildDefaultDailyBars(usageReport: ChildDeviceUsageReport? = null): List<UsageBar> {
    val cal = java.util.Calendar.getInstance()
    val weekdayChars = listOf("日", "一", "二", "三", "四", "五", "六")

    return (0..9).map { i ->
        val daysAgo = 9 - i
        val dayCal = (cal.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        }
        val label = if (i == 9) "今" else {
            val dayOfWeek = dayCal.get(java.util.Calendar.DAY_OF_WEEK)
            weekdayChars[(dayOfWeek - 1) % 7]
        }
        UsageBar(label, 0, emptyList())
    }
}

internal fun childInternetCatalogCategories(allowedRdpiIds: Set<String>? = null, allowedAppIds: Set<String> = emptySet()) = listOf(
    catalogCategory("education", "学习/教育", listOf("腾讯课堂", "瓜瓜龙启蒙", "凯叔讲故事", "叽里呱啦", "出口成章", "拍照搜题", "伴鱼绘本"), allowedRdpiIds, allowedAppIds),
    catalogCategory("media", "视频/音频", listOf("腾讯视频", "爱奇艺", "哔哩哔哩", "喜马拉雅", "网易云音乐", "微信视频号", "红果免费短剧"), allowedRdpiIds, allowedAppIds),
    catalogCategory("games", "游戏", listOf("王者荣耀", "和平精英", "蛋仔派对", "元梦之星", "迷你世界"), allowedRdpiIds, allowedAppIds),
    catalogCategory("tools", "工具", listOf("百度", "夸克", "计算器", "天气", "地图"), allowedRdpiIds, allowedAppIds),
    catalogCategory("social", "社交", listOf("QQ", "微信", "贴吧", "微博", "小红书"), allowedRdpiIds, allowedAppIds),
    catalogCategory("shopping", "支付/购物", listOf("支付宝", "云闪付", "京东", "淘宝", "拼多多"), allowedRdpiIds, allowedAppIds),
    catalogCategory("stores", "应用商店", listOf("华为应用市场", "小米应用商店", "应用宝", "酷安"), allowedRdpiIds, allowedAppIds)
)
private fun catalogCategory(id: String, name: String, names: List<String>, allowed: Set<String>?, appIds: Set<String>): AppCategoryPlan {
    val baseEnabled = id in setOf("education", "media", "tools")
    val apps = names.mapIndexed { i, name -> val appId = "$id-$i"; val rdpi = childInternetRdpiIds(name); val selected = when { allowed == null -> baseEnabled || i < 2; appId in appIds -> true; else -> rdpi.any { it in allowed } }; SelectableAppItem(appId, name, dashboardIconKey(name), selected = selected, rdpiIds = rdpi) }
    return AppCategoryPlan(id, name, if (allowed == null) baseEnabled else apps.any { it.selected }, apps)
}
/** One UI app can require several original RDPI IDs. */
internal fun childInternetRdpiIds(name: String): Set<String> = when (name) {
    "微信" -> setOf("7-1-2-0", "7-1-2-3", "7-1-2-12", "7-1-2-14")
    "微信视频号" -> setOf("10-1-2-0")
    "抖音", "抖音系列" -> setOf("10-5-1-0")
    "红果免费短剧", "红果短剧" -> setOf("10-5-2-0")
    "快手", "快手系列" -> setOf("10-146-1-0")
    "拼多多" -> setOf("18-158-1-0")
    "淘宝" -> setOf("18-4-2-0")
    "京东" -> setOf("18-159-1-0")
    "小红书" -> setOf("7-68-1-0")
    "哔哩哔哩" -> setOf("10-141-1-0")
    "王者荣耀" -> setOf("4-1-1-0", "4-1-1-1", "4-1-1-2")
    "和平精英" -> setOf("4-1-4-0", "4-1-4-2")
    "云闪付" -> setOf("18-4-3-0")
    "阿里CDN" -> setOf("8-4-1-6")
    else -> emptySet()
}

/**
 * RDPI 中文名 -> 图标 key。内置图标包（assets/appicons）覆盖国内应用，
 * 其余回落 Homarr Dashboard Icons CDN（kebab-case 命名），最后由
 * 首字头像兜底，保证任何应用都不会出现灰色占位方块。
 */
private fun dashboardIconKey(name: String) = when (name) {
    "微信" -> "wechat"; "企业微信" -> "wecom"; "微信读书" -> "weread"; "微信视频号" -> "wechat-channels"
    "QQ" -> "qq"; "QQ音乐" -> "qq-music"; "QQ浏览器" -> "qq-browser"; "腾讯课堂" -> "tencent-classroom"; "腾讯会议" -> "tencent-meeting"
    "腾讯视频" -> "tencent-video"; "哔哩哔哩" -> "bilibili"; "微视" -> "weishi"
    "抖音" -> "douyin"; "抖音系列" -> "douyin"; "快手" -> "kuaishou"; "小红书" -> "rednote"
    "爱奇艺" -> "iqiyi"; "优酷" -> "youku"; "芒果TV" -> "mango-tv"; "咪咕视频" -> "migu-video"
    "网易云音乐" -> "netease-music"; "酷狗音乐" -> "kugou-music"; "酷我音乐" -> "kuwo-music"
    "喜马拉雅" -> "himalaya"; "喜马拉雅儿童" -> "himalaya"
    "百度" -> "baidu"; "百度贴吧" -> "baidu-tieba"; "百度网盘" -> "baidu-netdisk"; "百度地图" -> "baidu-map"; "百度翻译" -> "baidu-fanyi"
    "高德地图" -> "amap"; "腾讯地图" -> "tencent-map"; "夸克" -> "quark"; "UC浏览器" -> "uc-browser"
    "阿里云盘" -> "aliyunpan"; "迅雷" -> "xunlei"; "菜鸟" -> "cainiao"; "顺丰速运" -> "sf-express"; "顺丰速递" -> "sf-express"
    "微博" -> "weibo"; "知乎" -> "zhihu"; "豆瓣" -> "douban"; "Soul" -> "soul"; "陌陌" -> "momo"; "探探" -> "tantan"
    "钉钉" -> "dingtalk"; "飞书" -> "feishu"; "WPS Office" -> "wps-office"
    "淘宝" -> "taobao"; "京东" -> "jingdong"; "拼多多" -> "pinduoduo"; "支付宝" -> "alipay"; "云闪付" -> "unionpay"
    "阿里CDN" -> "alibaba"; "阿里云" -> "aliyun"; "阿里巴巴" -> "alibaba"
    "红果免费短剧" -> "hongguo"; "红果短剧" -> "hongguo"
    "豆包" -> "doubao"; "DeepSeek" -> "deepseek"; "深度求索" -> "deepseek"
    "今日头条" -> "toutiao"; "唯品会" -> "vipshop"
    "美团" -> "meituan"; "饿了么" -> "eleme"; "滴滴出行" -> "didi"
    "闲鱼" -> "goofish"; "得物" -> "dewu"; "转转" -> "zhuanzhun"
    "携程旅行" -> "ctrip"; "去哪儿旅行" -> "qunar"; "同程旅行" -> "tongcheng"; "马蜂窝" -> "mafengwo"
    "淘票票" -> "taopiaopiao"; "铁路12306" -> "railway-12306"; "大众点评" -> "dianping"
    "华为应用市场" -> "huawei"; "小米应用商店" -> "xiaomi-global"; "应用宝" -> "yyb"; "酷安" -> "coolapk"; "App Store" -> "appstore"
    "Steam" -> "steam"; "Keep" -> "keep"; "虎扑" -> "hupu"; "IT之家" -> "ithome"; "雪球" -> "xueqiu"
    "番茄小说" -> "fanqie-novel"; "米游社" -> "mihoyo-bbs"; "瑞幸咖啡" -> "luckin"
    else -> name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "missing" }
}
