package com.labprobe.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

interface ChildInternetRepository {
    val state: ChildInternetOverviewState
    /** 进入页面 / 20 秒轮询：只读 Hub 已经算好的聚合，绝不要求路由器重扫。 */
    fun refreshOverview()
    /** 把上次成功抓取的持久缓存立刻铺回界面，网络回来后就地替换。 */
    fun hydrateFromCache()
    /** 重量级：逐设备 plans/runtime/usage 扇出，只留给用户主动要求细节的动作。 */
    fun refresh()
    fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int)
    fun setMasterEnabled(enabled: Boolean)
    fun setDeviceBlocked(deviceId: String, blocked: Boolean, durationMinutes: Int? = null, onResult: (Result<Unit>) -> Unit = {})
    /** 临时放行：只报时长代号（today/10m/30m/1h/cancel），绝对截止时间由 Hub 按北京时间算。 */
    fun setDevicePass(deviceId: String, preset: String, onResult: (Result<Unit>) -> Unit = {})
    fun savePlan(deviceId: String, plan: DeviceGuardPlan, onResult: (Result<Unit>) -> Unit = {})
    fun deletePlan(deviceId: String, planId: String, onResult: (Result<Unit>) -> Unit = {})
    fun setPlanEnabled(deviceId: String, planId: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit = {})
    fun loadCandidates(onResult: (Result<List<ChildGuardDeviceCandidate>>) -> Unit)
    fun addGuardDevice(mac: String, name: String, onResult: (Result<Unit>) -> Unit)
    fun removeGuardDevice(uid: String, onResult: (Result<Unit>) -> Unit)
    /** 单台设备的官方式上网报告：先回放缓存，再后台抓取。 */
    fun loadUsageReport(deviceId: String, onResult: (Result<Unit>) -> Unit)
    /** 「最近10天」点中某天时只要那一天的详情；缓存命中就不碰网络。 */
    fun loadUsageForDate(deviceId: String, date: String, onResult: (Result<Unit>) -> Unit = {})
}

/** 唯一的 Hub 传输出口；注入它才能在没有 Context 的单元测试里驱动整个仓库。 */
internal fun interface ChildGuardTransport {
    fun request(path: String, method: String, body: JSONObject?): JSONObject
}

/**
 * Hub 收了命令但路由器还没 ack（HTTP 202 + ``commandId``）。
 *
 * 这既不是失败也不是数据：把它当数据解析会得到一份空计划，把屏幕上真实的那一屏
 * 擦掉；当失败报又会弹一个「请求失败」的红色横幅。所以单独一个类型，读的时候留着
 * 缓存再查一次，写的时候留着「同步中」轮询 ``commandId``。
 */
internal class ChildGuardPendingException(val commandId: String) :
    Exception("路由器仍在处理这条命令")

private class HubChildGuardTransport(private val prefs: AppPrefs) : ChildGuardTransport {
    /** 与旧实现一致：每次请求新建 HubApi，Hub 地址与 DNS 改动即时生效。 */
    override fun request(path: String, method: String, body: JSONObject?): JSONObject =
        HubApi(prefs).requestJson(path, method, body)
}

/**
 * 儿童守护聚合缓存。键一律是 `routerId|设备|统计日`，日期进键就保证
 * 新的一天不会把昨天的数字当成今天的实况；值是 Hub 原样返回的 JSON。
 */
internal interface ChildGuardCache {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun keys(): List<String>
}

internal fun childGuardUsageCacheKey(routerId: String, deviceKey: String, date: String): String =
    "$routerId|$deviceKey|$date"

internal fun childGuardOverviewCacheKey(routerId: String, date: String): String =
    "$routerId|${CHILD_GUARD_OVERVIEW_SEGMENT}|$date"

internal const val CHILD_GUARD_OVERVIEW_SEGMENT = "overview"

/** 缓存只保留统计窗口内的日期，超出的整条丢掉，防止 SharedPreferences 无限膨胀。 */
internal fun childGuardCacheKeyIsExpired(key: String, today: String, keepDays: Int): Boolean {
    val date = key.lastDatePart() ?: return false
    if (date == today) return false
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return false
    val limit = runCatching { LocalDate.parse(today) }.getOrNull() ?: return false
    return day.isBefore(limit.minusDays((keepDays + 1).coerceAtLeast(1).toLong()))
}

/** 键的末段必须是 `YYYY-MM-DD`；否则这条不是「某一天」的报告缓存。 */
private val CHILD_GUARD_DATE_SEGMENT = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun String.lastDatePart(): String? = substringAfterLast('|').takeIf { CHILD_GUARD_DATE_SEGMENT.matches(it) }

private class SharedChildGuardCache(private val prefs: AppPrefs) : ChildGuardCache {
    override fun read(key: String): String? = runCatching {
        JSONObject(prefs.childGuardUsageCacheJson).optString(key).takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun write(key: String, value: String) {
        val today = childGuardStatisticsDate()
        val store = runCatching { JSONObject(prefs.childGuardUsageCacheJson) }.getOrElse { JSONObject() }
        store.put(key, value)
        prefs.childGuardUsageCacheJson = pruneChildGuardCache(store, today, USAGE_REPORT_WINDOW_DAYS).toString()
    }

    override fun keys(): List<String> = runCatching {
        val names = JSONObject(prefs.childGuardUsageCacheJson).names() ?: JSONArray()
        (0 until names.length()).mapNotNull { names.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
}

/**
 * 丢掉超出统计窗口的日期键。`names()` 返回值本身就是 JSONArray，
 * 绝不能再套一层 `JSONArray(...)`——那会命中原始数组构造器直接抛异常，
 * 把整次上网报告写入变成「更新失败」。
 */
internal fun pruneChildGuardCache(store: JSONObject, today: String, keepDays: Int): JSONObject {
    val names = store.names() ?: return store
    (0 until names.length()).mapNotNull { names.optString(it).takeIf(String::isNotBlank) }
        .forEach { stored ->
            if (childGuardCacheKeyIsExpired(stored, today, keepDays)) store.remove(stored)
        }
    return store
}

// 202 之后多久再看一次 —— 见 ``retryDetails`` / ``awaitCommand``。
private const val PENDING_READ_RETRY_MS = 3_000L
private const val PENDING_READ_ATTEMPTS = 5
private const val COMMAND_POLL_MS = 2_000L
private const val COMMAND_POLL_ATTEMPTS = 15

/** Production repository: only confirmed router members and measured usage are shown. */
class RealChildInternetRepository internal constructor(
    private val routerId: String,
    private val transport: ChildGuardTransport,
    private val cache: ChildGuardCache,
    private val scope: CoroutineScope
) : ChildInternetRepository {
    constructor(
        prefs: AppPrefs,
        routerId: String = "default",
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    ) : this(routerId, HubChildGuardTransport(prefs), SharedChildGuardCache(prefs), scope)

    private val api get() = ChildGuardHubApi(transport, routerId)
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var overviewJob: kotlinx.coroutines.Job? = null
    private var revision = 0L
    private val pending = mutableSetOf<String>()

    /** 缓存键快照，用来判断某天是否已经抓过报告，省掉一次磁盘扫。 */
    private var cacheKeys: List<String> = emptyList()

    /** 补抓只发生一次/设备/统计日，轮询不会把它变成隐性轮播。 */
    private val usageBackfilled = mutableSetOf<String>()

    override var state by mutableStateOf(ChildInternetOverviewState(false, emptyList(), loading = true))
        private set

    init {
        // App 启动即铺缓存：页面第一帧就有内容，不依赖网络是否已经回来。
        hydrateFromCache()
    }

    override fun hydrateFromCache() {
        scope.launch {
            val today = childGuardStatisticsDate()
            cacheKeys = withContext(Dispatchers.IO) { cacheKeyList() }
            // 缓存里的聚合与逐台报告都是几十到几百 KB 的 JSON，解析挪出主线程；
            // 铺回界面前后状态一致，只是不再阻塞首页第一帧之后的第一次滑动。
            val cached = withContext(Dispatchers.IO) { readCachedOverview(today) }
            if (cached == null) { state = state.copy(loading = true); return@launch }
            // 只有今天的聚合才带着「现在」的在离线；昨天的那份只留身份。
            val snapshot = if (cached.first == today) cached.second else cached.second.withoutPresence()
            applyOverview(snapshot, cacheRead = true)
            val uids = state.devices.map { device -> device.summary.deviceId }
            val reports = withContext(Dispatchers.IO) {
                uids.mapNotNull { uid -> readCachedUsagePayload(uid)?.let { payload -> uid to parseChildGuardUsageReport(payload) } }
            }
            reports.forEach { (uid, report) ->
                updateDevice(uid) { current ->
                    if (current.usage != null) current else current.withUsage(report)
                }
            }
            state = state.copy(loading = state.devices.isEmpty())
        }
    }

    /** 今天的聚合优先；换天了才退回最近一份，至少设备名单不用等网络。 */
    private fun readCachedOverview(today: String): Pair<String, ChildGuardOverviewSnapshot>? {
        val prefix = "$routerId|$CHILD_GUARD_OVERVIEW_SEGMENT|"
        val keys = cacheKeys.filter { it.startsWith(prefix) }.sortedBy { it.substringAfterLast('|') }
            .takeIf { it.isNotEmpty() } ?: return null
        val chosen = if (keys.last() == today) keys.last() else keys.lastOrNull { it != today } ?: keys.last()
        val raw = runCatching { cache.read(chosen) }.getOrNull() ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return runCatching { chosen.substringAfterLast('|') to parseChildGuardOverview(root) }.getOrNull()
    }

    override fun refreshOverview() {
        overviewJob?.cancel()
        val expected = revision
        state = state.copy(refreshing = true, loading = state.devices.isEmpty(), error = "")
        overviewJob = scope.launch { updateOverviewFromAggregate(expected) }
    }

    /**
     * 总览聚合的唯一抓取路径：GET 读的是 Hub 已经算好的行，不要求路由器重扫，
     * 失败时保留上一屏内容，只把「更新失败 · 最后更新 …」显示出来。
     */
    internal suspend fun updateOverviewFromAggregate(expected: Long = revision) {
        try {
            val root = withContext(Dispatchers.IO) { api.overview() }
            if (expected != revision) return
            val snapshot = withContext(Dispatchers.IO) {
                parseChildGuardOverview(root).also { parsed ->
                    cache.write(childGuardOverviewCacheKey(routerId, parsed.date.ifBlank { childGuardStatisticsDate() }), root.toString())
                }
            }
            applyOverview(snapshot)
            val cachedReports = withContext(Dispatchers.IO) {
                state.devices.mapNotNull { device ->
                    readCachedUsagePayload(device.summary.deviceId)?.let { payload ->
                        device.summary.deviceId to parseChildGuardUsageReport(payload)
                    }
                }
            }
            cachedReports.forEach { (uid, report) ->
                updateDevice(uid) { current -> if (current.usage != null) current else current.withUsage(report) }
            }
            state = state.copy(error = "")
            backfillMissingUsage(expected)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (expected == revision) state = state.copy(error = error.userMessage())
        } finally {
            if (expected == revision) state = state.copy(refreshing = false, loading = false)
        }
    }

    /**
     * 总览卡片上的数字归上网报告所有。缓存里没有这一天的设备才补抓一次，
     * 每台每统计日最多一次，串行进行——绝不在 20 秒轮询里重演全量扇出。
     */
    private suspend fun backfillMissingUsage(expected: Long) {
        val today = childGuardStatisticsDate()
        val missing = state.devices.filter { device ->
            device.usage?.date != today && usageCacheKey(device.summary.deviceId, today) !in cacheKeys
        }
        for (device in missing) {
            if (expected != revision) return
            val uid = device.summary.deviceId
            val gate = usageCacheKey(uid, today)
            if (!usageBackfilled.add(gate)) continue
            try {
                updateUsageFromReport(uid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                usageBackfilled.remove(gate)
                // 一台补抓失败不能擦掉整屏：数字留在 `--`，错误只在总状态上示一次。
                if (expected == revision && state.error.isBlank()) state = state.copy(error = error.userMessage())
            }
        }
    }

    /** 总览只写成员、身份、守护状态与在离线；统计数字一个都不碰。 */
    private fun applyOverview(snapshot: ChildGuardOverviewSnapshot, cacheRead: Boolean = false) {
        val previous = state.devices
        val available = previous.toMutableList()
        val matched = snapshot.devices.map { row ->
            val cached = available.firstOrNull { device ->
                device.summary.matchesChildGuardDevice(row.uid) ||
                    row.macs.any { mac -> device.summary.matchesChildGuardDevice(mac) }
            }
            cached?.let { available.remove(it) }
            (cached ?: newChildGuardDevice(row.uid, row.name, row.iconKey, row.macs)).copy(
                summary = (cached?.summary ?: row.summary).copy(
                    // 新名字优先；路由器这一轮只给了占位串时，继承上一轮的真名字，
                    // 两者都没有才用 MAC 尾号 —— 名字不能随着轮询在两类回退间跳。
                    name = childGuardDisplayName(
                        listOf(row.name, cached?.summary?.name.orEmpty()), row.uid, row.macs
                    ),
                    iconKey = row.iconKey.ifBlank { cached?.summary?.iconKey.orEmpty() },
                    macAddresses = row.macs.ifEmpty { cached?.summary?.macAddresses ?: setOf(row.uid) },
                    isOnline = snapshot.presenceKnown && row.online,
                    status = if (row.blocked) GuardStatus.BLOCKED else cached?.summary?.status ?: GuardStatus.UNRESTRICTED,
                    blockedUntilEpoch = row.blockedUntilEpoch,
                    passUntilEpoch = row.passUntilEpoch
                ),
                presence = if (snapshot.presenceKnown) row.presence else null,
                schedule = row.schedule
            )
        }
        // 聚合里消失的设备是 Hub 确认过的离队成员才掉出列表；整个 devices
        // 字段缺失（旧 Hub / 降级 payload）时保留上一屏，绝不因为读不到就清空。
        val kept = if (snapshot.hasDeviceRows) matched else previous.ifEmpty { matched }
        state = state.copy(
            devices = kept,
            generatedAtEpoch = snapshot.generatedAtEpoch ?: state.generatedAtEpoch,
            lastSampleAtEpoch = snapshot.lastSampleAtEpoch ?: state.lastSampleAtEpoch,
            stale = snapshot.stale,
            refreshing = if (cacheRead) state.refreshing else false,
            loading = false
        )
    }

    private fun usageCacheKey(deviceId: String, date: String): String =
        childGuardUsageCacheKey(routerId, childGuardDeviceKey(deviceId), date)

    override fun refresh() = refreshFrom(0)

    private fun refreshFrom(attempt: Int) {
        refreshJob?.cancel()
        val expected = revision
        state = state.copy(loading = state.devices.isEmpty(), error = "")
        refreshJob = scope.launch {
            try {
                val client = api
                val devices = withContext(Dispatchers.IO) { client.devices() }
                val caps = withContext(Dispatchers.IO) {
                    runCatching { client.capabilities() }.getOrDefault(state.capabilities)
                }
                if (expected != revision) return@launch
                state = state.copy(
                    devices = mergeChildGuardDeviceList(state.devices, devices, caps),
                    capabilities = caps, loading = false
                )
                // A single sequence avoids flooding the router with three calls per device at once.
                for (device in devices) {
                    if (expected != revision) break
                    try { readDetails(device.summary.deviceId, expected) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (error.needsASilentRetry()) retryDetails(device.summary.deviceId, expected, 0)
                        else if (expected == revision) state = state.copy(error = error.userMessage())
                    }
                }
                updateMasterState()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error.needsASilentRetry()) {
                    // 上一轮还在路由器手里，或这一秒 DNS 没解析出来：留着当前这一屏，
                    // 几秒后自己再来一次，别把用户推到「再点一次刷新」。
                    state = state.copy(loading = false)
                    if (expected == revision && attempt < PENDING_READ_ATTEMPTS) {
                        delay(PENDING_READ_RETRY_MS)
                        refreshFrom(attempt + 1)
                    }
                } else if (expected == revision) {
                    state = state.copy(loading = false, error = error.userMessage())
                }
            }
        }
    }

    override fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int) {
        updateDevice(deviceId) { device ->
            device.copy(summary = device.summary.copy(
                name = name.ifBlank { device.summary.name },
                iconKey = iconKey.ifBlank { device.summary.iconKey },
                accentArgb = accentArgb
            ))
        }
    }

    override fun setMasterEnabled(enabled: Boolean) {
        // 一台设备一次调用，不是一条计划一次。每条写都要中继跑一轮
        // `/etc/init.d/child_guard reload`（真机 40 秒以上，还会先拆 iptables 链），
        // 逐条发就是让路由器连续 busy 一分多钟，期间所有 Hub 请求都卡住 —— 界面上
        // 就是反复弹「儿童守护请求失败」。
        val devices = state.devices.filter { d -> d.plans.any { it.id.isNotBlank() } }
        if (devices.isEmpty()) return
        mutate("*", {}, request = {
            devices.forEach { device ->
                api.setAllPlansEnabled(resolveRouterUid(device.summary.deviceId), enabled)
            }
            // 逐台各发一次写，`accepted` 不看响应，所以这里只交回「都发完了」。
            JSONObject()
        }, hudText = "配置中…", accepted = {
            state = state.copy(devices = state.devices.map { d ->
                withPlans(d, d.plans.map { it.copy(enabled = enabled) })
            })
        })
    }

    override fun setDeviceBlocked(deviceId: String, blocked: Boolean, durationMinutes: Int?, onResult: (Result<Unit>) -> Unit) {
        val until = if (!blocked) 0L else durationMinutes?.takeIf { it > 0 }
            ?.let { System.currentTimeMillis() / 1000 + it * 60L } ?: 1L
        mutate(deviceId, onResult, request = {
            if (blocked) api.pauseDevice(resolveRouterUid(deviceId), until)
            else api.resumeDevice(resolveRouterUid(deviceId))
        }, hudText = if (blocked) "禁网中…" else "恢复中…", accepted = {
            updateDevice(deviceId) { d ->
                d.copy(
                    runtime = d.runtime.copy(paused = blocked, blockedUntilEpoch = until),
                    summary = d.summary.copy(blockedUntilEpoch = until,
                        status = if (blocked) GuardStatus.BLOCKED else guardStatus(false, d.plans))
                )
            }
        })
    }

    override fun setDevicePass(deviceId: String, preset: String, onResult: (Result<Unit>) -> Unit) {
        mutate(deviceId, onResult, request = { api.passDevice(resolveRouterUid(deviceId), preset) },
            hudText = "放行中…", accepted = { response ->
                // 路由器回的是它真正接受的绝对时间；界面只存这一个数，剩余分钟由卡片自己算。
                val until = response.data().optLong("passUntilEpoch", 0L)
                updateDevice(deviceId) { d ->
                    d.copy(
                        runtime = d.runtime.copy(passUntilEpoch = until),
                        summary = d.summary.copy(passUntilEpoch = until)
                    )
                }
            })
    }

    override fun savePlan(deviceId: String, plan: DeviceGuardPlan, onResult: (Result<Unit>) -> Unit) {
        if (plan.repeatDays.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("请至少选择一个重复日期")))
            return
        }
        val device = state.devices.firstOrNull { it.summary.matchesChildGuardDevice(deviceId) }
        mutate(deviceId, onResult, request = {
            if (plan.id.isBlank()) api.createPlan(resolveRouterUid(deviceId), plan,
                device?.summary?.macAddresses?.firstOrNull(), device?.summary?.name)
            else api.updatePlan(resolveRouterUid(deviceId), plan)
        }, hudText = "配置中…", accepted = { response ->
            val body = response.data().optJSONObject("plan") ?: response.data()
            val id = body.text("id", "planId", "policyId").ifBlank { plan.id }
            if (id.isNotBlank()) updateDevice(deviceId) { d ->
                val saved = plan.copy(id = id, configured = true)
                withPlans(d, d.plans.filterNot { it.id == saved.id } + saved)
            }
        })
    }

    override fun deletePlan(deviceId: String, planId: String, onResult: (Result<Unit>) -> Unit) =
        mutate(deviceId, onResult, request = { api.deletePlan(resolveRouterUid(deviceId), planId) },
            hudText = "删除中…",
            accepted = { updateDevice(deviceId) { withPlans(it, it.plans.filterNot { p -> p.id == planId }) } })

    override fun setPlanEnabled(deviceId: String, planId: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit) =
        mutate(deviceId, onResult, request = { api.setPlanEnabled(resolveRouterUid(deviceId), planId, enabled) },
            hudText = "配置中…",
            accepted = { updateDevice(deviceId) { withPlans(it, it.plans.map { p -> if (p.id == planId) p.copy(enabled = enabled) else p }) } })

    override fun loadCandidates(onResult: (Result<List<ChildGuardDeviceCandidate>>) -> Unit) {
        scope.launch {
            try { onResult(Result.success(withContext(Dispatchers.IO) { api.candidates() })) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { onResult(Result.failure(error)) }
        }
    }

    override fun addGuardDevice(mac: String, name: String, onResult: (Result<Unit>) -> Unit) =
        mutate(mac, onResult, request = { api.addDevice(listOf(mac), name) },
            hudText = "加入中…", accepted = {})

    override fun removeGuardDevice(uid: String, onResult: (Result<Unit>) -> Unit) =
        mutate(uid, onResult, request = { api.removeDevice(resolveRouterUid(uid)) },
            hudText = "解除中…", accepted = {
            state = state.copy(devices = state.devices.filterNot { it.summary.matchesChildGuardDevice(uid) })
        })

    override fun loadUsageReport(deviceId: String, onResult: (Result<Unit>) -> Unit) {
        val expected = revision
        val uid = resolveRouterUid(deviceId)
        scope.launch {
            setUsageLoading(uid, true)
            try {
                applyCachedUsage(uid)
                readDetails(uid, expected)
                // 一次成功的读取就是这一屏的「新」，之前那条红色横幅该退了。
                if (expected == revision) state = state.copy(error = "")
                onResult(Result.success(Unit))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error.needsASilentRetry()) {
                    // 路由器还在处理，或这一秒手机解析不出 Hub：留着当前这一屏，
                    // 几秒后自己再看一次，不弹红色横幅让用户再点一遍刷新。
                    retryDetails(uid, expected, 0)
                    onResult(Result.success(Unit))
                } else {
                    // 抓不到就继续显示缓存那一屏，只把失败状态显示出来。
                    if (expected == revision) state = state.copy(error = error.userMessage())
                    onResult(Result.failure(error))
                }
            } finally {
                setUsageLoading(uid, false)
            }
        }
    }

    /**
     * 「最近10天」点中某一天只要那一天的详情 —— 官方就是逐日详情，不是 10 天汇总，
     * 汇总会把「周三看了 2 小时抖音」摊成看不出顺序的一堆数字。缓存键本来就含日期，
     * 所以看过的天再点开零网络流量。
     */
    override fun loadUsageForDate(deviceId: String, date: String, onResult: (Result<Unit>) -> Unit) {
        val uid = resolveRouterUid(deviceId)
        if (date.isBlank()) return
        updateDevice(uid) { current ->
            current.copy(dayUsage = ChildGuardDayUsage(
                date, InternetUsageSummary(null, emptyList(), emptyList()), loading = true))
        }
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    runCatching { cache.read(usageCacheKey(uid, date)) }.getOrNull()
                        ?.let { raw -> runCatching { parseChildGuardUsageReport(JSONObject(raw)).today }.getOrNull() }
                }
                if (cached != null) {
                    setDayUsage(uid, date, cached)
                    onResult(Result.success(Unit))
                    return@launch
                }
                val device = state.devices.firstOrNull { it.summary.matchesChildGuardDevice(uid) }
                val payload = withContext(Dispatchers.IO) {
                    api.usageReportPayload(uid, device?.summary?.macAddresses ?: emptySet(), date = date, days = 1)
                }
                val (usage, keys) = withContext(Dispatchers.IO) {
                    cache.write(usageCacheKey(uid, date), payload.toString())
                    parseChildGuardUsageReport(payload).today to cacheKeyList()
                }
                cacheKeys = keys
                setDayUsage(uid, date, usage)
                onResult(Result.success(Unit))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                // 抓不到就把那一格留空、停掉转圈：列表页还在，用户没做错什么，
                // 不值得为一次逐日补读弹红色横幅。
                updateDevice(uid) { current ->
                    val slot = current.dayUsage
                    if (slot != null && slot.date == date) current.copy(dayUsage = slot.copy(loading = false))
                    else current
                }
                onResult(Result.failure(error))
            }
        }
    }

    private fun setDayUsage(uid: String, date: String, usage: InternetUsageSummary) {
        updateDevice(uid) { current ->
            val slot = current.dayUsage
            if (slot != null && slot.date == date && !slot.loading) current
            else current.copy(dayUsage = ChildGuardDayUsage(date, usage))
        }
    }

    private suspend fun readDetails(uid: String, expected: Long) {
        val device = state.devices.firstOrNull { it.summary.matchesChildGuardDevice(uid) } ?: return
        val client = api
        val plans = withContext(Dispatchers.IO) { client.plans(uid) }
        val runtime = withContext(Dispatchers.IO) { client.runtime(uid) }
        if (expected != revision || uid in pending) return
        updateDevice(uid) { d ->
            withPlans(d, plans).copy(runtime = runtime, summary = d.summary.copy(
                status = guardStatus(runtime.paused, plans),
                blockedUntilEpoch = runtime.blockedUntilEpoch,
                passUntilEpoch = runtime.passUntilEpoch
            ))
        }
        updateUsageFromReport(uid)
    }

    /**
     * 202 之后的自动补读：界面上不留红色横幅，也不让用户再点一次刷新。
     *
     * 次数封顶是故意的 —— 路由器真不在的时候，无限重试只会把 Hub 的队列填满
     * 同一台设备的同一条读命令。
     */
    private fun retryDetails(uid: String, expected: Long, attempt: Int) {
        if (attempt >= PENDING_READ_ATTEMPTS) return
        scope.launch {
            delay(PENDING_READ_RETRY_MS)
            if (expected != revision) return@launch
            try {
                readDetails(uid, expected)
                state = state.copy(error = "")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error.needsASilentRetry()) retryDetails(uid, expected, attempt + 1)
                else state = state.copy(error = error.userMessage())
            }
        }
    }

    /** 上网报告 payload 的唯一抓取入口；写入状态的一律是 `applyUsage`。 */
    internal suspend fun updateUsageFromReport(uid: String) {
        val device = state.devices.firstOrNull { it.summary.matchesChildGuardDevice(uid) } ?: return
        val payload = withContext(Dispatchers.IO) {
            api.usageReportPayload(uid, device.summary.macAddresses, days = USAGE_REPORT_WINDOW_DAYS)
        }
        if (uid in pending) return
        applyUsage(uid, payload)
    }

    /**
     * 报告 → 设备状态：全场唯一写统计数字的地方（实时抓取、缓存回放、显式刷新
     * 三条路都汇到这里），所以缓存回放和实况永远不会长成两副样子。
     */
    private suspend fun applyUsage(uid: String, payload: JSONObject) {
        val (report, keys) = withContext(Dispatchers.IO) {
            val parsed = parseChildGuardUsageReport(payload)
            val date = parsed.stats.date.ifBlank { childGuardStatisticsDate() }
            cache.write(usageCacheKey(uid, date), payload.toString())
            parsed to cacheKeyList()
        }
        cacheKeys = keys
        updateDevice(uid) { it.withUsage(report) }
    }

    /**
     * 打开页面前先把这台设备最近一份报告铺回去。缓存按 `routerId|设备|日期`
     * 分键，所以昨天那份只会以昨天的日期出现，不会冒充今天的实况。
     */
    private suspend fun applyCachedUsage(uid: String) {
        val report = withContext(Dispatchers.IO) {
            readCachedUsagePayload(uid)?.let(::parseChildGuardUsageReport)
        } ?: return
        updateDevice(uid) { current ->
            if (current.usage != null) current else current.withUsage(report)
        }
    }

    /** 找这台设备最近一份报告的原始 JSON；纯读，供 IO 线程调用。 */
    private fun readCachedUsagePayload(uid: String): JSONObject? {
        val prefix = usageCacheKey(uid, "").dropLast(1)
        val latestKey = cacheKeys.filter { it.startsWith(prefix) && it.lastDatePart() != null }
            .maxByOrNull { it.lastDatePart().orEmpty() } ?: return null
        val raw = runCatching { cache.read(latestKey) }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun cacheKeyList(): List<String> = runCatching { cache.keys() }.getOrDefault(emptyList())

    private fun setUsageLoading(uid: String, loading: Boolean) =
        updateDevice(uid) { d -> if (d.usageLoading == loading) d else d.copy(usageLoading = loading) }

    private fun mutate(deviceId: String, onResult: (Result<Unit>) -> Unit,
        request: () -> JSONObject, accepted: (JSONObject) -> Unit, hudText: String = "正在同步…") {
        val uid = resolveRouterUid(deviceId)
        if (pending.isNotEmpty()) {
            onResult(Result.failure(IllegalStateException("正在同步上一项操作，请稍候")))
            return
        }
        revision++
        refreshJob?.cancel()
        pending.add(uid)
        state = state.copy(error = "", pendingHud = hudText, pendingDeviceIds = pending.toSet())
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { request() }
                accepted(response)
                updateMasterState()
                clearPending(uid)
                // Acknowledgement is independent of the slower background read-back.
                refreshAfterMutation(uid)
                onResult(Result.success(Unit))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (stillRunning: ChildGuardPendingException) {
                // 202 = Hub 已受理、路由器还在写。这不是失败：留着「同步中」，
                // 轮询 commandId，等真正的结果回来再落状态。
                awaitCommand(stillRunning.commandId, uid, onResult, accepted)
            }
            catch (error: Exception) {
                clearPending(uid)
                // A timeout or a multi-device partial failure may still have changed router state.
                refreshAfterMutation(uid)
                state = state.copy(error = error.userMessage())
                onResult(Result.failure(error))
            }
        }
    }

    /**
     * 轮询 Hub 的命令结果；成功/失败/超时三条路都会把「同步中」摘掉。
     *
     * 拿到最终结果时必须补做同步路径那份 `accepted`：Hub 回 202 时 `accepted` 一次
     * 都没跑，界面上「解除儿童守护」成功了设备却还在列表里，就是因为这一份状态
     * 只落在了「路由器同步返回」那条少数的快路上。
     */
    private fun awaitCommand(commandId: String, uid: String,
                             onResult: (Result<Unit>) -> Unit,
                             accepted: (JSONObject) -> Unit,
                             attempt: Int = 0) {
        if (commandId.isBlank() || attempt >= COMMAND_POLL_ATTEMPTS) {
            clearPending(uid)
            // 问不到结果不等于成功：命令可能还在路由器上跑，也可能已经失败。
            // 报成功会让界面弹「已解除儿童守护」并把那一行留在原地，所以这里
            // 只说「还在处理」，把判断交给读回来的真实状态。
            refreshAfterMutation(uid)
            onResult(Result.failure(IllegalStateException(
                "路由器还在处理，稍后会自动更新，不用重复操作")))
            return
        }
        scope.launch {
            delay(COMMAND_POLL_MS)
            try {
                val result = withContext(Dispatchers.IO) { api.commandResult(commandId) }
                accepted(result)
                clearPending(uid)
                updateMasterState()
                refreshAfterMutation(uid)
                onResult(Result.success(Unit))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (stillRunning: ChildGuardPendingException) {
                awaitCommand(commandId, uid, onResult, accepted, attempt + 1)
            } catch (error: Exception) {
                clearPending(uid)
                refreshAfterMutation(uid)
                state = state.copy(error = error.userMessage())
                onResult(Result.failure(error))
            }
        }
    }

    private fun clearPending(uid: String) {
        pending.remove(uid)
        state = state.copy(
            pendingHud = if (pending.isEmpty()) "" else state.pendingHud,
            pendingDeviceIds = pending.toSet()
        )
    }

    /**
     * 写操作后要读回真实状态：单机只补这一台的细节，全设备开关才付得起整轮扇出。
     * `resolveRouterUid` 把 `*` 原样带回，所以这里只会用一次重刷。
     */
    private fun refreshAfterMutation(uid: String) {
        if (uid == "*") {
            refresh()
            return
        }
        refreshOverview()
        scope.launch {
            try { readDetails(uid, revision) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error.needsASilentRetry()) retryDetails(uid, revision, 0)
                else state = state.copy(error = error.userMessage())
            }
        }
    }

    private fun updateDevice(id: String, transform: (ChildInternetDeviceState) -> ChildInternetDeviceState) {
        state = state.copy(devices = state.devices.map {
            if (it.summary.matchesChildGuardDevice(id)) transform(it) else it
        })
    }

    private fun updateMasterState() {
        val plans = state.devices.flatMap { it.plans }
        state = state.copy(masterEnabled = plans.any { it.enabled })
    }

    private fun withPlans(device: ChildInternetDeviceState, plans: List<DeviceGuardPlan>) =
        device.copy(plans = plans,
            plan = plans.firstOrNull() ?: DeviceGuardPlan(categories = childInternetCatalogCategories()),
            summary = device.summary.copy(status = guardStatus(device.runtime.paused, plans)))

    private fun resolveRouterUid(id: String): String =
        state.devices.firstOrNull { it.summary.matchesChildGuardDevice(id) }?.summary?.deviceId
            ?.let(::childGuardDeviceKey) ?: childGuardDeviceKey(id)
}

internal fun guardStatus(blocked: Boolean, plans: List<DeviceGuardPlan>): GuardStatus = when {
    blocked -> GuardStatus.BLOCKED
    plans.any { it.enabled } -> GuardStatus.GUARDED
    else -> GuardStatus.UNRESTRICTED
}

/**
 * 上网报告写入设备状态的唯一入口：缓存回放与实时抓取都走这里，
 * 所以同一份 payload 无论来自磁盘还是网络，界面长成一个样。
 */
internal fun ChildInternetDeviceState.withUsage(report: ChildGuardUsageReport): ChildInternetDeviceState = copy(
    todayUsage = report.today, recentUsage = report.recent, usage = report.stats,
    attentionEntries = report.attention, usageReport = report.traffic, usageSource = report.source
)

/** 总览/成员表里新出现的设备：只带身份，统计一律留空等上网报告来写。 */
internal fun newChildGuardDevice(
    uid: String, name: String, iconKey: String, macs: Set<String> = emptySet()
): ChildInternetDeviceState =
    ChildInternetDeviceState(
        summary = ProtectedDeviceSummary(
            deviceId = childGuardDeviceKey(uid),
            name = childGuardDisplayName(listOf(name), uid, macs),
            iconKey = iconKey.ifBlank { "unknown" },
            accentArgb = 0xFF64748B.toInt(),
            status = GuardStatus.UNRESTRICTED
        ),
        plan = DeviceGuardPlan(categories = childInternetCatalogCategories())
    )

/** Empty successful responses remove members; transport failures never call this merger. */
internal fun mergeChildGuardDeviceList(
    previous: List<ChildInternetDeviceState>, incoming: List<ChildInternetDeviceState>, caps: ChildGuardCapabilities
): List<ChildInternetDeviceState> = incoming.distinctBy { childGuardDeviceKey(it.summary.deviceId) }.map { raw ->
    val cached = previous.firstOrNull { it.summary.matchesChildGuardDevice(raw.summary.deviceId) }
    (cached ?: raw).copy(
        summary = raw.summary.copy(
            appManagementSupported = caps.appManagementSupported,
            experimentalAppControl = caps.appManagementSupported && isExperimentalAppControlDevice(raw.summary.iconKey),
            status = guardStatus(raw.summary.status == GuardStatus.BLOCKED, cached?.plans.orEmpty())
        ),
        // 统计数字、提醒与在离线都归原写入者，成员刷新只是不改它们。
        usage = cached?.usage,
        todayUsage = cached?.todayUsage ?: raw.todayUsage,
        recentUsage = cached?.recentUsage ?: raw.recentUsage,
        attentionEntries = cached?.attentionEntries ?: raw.attentionEntries,
        usageReport = cached?.usageReport ?: raw.usageReport,
        usageSource = cached?.usageSource ?: raw.usageSource,
        presence = cached?.presence ?: raw.presence,
        runtime = (cached?.runtime ?: raw.runtime).copy(paused = raw.summary.status == GuardStatus.BLOCKED,
            blockedUntilEpoch = raw.summary.blockedUntilEpoch,
            passUntilEpoch = raw.summary.passUntilEpoch)
    )
}

/** The sole Android location that knows the stable Hub paths, never raw UCI/sniffer fields. */
internal class ChildGuardHubApi(private val hub: ChildGuardTransport, routerId: String) {
    private val base = "/api/router/child-guard"
    private val routerQuery = "?router=${pathPart(routerId)}"
    fun capabilities() = parseChildGuardCapabilities(get("$base/capabilities$routerQuery"))
    fun devices() = parseChildGuardDevices(get("$base/devices$routerQuery"))

    /**
     * 总览聚合。Hub 读的是 router_usage_minute 里已经算好的行，
     * 所以这个 GET 本身不会要求路由器重扫，可以放心 20 秒轮一次。
     */
    fun overview() = get("$base/overview$routerQuery")

    /** 202 之后的结果查询：路由器 ack 了才回 payload，还在处理就继续抛 pending。 */
    fun commandResult(commandId: String) = get("$base/command/${pathPart(commandId)}")
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
    /** 一台设备的所有计划一次写完：逐条发就是逐次固件 reload。 */
    fun setAllPlansEnabled(uid: String, enabled: Boolean) = write("$base/devices/${pathPart(uid)}/plans/enabled-all$routerQuery", "POST", JSONObject().put("enabled", enabled))
    fun pauseDevice(uid: String, untilEpoch: Long? = null) = write(
        "$base/devices/${pathPart(uid)}/pause$routerQuery",
        "POST",
        JSONObject().apply {
            if (untilEpoch != null && untilEpoch > 0) put("untilEpoch", untilEpoch)
        }
    )
    fun resumeDevice(uid: String) = write("$base/devices/${pathPart(uid)}/resume$routerQuery", "POST")
    /** 预设交给 Hub 换算：中继在路由器上算不出「今天还剩多久」。 */
    fun passDevice(uid: String, preset: String) = write(
        "$base/devices/${pathPart(uid)}/pass$routerQuery", "POST", JSONObject().put("preset", preset)
    )
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
    fun usageReport(uid: String, macs: Set<String>, date: String? = null, days: Int = 1): ChildGuardUsageReport =
        parseChildGuardUsageReport(usageReportPayload(uid, macs, date, days))

    /** Raw payload, kept separate so the caller can cache it verbatim and re-parse on replay. */
    fun usageReportPayload(uid: String, macs: Set<String>, date: String? = null, days: Int = 1): JSONObject {
        val query = buildString {
            append(routerQuery)
            append("&days=").append(days.coerceAtLeast(1))
            date?.takeIf { it.isNotBlank() }?.let { append("&date=").append(pathPart(it)) }
            val macList = macs.filter { it.isNotBlank() }
            if (macList.isNotEmpty()) append("&macs=").append(pathPart(macList.joinToString(",")))
        }
        return get("$base/devices/${pathPart(uid)}/usage-report$query")
    }

    private fun get(path: String) = checked(hub.request(path, "GET", null))
    private fun write(path: String, method: String, body: JSONObject = JSONObject()) = checked(hub.request(path, method, body))
    private fun checked(root: JSONObject): JSONObject {
        if (root.has("ok") && !root.optBoolean("ok")) throw IllegalStateException(root.optString("message").ifBlank { root.optString("error") }.ifBlank { "儿童守护请求失败" })
        // Hub 等不到路由器 ack 会回 202 + commandId。这不是失败，也**不是**数据：
        // 拿它去 parse 会得到一份空计划/空 runtime，把屏幕上真实的那一屏擦掉。
        if (root.optBoolean("pending")) throw ChildGuardPendingException(root.optString("commandId"))
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
    // Per-weekday windows, the shape the relay hands to the firmware timerange
    // list — this is what makes several rules on one weekday actually enforce.
    put("times", JSONObject().apply {
        repeatDays.sorted().forEach { day ->
            weekdayKey(day).takeIf { it.isNotBlank() }?.let { key ->
                put(key, JSONArray(listOf(JSONArray(listOf(startTime, endTime)))))
            }
        }
    })
    val applications = categories.filter { it.enabled }.flatMap { it.apps }.filter { it.selected && it.rdpiIds.isNotEmpty() }.map { app ->
        JSONObject().put("id", app.id).put("name", app.name).put("rdpiIds", JSONArray(app.rdpiIds.sorted()))
    }
    put("mode", if (applications.isEmpty()) "internet_window" else "app_allowlist")
    put("applications", JSONArray(applications))
}

private val WEEKDAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

internal fun weekdayKey(day: Int): String = WEEKDAY_KEYS.getOrElse(day - 1) { "" }

internal fun weekdayNumber(key: String): Int = WEEKDAY_KEYS.indexOf(key.trim().lowercase(Locale.US)) + 1

/**
 * Reads the per-weekday `times` map back into the rule's repeat days and window.
 * Rules created by this app always carry exactly one window, so the first range
 * is the whole rule; anything richer still shows up as correct repeat days.
 */
internal fun parsePlanTimes(times: JSONObject?): Pair<Set<Int>, Pair<String, String>?>? {
    if (times == null) return null
    val days = mutableSetOf<Int>()
    var firstRange: Pair<String, String>? = null
    times.keys().forEach { key ->
        val day = weekdayNumber(key)
        if (day !in 1..7) return@forEach
        val ranges = times.optJSONArray(key) ?: return@forEach
        if (ranges.length() == 0) return@forEach
        days.add(day)
        val range = ranges.optJSONArray(0)
        val start = range?.optString(0).orEmpty()
        val end = range?.optString(1).orEmpty()
        if (firstRange == null && start.isNotBlank() && end.isNotBlank()) firstRange = start to end
    }
    if (days.isEmpty()) return null
    return days to firstRange
}

internal fun parseChildGuardCapabilities(root: JSONObject): ChildGuardCapabilities {
    val d = root.data()
    val childGuard = d.bool("available")
    val rdpi = d.bool("rdpiEnabled")
    return ChildGuardCapabilities(childGuard, rdpi, d.text("version"), d.bool("appControlSupported", default = childGuard && rdpi))
}

internal fun parseChildGuardDevices(root: JSONObject): List<ChildInternetDeviceState> =
    childGuardDeviceRows(root).map { row ->
        ChildInternetDeviceState(
            summary = row.summary,
            presence = row.presence,
            plan = DeviceGuardPlan(categories = childInternetCatalogCategories())
        )
    }

/**
 * `/child-guard/overview` 的一行。这里只承认成员、身份、禁网状态与在离线；
 * 今日分钟数、深夜分钟数、提醒都不写入共享状态——那是上网报告的数字，
 * 总览卡片从 `routerId|设备|日期` 的报告缓存里读它们。
 */
internal data class ChildGuardOverviewDevice(
    val uid: String,
    val macs: Set<String>,
    val name: String,
    val iconKey: String,
    val online: Boolean,
    val activeNow: Boolean?,
    val lastSeenAtEpoch: Long?,
    val blocked: Boolean,
    val blockedUntilEpoch: Long,
    val passUntilEpoch: Long,
    val updatedAtEpoch: Long?,
    /** Hub 本地算出来的此刻生效态；state 为空就是还没读过这台设备的计划。 */
    val schedule: ChildGuardSchedule = ChildGuardSchedule(),
    val accentArgb: Int
) {
    val summary: ProtectedDeviceSummary
        get() = ProtectedDeviceSummary(
            deviceId = childGuardDeviceKey(uid),
            name = name,
            iconKey = iconKey,
            accentArgb = accentArgb,
            status = if (blocked) GuardStatus.BLOCKED else GuardStatus.UNRESTRICTED,
            macAddresses = macs,
            isOnline = online,
            blockedUntilEpoch = blockedUntilEpoch,
            passUntilEpoch = passUntilEpoch
        )
    val presence: ChildGuardPresence
        get() = ChildGuardPresence(online, activeNow, lastSeenAtEpoch, updatedAtEpoch)
}

internal data class ChildGuardOverviewSnapshot(
    val devices: List<ChildGuardOverviewDevice>,
    val date: String = "",
    val generatedAtEpoch: Long? = null,
    val lastSampleAtEpoch: Long? = null,
    val stale: Boolean = false,
    /** payload 里根本没有 devices 数组 ≠ 一台都没守护；前者不许清空上一屏。 */
    val hasDeviceRows: Boolean = false,
    /** 换天后回放的聚合只剩名单：在离线一律不写，界面宁可不画徽章。 */
    val presenceKnown: Boolean = true
) {
    fun withoutPresence(): ChildGuardOverviewSnapshot = copy(presenceKnown = false)
}

internal fun parseChildGuardOverview(root: JSONObject): ChildGuardOverviewSnapshot {
    val d = root.data()
    return ChildGuardOverviewSnapshot(
        devices = childGuardDeviceRows(root),
        date = d.text("date").ifBlank { d.text("statisticsDate") },
        generatedAtEpoch = d.longOrNull("generatedAt"),
        lastSampleAtEpoch = d.longOrNull("lastSampleAt"),
        stale = d.bool("stale", default = false),
        hasDeviceRows = d.optJSONArray("devices") != null
    )
}

private fun childGuardDeviceRows(root: JSONObject): List<ChildGuardOverviewDevice> {
    val devices = root.data().optJSONArray("devices") ?: JSONArray()
    return (0 until devices.length()).mapNotNull { i ->
        val item = devices.optJSONObject(i) ?: return@mapNotNull null
        val id = item.text("uid", "id", "deviceId", "mac").ifBlank { return@mapNotNull null }
        val rawIconKey = item.text("iconKey", "deviceType", "type")
        val iconKey = normalizeDeviceTypeToken(rawIconKey).ifBlank { rawIconKey }
        val macs = item.stringSet("macs", "mac")
        val online = item.bool("online", default = false)
        ChildGuardOverviewDevice(
            uid = id,
            macs = macs,
            // 这里只留「路由器真给的名字」，占位串一律当没有：名字的回退要等到
            // applyOverview 手里有上一轮的真名可以继承时再做。
            name = childGuardRealName(
                listOf(
                    item.text("userDefinedName"),
                    item.text("recommendedName"),
                    item.text("name", "displayName"),
                    item.text("hostname")
                ), id
            ),
            iconKey = iconKey,
            online = online,
            // 缺 activeNow 就是不知道，绝不能拿在线顶替「正在上网」。
            activeNow = item.boolOrNull("activeNow"),
            lastSeenAtEpoch = item.longOrNull("lastSeenAt"),
            blocked = item.bool("blocked", "paused"),
            blockedUntilEpoch = item.longOrNull("blockedUntilEpoch") ?: 0L,
            passUntilEpoch = item.longOrNull("passUntilEpoch") ?: 0L,
            updatedAtEpoch = item.longOrNull("updatedAt"),
            schedule = ChildGuardSchedule(
                state = item.text("schedule"),
                planCount = item.optInt("planCount", 0),
                currentStart = item.optJSONObject("currentRange")?.text("start").orEmpty(),
                currentEnd = item.optJSONObject("currentRange")?.text("end").orEmpty(),
                nextChangeAtEpoch = item.longOrNull("nextChangeAtEpoch"),
                minutesToChange = item.intOrNull("minutesToChange"),
            ),
            accentArgb = item.optInt("accentArgb", 0xFF64748B.toInt())
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
            online = item.bool("online", default = false),
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
        val times = parsePlanTimes(item.optJSONObject("times"))
        DeviceGuardPlan(
            id = id, configured = id.isNotBlank(), enabled = item.bool("enabled", "enable"),
            startTime = times?.second?.first ?: item.text("startTime").ifBlank { "17:00" },
            endTime = times?.second?.second ?: item.text("endTime").ifBlank { "21:30" },
            repeatDays = times?.first ?: item.weekdaySet("weekdays", "repeatDays"),
            categories = childInternetCatalogCategories(rdpiIds, allowedApps)
        )
    }
}

internal fun parseChildGuardRuntime(root: JSONObject, fallbackUid: String = ""): ChildGuardRuntimeState {
    val d = root.data().optJSONObject("runtime") ?: root.data()
    val bound = d.stringSet("policyIds")
    val effect = d.text("effectPolicyId").takeUnless { it.equals("none", true) }
    return ChildGuardRuntimeState(d.text("uid").ifBlank { fallbackUid }, bound, effect, mapRuntimeEffectPolicy(effect, bound),
        paused = d.bool("blocked", "paused"), blockedUntilEpoch = d.optLong("blockedUntilEpoch", 0L),
        passUntilEpoch = d.optLong("passUntilEpoch", 0L))
}

/** The 上网统计 window. Matches the Hub's relay/Hub retention (10 days). */
internal const val USAGE_REPORT_WINDOW_DAYS = 10

/** 报告缓存的统计日：与 Hub 的 `date` 同为「路由器本地那一天」，换天自然读到新键。 */
internal fun childGuardStatisticsDate(zoneId: ZoneId = childGuardDefaultZone()): String =
    LocalDate.now(zoneId).toString()

internal fun childGuardDefaultZone(): ZoneId = ZoneId.systemDefault()

/**
 * 时段窗口按路由器本地时区切：`00:00–06:00` 是路由器那一侧的自然小时，
 * 手机在国内而路由器在海外时硬编码北京会把整条时间线搬走。Hub 带上
 * `timezone` 时以它为准，缺字段才退回设备本地时区。
 */
internal fun childGuardZone(payloadTimezone: String?): ZoneId =
    payloadTimezone?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: childGuardDefaultZone()

internal data class ChildGuardUsageReport(
    val today: InternetUsageSummary,
    val recent: InternetUsageSummary,
    val stats: ChildUsageStats,
    val attention: List<ParentAttentionEntry> = emptyList(),
    val source: String = "",
    /** 固件设备计数器口径的流量，Hub 未返回 traffic 块时为 null。 */
    val traffic: ChildDeviceUsageReport? = null
)

/**
 * Parses the Hub's usage report into the two summaries the report page renders.
 *
 * * 今日 — hour bars straight from `hourly[]` (no invented 24-slot frame), plus
 *   per-app rows from `apps[]`.
 * * 最近10天 — one bar per date the Hub actually returned in `range.days[]`.
 * * traffic — device counters (固件总数口径), independent of the app classification.
 *
 * Apps that earned no active time are dropped: a backgrounded app that only sent
 * heartbeats is not "used", and listing thirty of them as 0 分钟 would be noise.
 *
 * 这是全场唯一解析上网报告的地方：`onlineMinutes`、`lateNightMinutes`、提醒状态
 * 只从这里写出去，总览聚合永远只读，所以不会出现两个写入者互相覆盖。
 */
internal fun parseChildGuardUsageReport(root: JSONObject, todayLabel: String = "今天"): ChildGuardUsageReport {
    val d = root.data()
    val zone = childGuardZone(d.text("timezone", "tz").takeIf { it.isNotBlank() })
    val date = d.text("date").ifBlank { childGuardStatisticsDate(zone) }
    val hasRecords = childGuardHasRecords(d)
    // 没有记录就没有「零分钟」可言：整块退回未知，界面显示 `--` 而不是 0分钟。
    val todayBars = if (hasRecords) parseHourBars(d.optJSONArray("hourly")) else emptyList()
    val todayEntries = if (hasRecords) parseUsageEntries(d.optJSONArray("apps"), zone) else emptyList()
    val todayMinutes = if (hasRecords) d.intOrNull("onlineMinutes")?.coerceAtLeast(0) ?: 0 else null
    val lateNightMinutes = if (hasRecords) childGuardLateNightMinutes(d, todayBars) else null
    val attentionState = childGuardAttentionState(d, hasRecords, lateNightMinutes)

    val range = d.optJSONObject("range")
    val rangeDays = range?.optJSONArray("days") ?: JSONArray()
    val dayCount = rangeDays.length()
    val recentBars = (0 until dayCount).mapNotNull { index ->
        rangeDays.optJSONObject(index)?.let { childGuardDayBar(it, isToday = index == dayCount - 1, todayLabel = todayLabel, zone = zone) }
    }
    val recentEntries = parseUsageEntries(range?.optJSONArray("apps"), zone)
    val recentTotal = recentBars.filter { it.hasData }.sumOf { it.minutes }.takeIf { recentBars.isNotEmpty() }
    val stats = ChildUsageStats(
        date = date,
        onlineMinutes = todayMinutes,
        lateNightMinutes = lateNightMinutes,
        attention = attentionState,
        hasData = hasRecords,
        generatedAtEpoch = d.longOrNull("generatedAt"),
        lastSampleAtEpoch = d.longOrNull("lastSampleAt"),
        stale = d.bool("stale", default = false)
    )
    return ChildGuardUsageReport(
        today = InternetUsageSummary(todayMinutes, todayBars, todayEntries),
        recent = InternetUsageSummary(recentTotal, recentBars, recentEntries),
        stats = stats,
        attention = childGuardAttentionEntries(stats, recentBars, parseLateNightWindows(d.optJSONArray("lateNightRanges"), zone)),
        source = d.text("source"),
        traffic = parseChildGuardTraffic(d.optJSONObject("traffic"))
    )
}

/** `coverage.status` 是 Hub 的官方口径：no_record/unavailable 就是没有记录，不是 0 分钟。 */
private fun childGuardHasRecords(d: JSONObject): Boolean {
    if (d.has("hasData") && !d.isNull("hasData")) return d.bool("hasData")
    val coverage = d.optJSONObject("coverage") ?: return d.intOrNull("onlineMinutes")?.let { it > 0 } ?: false
    if (coverage.has("hasRecords") && !coverage.isNull("hasRecords")) return coverage.bool("hasRecords")
    return coverage.text("status").let { it == "recorded" || it == "partial" }
}

/** 00:00–06:00 只认 Hub 的 `lateNightMinutes`；老 payload 才从 Hub 自己的小时桶求和。 */
private fun childGuardLateNightMinutes(d: JSONObject, todayBars: List<UsageBar>): Int? {
    d.intOrNull("lateNightMinutes")?.let { return it.coerceAtLeast(0) }
    val hours = d.optJSONArray("hourly") ?: return null
    if (hours.length() == 0) return null
    return todayBars.filter { it.hour in 0..5 }.sumOf { it.minutes }
}

private fun childGuardAttentionState(d: JSONObject, hasRecords: Boolean, lateNightMinutes: Int?): ChildAttentionState {
    val server = d.optJSONObject("attention")?.text("state")
        ?.takeIf { it.equals("none", true) || it.equals("notice", true) || it.equals("alert", true) || it.equals("unknown", true) }
    server?.let { return childGuardAttentionStateOf(it) }
    // 没记录 = 未知，绝不是「一切正常」。
    if (!hasRecords) return ChildAttentionState.UNKNOWN
    return if ((lateNightMinutes ?: 0) > 0) ChildAttentionState.ALERT else ChildAttentionState.NONE
}

internal fun childGuardAttentionStateOf(raw: String?): ChildAttentionState = when {
    raw.isNullOrBlank() -> ChildAttentionState.UNKNOWN
    raw.equals("none", true) -> ChildAttentionState.NONE
    raw.equals("notice", true) -> ChildAttentionState.NOTICE
    raw.equals("alert", true) -> ChildAttentionState.ALERT
    else -> ChildAttentionState.UNKNOWN
}

private fun parseHourBars(hourly: JSONArray?): List<UsageBar> {
    if (hourly == null || hourly.length() == 0) return emptyList()
    return (0 until hourly.length()).mapNotNull { i ->
        val row = hourly.optJSONObject(i) ?: return@mapNotNull null
        val hour = row.optInt("hour", -1)
        if (hour !in 0..23) return@mapNotNull null
        UsageBar(label = "${hour}点", minutes = row.optInt("minutes", 0).coerceAtLeast(0), hour = hour)
    }.sortedBy { it.hour }
}

private fun childGuardDayBar(day: JSONObject, isToday: Boolean, todayLabel: String, zone: ZoneId): UsageBar {
    val date = day.text("date")
    // `coverage: "no_record"` 的那天没有分钟数这回事；recorded 且 0 分钟才是真的「无上网记录」。
    val hasData = if (day.has("hasData") && !day.isNull("hasData")) day.bool("hasData")
    else day.text("coverage").isBlank() || day.text("coverage") == "recorded"
    return UsageBar(
        label = childUsageDayLabel(date, isToday = isToday, fallback = todayLabel),
        // 未记录的那天柱子画 0 高，明细行必须显示 `--`。
        minutes = if (hasData) day.intOrNull("onlineMinutes")?.coerceAtLeast(0) ?: 0 else 0,
        date = date,
        lateNightMinutes = if (hasData) day.intOrNull("lateNightMinutes")?.coerceAtLeast(0) else null,
        lateNightWindows = parseLateNightWindows(day.optJSONArray("lateNightRanges"), zone),
        hasData = hasData
    )
}

/**
 * 家长请注意只列服务器逐日返回的那几天：有记录才谈健康与否，
 * 没记录就是「暂无使用记录」，未知状态永远是 `--` / 数据同步中，不会写成「一切正常」。
 */
private fun childGuardAttentionEntries(
    stats: ChildUsageStats,
    days: List<UsageBar>,
    todayWindows: List<LateNightWindow>
): List<ParentAttentionEntry> {
    val history = days.filter { it.date.isNotBlank() && it.date != stats.date }
    val today = UsageBar(
        label = "今天",
        minutes = stats.onlineMinutes ?: 0,
        date = stats.date,
        lateNightMinutes = stats.lateNightMinutes,
        lateNightWindows = todayWindows.ifEmpty {
            history.firstOrNull { it.date == stats.date }?.lateNightWindows.orEmpty()
        },
        hasData = stats.hasData
    )
    return (listOf(today) + history).sortedByDescending { it.date }
        .map { day -> childGuardAttentionEntry(day.label, day) }
}

private fun childGuardAttentionEntry(label: String, day: UsageBar): ParentAttentionEntry {
    val minutes = day.lateNightMinutes
    val state = when {
        !day.hasData || minutes == null -> ChildAttentionState.UNKNOWN
        minutes > 0 -> ChildAttentionState.ALERT
        else -> ChildAttentionState.NONE
    }
    return ParentAttentionEntry(
        dayLabel = label,
        date = day.date,
        message = when (state) {
            ChildAttentionState.ALERT -> "【深夜上网】累计${formatLateNightDuration(minutes ?: 0)}"
            ChildAttentionState.NONE -> if (day.date == childGuardStatisticsDate()) "今日上网健康" else "未发现深夜上网"
            else -> "暂无使用记录"
        },
        normal = state == ChildAttentionState.NONE,
        hasData = day.hasData,
        windows = if (state == ChildAttentionState.ALERT) day.lateNightWindows else emptyList(),
        state = state
    )
}

/**
 * 分钟桶口径下每条 sessionRanges 就是一段连续活跃分钟：`endEpoch` 已是
 * 最后一个活跃分钟 +60（右开），所以区间直接照抄，时长只认 `minutes`，
 * 绝不用 end-start 或 activeSeconds 反推。不连续的分钟由 Hub 拆成多条。
 */
private fun parseUsageEntries(rows: JSONArray?, zone: ZoneId): List<InternetUsageEntry> {
    if (rows == null) return emptyList()
    // 一天之内的连续段，日期在标题上已经给过了；行内和弹窗都只留 HH:mm–HH:mm。
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val app = row.text("app", "name").ifBlank { return@mapNotNull null }
        val minutes = row.optInt("minutes", 0).coerceAtLeast(0)
        if (minutes <= 0) return@mapNotNull null
        val ranges = row.optJSONArray("sessionRanges") ?: row.optJSONArray("ranges") ?: JSONArray()
        val sessions = (0 until ranges.length()).mapNotNull { i ->
            val range = ranges.optJSONObject(i) ?: return@mapNotNull null
            val start = range.optLong("startEpoch", 0L)
            val end = range.optLong("endEpoch", 0L)
            val runMinutes = range.optInt("minutes", 0).coerceAtLeast(0)
            if (start <= 0 || end <= start || runMinutes <= 0) return@mapNotNull null
            runCatching { AppUsageSession(
                formatter.format(Instant.ofEpochSecond(start)) + " – " +
                    formatter.format(Instant.ofEpochSecond(end)),
                formatChildDuration(runMinutes)
            ) }.getOrNull()
        }
        InternetUsageEntry(
            id = app,
            appName = app,
            iconKey = dashboardIconKey(app),
            durationMinutes = minutes,
            timeRange = if (sessions.size == 1) sessions.first().timeRange else "",
            count = row.optInt("sessions", 0).coerceAtLeast(0),
            sessions = sessions,
            hourlyMinutes = parseAppHourMinutes(row)
        )
    }
}

/**
 * 应用小时分布只能照抄 Hub 的 `apps[].hourlyMinutes`——那份是按分钟桶分摊出来的
 * 估算，不是逐应用测量，缺字段就没有这一列，绝不用总时长自己造。
 */
private fun parseAppHourMinutes(row: JSONObject): Map<Int, Int> {
    val minutes = LinkedHashMap<Int, Int>()
    when (val raw = row.opt("hourlyMinutes")) {
        is JSONArray -> (0 until raw.length()).forEach { i ->
            val item = raw.optJSONObject(i) ?: return@forEach
            val hour = item.optInt("hour", -1)
            if (hour in 0..23) minutes[hour] = item.optInt("minutes", 0).coerceAtLeast(0)
        }
        is JSONObject -> raw.keys().forEach { key ->
            val hour = key.toIntOrNull() ?: return@forEach
            if (hour in 0..23) minutes[hour] = raw.optInt(key, 0).coerceAtLeast(0)
        }
        else -> Unit
    }
    return minutes
}

/** 00:00–06:00 sessions, which 家长请注意 renders as concrete red time ranges. */
private fun parseLateNightWindows(rows: JSONArray?, zone: ZoneId): List<LateNightWindow> {
    if (rows == null) return emptyList()
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val app = row.text("app", "name")
        val start = row.optLong("startEpoch", 0L)
        val end = row.optLong("endEpoch", 0L)
        val minutes = row.optInt("minutes", 0).coerceAtLeast(0)
        if (start <= 0L || end <= start || minutes <= 0) return@mapNotNull null
        runCatching {
            LateNightWindow(
                app = app,
                rangeText = formatter.format(Instant.ofEpochSecond(start)) + "–" +
                    formatter.format(Instant.ofEpochSecond(end)),
                minutes = minutes
            )
        }.getOrNull()
    }
}

/** 今天 / 昨天 / 周四 … for the 最近N天 bars. */

private fun childUsageDayLabel(isoDate: String, isToday: Boolean, fallback: String = "今天"): String {
    if (isToday) return fallback
    if (isoDate.isBlank()) return ""
    return runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate) ?: return@runCatching isoDate.takeLast(5)
        SimpleDateFormat("E", Locale.CHINA).format(parsed).removePrefix("星期")
    }.getOrDefault(isoDate.takeLast(5))
}

/** 设备总流量块；Hub 没发或全为零就不成立，返回 null 让卡片整体隐藏。 */
internal fun parseChildGuardTraffic(traffic: JSONObject?): ChildDeviceUsageReport? {
    if (traffic == null) return null
    val dailyArr = traffic.optJSONArray("daily") ?: JSONArray()
    val daily = (0 until dailyArr.length()).mapNotNull { i ->
        val item = dailyArr.optJSONObject(i) ?: return@mapNotNull null
        val date = item.optString("date", "")
        if (date.isBlank()) return@mapNotNull null
        DailyUsageItem(
            date = date,
            txBytes = item.optLong("txBytes", 0L),
            rxBytes = item.optLong("rxBytes", 0L),
            totalBytes = item.optLong("totalBytes", 0L)
        )
    }
    val report = ChildDeviceUsageReport(
        todayTxBytes = traffic.longOrNull("todayTxBytes") ?: traffic.optLong("txBytes", 0L),
        todayRxBytes = traffic.longOrNull("todayRxBytes") ?: traffic.optLong("rxBytes", 0L),
        todayTotalBytes = traffic.longOrNull("todayTotalBytes") ?: traffic.optLong("totalBytes", 0L),
        daily = daily
    )
    return report.takeIf { it.todayTotalBytes > 0L || it.todayTxBytes > 0L || it.todayRxBytes > 0L }
}
private fun JSONObject.data(): JSONObject = optJSONObject("data") ?: optJSONObject("capabilities") ?: this
/** 键不存在与值为 null 都算「服务器没说」，交给下面的 `*At` 返回 null。 */
private fun JSONObject.flag(key: String): Any? = if (!has(key) || isNull(key)) null else opt(key)
private fun boolAt(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> value.trim().let { token ->
        if (token == "1" || token.equals("true", true)) true
        else if (token == "0" || token.equals("false", true)) false
        else null
    }
    else -> null
}
private fun intAt(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is Boolean -> if (value) 1 else 0
    is String -> value.trim().toDoubleOrNull()?.toInt()
    else -> null
}
private fun longAt(value: Any?): Long? = when (value) {
    is Number -> value.toLong()
    is Boolean -> if (value) 1L else 0L
    is String -> value.trim().toDoubleOrNull()?.toLong()
    else -> null
}
private fun JSONObject.text(vararg keys: String): String = keys.firstNotNullOfOrNull { key -> flag(key)?.toString()?.trim()?.takeIf(String::isNotBlank) }.orEmpty()
private fun JSONObject.bool(vararg keys: String, default: Boolean = false): Boolean = keys.firstNotNullOfOrNull { boolAt(flag(it)) } ?: default
/** 缺字段与 0 是两回事：这三个 `*OrNull` 就是 §4 那条口径的落地点。 */
private fun JSONObject.boolOrNull(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { boolAt(flag(it)) }
private fun JSONObject.intOrNull(vararg keys: String): Int? = keys.firstNotNullOfOrNull { intAt(flag(it)) }
private fun JSONObject.longOrNull(vararg keys: String): Long? = keys.firstNotNullOfOrNull { longAt(flag(it)) }
private fun JSONObject.stringSet(vararg keys: String): Set<String> = keys.flatMap { key ->
    when (val v = opt(key)) { is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString()?.trim()?.takeIf(String::isNotBlank) }; is String -> v.split(',', ' ').map(String::trim).filter(String::isNotBlank); else -> emptyList() }
}.toSet()
private fun JSONObject.intSet(vararg keys: String): Set<Int> = keys.flatMap { key ->
    when (val v = opt(key)) { is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString()?.toIntOrNull() }; is String -> v.split(',', ' ').mapNotNull(String::toIntOrNull); else -> emptyList() }
}.toSet()
private fun JSONObject.weekdaySet(vararg keys: String): Set<Int> {
    val raw = keys.flatMap { key -> when (val value = opt(key)) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it)?.toString()?.trim()?.lowercase() }
        is String -> value.split(',', ' ').map(String::trim).filter(String::isNotBlank).map { it.lowercase() }
        else -> emptyList()
    } }
    return raw.mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } ?: weekdayNumber(it).takeIf { day -> day in 1..7 } }.toSet()
}
private fun pathPart(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

/**
 * 反代（Lucky/nginx）超时回的是给浏览器看的一整页 HTML，它跟在 "HTTP 502: " 后面
 * 一起进了异常消息。这页东西对用户没有意义，还会把整屏撑坏，所以先剥掉再判。
 */
internal fun stripMarkupForDisplay(raw: String): String =
    raw.substringBefore('<').trim().trimEnd(':', ' ').trim()

private val httpStatusInText = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)

/** OkHttp 的自定义 DNS 解析不出来时，异常原文是「CustomDns@… returned no addresses for …」。 */
private val unresolvedHostText = Regex(
    "returned no addresses|unable to resolve host|name or service not known|no host found",
    RegexOption.IGNORE_CASE
)

internal fun Throwable.isUnresolvedHubHost(): Boolean =
    unresolvedHostText.containsMatchIn(message.orEmpty())

/** 值得「不报错、过几秒自己再试一次」的两种失败：路由器还在处理，和 DNS 打嗝。 */
internal fun Throwable.needsASilentRetry(): Boolean =
    this is ChildGuardPendingException || isUnresolvedHubHost()

internal fun Throwable.userMessage(): String {
    val raw = stripMarkupForDisplay(message.orEmpty())
    val lower = raw.lowercase()
    val status = (this as? HubHttpException)?.statusCode
        ?: httpStatusInText.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return when {
        isUnresolvedHubHost() -> "无法解析 Hub 域名，请检查手机网络或 DNS"
        status in 502..504 || "bad gateway" in lower || "gateway timeout" in lower ->
            "Hub 网关无响应，路由器可能还在处理，稍后自动重试"
        status == 500 || "internal server error" in lower -> "Hub 处理失败，请稍后重试"
        status == 404 || "not found" in lower -> "Hub 上找不到这台设备的守护数据"
        "timeout" in lower || "timed out" in lower -> "路由器响应超时，请检查路由器连接"
        status == 401 || "unauthorized" in lower || "bad hook token" in lower -> "身份凭证已失效，请重新连接 Hub"
        "connection refused" in lower || "failed to connect" in lower -> "无法连接 Hub，请检查网络"
        "stale command" in lower || "delivery timeout" in lower -> "路由器响应超时，请重试"
        // OkHttp 在连接被中途掐断时给的是这些英文（真机 2026-09-20 截图里就是
        // 「Software caused connection abort」「connection closed」原样糊在横幅上）。
        // 这类中断绝大多数发生在路由器还在写配置的窗口里，稍后自己会好。
        "software caused connection abort" in lower || "connection closed" in lower ||
            "connection reset" in lower || "broken pipe" in lower || "socket closed" in lower ||
            "stream was reset" in lower || "canceled" in lower ->
            "和 Hub 的连接中断，路由器可能还在处理，稍后自动重试"
        "invalid plan id" in lower -> "计划标识无效"
        "invalid device uid" in lower -> "设备标识无效"
        "no router" in lower -> "未检测到关联路由器"
        raw.isBlank() -> "儿童守护请求失败"
        // 兜底也只说人话：英文异常原文对家长没有信息量，还会把横幅撑成一行代码。
        else -> if (raw.any { it.code > 127 }) raw.take(80) else "儿童守护请求失败，请稍后重试"
    }
}

internal fun childGuardDeviceKey(value: String): String {
    val trimmed = value.trim()
    val compact = trimmed.replace(Regex("[:.\\-\\s]"), "")
    return if (compact.length in setOf(12, 32) && compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        if (compact.length == 32) compact.uppercase() else compact.lowercase()
    } else {
        trimmed
    }
}

internal fun sameChildGuardDevice(left: String, right: String): Boolean =
    childGuardDeviceKey(left).equals(childGuardDeviceKey(right), ignoreCase = true)

/** 中继在拿不到身份信息时回的就是这些占位串 —— 它们不是名字。 */
private val childGuardPlaceholderNames = listOf("受守护设备", "受保护设备", "LabProbe 设备", "未知设备")

/**
 * 设备名的最后一道，绝不把 32 位 UID 显示成名字：用户报的「设备名经常变成一长串
 * 字符」就是这条回退 —— 路由器那侧的名字偶尔是占位串，被拒绝后原代码拿 uid 顶上。
 * 有 MAC 就用它的尾号做区分，一个都没有才说「未命名设备」。
 */
internal fun childGuardDisplayName(
    candidates: List<String>,
    uid: String,
    macs: Set<String> = emptySet()
): String {
    val tail = macs.firstOrNull { it.length >= 5 }?.uppercase()?.takeLast(5)
    return childGuardRealName(candidates, uid)
        .ifBlank { if (tail != null) "未命名设备 · $tail" else "未命名设备" }
}

/** 路由器给的名字里第一个「像名字」的；一个都没有就回空串，由调用方决定怎么补。 */
internal fun childGuardRealName(candidates: List<String>, uid: String): String =
    candidates.map(String::trim).firstOrNull { candidate ->
        candidate.isNotBlank() &&
            childGuardPlaceholderNames.none { it.equals(candidate, ignoreCase = true) } &&
            !sameChildGuardDevice(candidate, uid)
    }.orEmpty()

internal fun ProtectedDeviceSummary.matchesChildGuardDevice(value: String): Boolean =
    sameChildGuardDevice(deviceId, value) || macAddresses.any { sameChildGuardDevice(it, value) }

/** Preview-only repository: it starts empty and only ever carries what the caller typed in. */
object FakeChildInternetRepository : ChildInternetRepository {
    override var state by mutableStateOf(
        ChildInternetOverviewState(false, emptyList(), ChildGuardCapabilities(true, true), loading = true)
    ); private set
    override fun refresh() = Unit
    override fun refreshOverview() = Unit
    override fun hydrateFromCache() = Unit
    override fun ensureDevice(deviceId: String, name: String, iconKey: String, accentArgb: Int) {
        if (state.devices.none { it.summary.deviceId == deviceId }) state = state.copy(devices = listOf(mockDevice(deviceId, name, iconKey, accentArgb)) + state.devices)
    }
    override fun setMasterEnabled(enabled: Boolean) { state = state.copy(masterEnabled = enabled) }
    override fun setDeviceBlocked(deviceId: String, blocked: Boolean, durationMinutes: Int?, onResult: (Result<Unit>) -> Unit) { state = state.copy(devices = state.devices.map { if (it.summary.deviceId == deviceId) it.copy(summary = it.summary.copy(status = if (blocked) GuardStatus.BLOCKED else GuardStatus.GUARDED)) else it }); onResult(Result.success(Unit)) }
    override fun setDevicePass(deviceId: String, preset: String, onResult: (Result<Unit>) -> Unit) { state = state.copy(devices = state.devices.map { if (it.summary.deviceId == deviceId) it.copy(summary = it.summary.copy(status = GuardStatus.GUARDED)) else it }); onResult(Result.success(Unit)) }
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
            state = state.copy(devices = state.devices + mockDevice("candidate-$mac", name.ifBlank { "新设备" }, "phone", 0xFF2563EB.toInt()))
        }
        onResult(Result.success(Unit))
    }
    override fun removeGuardDevice(uid: String, onResult: (Result<Unit>) -> Unit) {
        state = state.copy(devices = state.devices.filterNot { it.summary.deviceId == uid })
        onResult(Result.success(Unit))
    }
    override fun loadUsageReport(deviceId: String, onResult: (Result<Unit>) -> Unit) = onResult(Result.success(Unit))
    override fun loadUsageForDate(deviceId: String, date: String, onResult: (Result<Unit>) -> Unit) =
        onResult(Result.success(Unit))
}

/** 乐观插入的设备只有身份字段：时长、深夜分钟、流量、提醒一律留空，等 Hub 的真实统计覆盖。 */
private fun mockDevice(id: String, name: String, icon: String, color: Int): ChildInternetDeviceState =
    ChildInternetDeviceState(
        summary = ProtectedDeviceSummary(id, name, icon, color, GuardStatus.UNRESTRICTED,
            appManagementSupported = true, experimentalAppControl = isExperimentalAppControlDevice(icon)),
        plan = DeviceGuardPlan(categories = childInternetCatalogCategories())
    )

/**
 * 可选应用清单来自路由器特征库的快照（`RdpiCatalogGenerated.kt`，由
 * `tools/gen_rdpi_catalog.py` 从 /usr/share/ndpi/db.default.json 生成）。
 * 只有特征库认得出的应用才可能被封，手写名单一定漏 —— 之前就是这样漏了
 * 抖音系列、QQ音乐和后来新增的米家那一批。
 */
internal fun childInternetCatalogCategories(allowedRdpiIds: Set<String>? = null, allowedAppIds: Set<String> = emptySet()) =
    rdpiCatalogCategories.map { (id, label) -> catalogCategory(id, label, allowedRdpiIds, allowedAppIds) }

private fun catalogCategory(id: String, label: String, allowed: Set<String>?, appIds: Set<String>): AppCategoryPlan {
    val baseEnabled = id in setOf("education", "media", "tools")
    val apps = rdpiCatalogApps.filter { it.categoryId == id }.mapIndexed { i, entry ->
        // 条目 id 用主特征编号而不是位置：目录会随固件增删，位置会变，编号不会。
        val selected = when {
            allowed == null -> baseEnabled || i < 2
            entry.name in appIds -> true
            else -> entry.indexes.any { it in allowed }
        }
        SelectableAppItem(
            id = entry.indexes.first(), name = entry.name, iconKey = dashboardIconKey(entry.name),
            selected = selected, rdpiIds = entry.indexes, note = entry.note
        )
    }
    return AppCategoryPlan(id, label, if (allowed == null) baseEnabled else apps.any { it.selected }, apps)
}

/** 界面名 -> 该应用全部 RDPI 编号；封禁要一次覆盖主特征和它的派生变体。 */
internal fun childInternetRdpiIds(name: String): Set<String> =
    rdpiCatalogApps.firstOrNull { it.name == name }?.indexes ?: emptySet()
/**
 * RDPI 中文名 -> 图标 key。内置图标包（assets/appicons）覆盖国内应用，
 * 其余回落 Homarr Dashboard Icons CDN（kebab-case 命名），两者都没有时用
 * 内置的"未识别应用图标"兜底，不再出现首字母色块。
 */
/** 派生名后缀（王者荣耀_login、优酷视频_weak_relation…）与主应用共用图标。 */
private val derivedAppNameSuffix = Regex("_(login|gaming|weak_relation|null_relation)$")

private fun dashboardIconKey(rawName: String): String {
    val name = rawName.replace(derivedAppNameSuffix, "")
    return when (name) {
    // 待补图：腾讯课堂 / 转转。
    // 没有内置图就不要写进映射表 —— 见 ChildInternetRepositoryTest.everyMappedIconKeyShipsBundledArtwork。
    "微信" -> "wechat"; "企业微信" -> "wecom"; "微信读书" -> "weread"; "微信视频号" -> "wechat-channels"
    "QQ" -> "qq"; "QQ音乐" -> "qq-music"; "QQ浏览器" -> "qq-browser"; "腾讯会议" -> "tencent-meeting"
    "腾讯视频" -> "tencent-video"; "哔哩哔哩" -> "bilibili";
    "抖音" -> "douyin"; "抖音系列" -> "douyin"; "快手" -> "kuaishou"; "小红书" -> "rednote"
    "爱奇艺" -> "iqiyi"; "优酷" -> "youku"; "优酷视频" -> "youku"; "芒果TV" -> "mango-tv"; "咪咕视频" -> "migu-video"
    "网易云音乐" -> "netease-music"; "酷我音乐" -> "kuwo-music"
    "喜马拉雅" -> "himalaya"; "喜马拉雅儿童" -> "himalaya"
    "百度" -> "baidu"; "百度贴吧" -> "baidu-tieba"; "贴吧" -> "baidu-tieba"; "百度网盘" -> "baidu-netdisk"; "百度地图" -> "baidu-map";
    "高德地图" -> "amap"; "腾讯地图" -> "tencent-map"; "夸克" -> "quark"; "UC浏览器" -> "uc-browser"
    "阿里云盘" -> "aliyunpan"; "迅雷" -> "xunlei"; "菜鸟" -> "cainiao"; "顺丰速运" -> "sf-express"; "顺丰速递" -> "sf-express"
    // 自建特征（rdpi 18-4-3-0）：官方库未明确归属的阿里基础设施流量。
    "阿里CDN" -> "alibaba"
    "微博" -> "weibo"; "知乎" -> "zhihu"; "豆瓣" -> "douban"; "Soul" -> "soul"
    "钉钉" -> "dingtalk"; "飞书" -> "feishu"; "WPS Office" -> "wps-office"
    "淘宝" -> "taobao"; "京东" -> "jingdong"; "拼多多" -> "pinduoduo"; "支付宝" -> "alipay"; "唯品会" -> "vipshop"
    "云闪付" -> "unionpay"
    "美团" -> "meituan"; "饿了么" -> "eleme"; "滴滴出行" -> "didi"
    "闲鱼" -> "goofish"; "得物" -> "dewu"
    "携程旅行" -> "ctrip"; "去哪儿旅行" -> "qunar"; "同程旅行" -> "tongcheng"; "马蜂窝" -> "mafengwo"
    "淘票票" -> "taopiaopiao"; "铁路12306" -> "railway-12306"; "大众点评" -> "dianping"
    // 应用商店走内置图标（用户提供的官方 logo），不依赖 CDN；
    // key 与官方特征库名一一对应。
    "华为应用商店" -> "huawei-app-store"; "小米应用商店" -> "xiaomi-app-store"
    "OPPO应用商店" -> "oppo-store"; "VIVO应用商店" -> "vivo-app-store"; "应用宝" -> "yyb"
    "酷安应用市场" -> "coolapk"; "酷安" -> "coolapk"; "App Store" -> "appstore"
    "Steam" -> "steam"; "Keep" -> "keep"; "虎扑" -> "hupu"; "IT之家" -> "ithome"; "雪球" -> "xueqiu"
    "番茄小说" -> "fanqie-novel"; "米游社" -> "mihoyo-bbs"; "瑞幸咖啡" -> "luckin"
    "王者荣耀" -> "honor-of-kings"; "中国建设银行" -> "ccb"; "今日头条" -> "toutiao"
    "豆包" -> "doubao"; "DeepSeek" -> "deepseek"
    // 儿童上网列表里会出现、但以前没有对应关系的应用：名字一律对齐 RDPI 特征库
    // 里的官方 name（中继按 `_` 截断派生名），否则落到 else 分支会变成中文 key，
    // 加载不到任何图。
    "微信支付" -> "wechat-pay"; "安全教育平台" -> "safety-education"
    "TP-LINK物联" -> "tp-link-iot"; "海尔智家" -> "haier-smart-home"
    "美的美居" -> "midea-meiju"; "小爱同学" -> "xiaoai"; "醒图" -> "xingtu"
    "西瓜视频" -> "xigua-video"; "番茄免费小说" -> "fanqie-novel"; "红果免费短剧" -> "hongguo-shortdrama"
    // 目录改成按特征库全量生成后新补的 logo。名字必须是库里的官方 name：
    // 快手在库里叫「快手系列」，写「快手」就永远匹配不上。
    "快手系列" -> "kuaishou"; "酷狗音乐" -> "kugou-music"; "微视" -> "tencent-weishi"
    "英雄联盟手游" -> "lol-mobile"; "搜狐视频" -> "sohu-video"; "百度翻译" -> "baidu-translate"
    "学习通" -> "xuexitong"; "中国银行" -> "bank-of-china"
    "多邻国" -> "duolingo"; "有道词典" -> "youdao-dict"; "YY" -> "yy"; "全球网测" -> "global-net-test"
    // 2026-09-21 补特征那一批：名字必须和下发到路由器的特征库 name 一字不差，
    // 否则界面上有图、列表里却是另一个应用。
    "UU远程" -> "uu-remote"; "360儿童卫士" -> "qihoo-kids-watch"
    "360智慧生活" -> "qihoo-smart-life"; "亲宝宝" -> "qinbaobao"
    // 「补充图标1」批次：这 11 款都在目录里，之前一张图都没有，全落到未识别图标。
    "TapTap" -> "taptap"; "中国农业银行" -> "abc-bank"; "中国工商银行" -> "icbc"
    "人人视频" -> "renren-video"; "使命召唤手游" -> "call-of-duty-mobile"
    "探探" -> "tantan"; "陌陌" -> "momo"; "花椒直播" -> "huajiao-live"
    "虎牙直播" -> "huya-live"; "苏宁易购" -> "suning"; "魅族应用商店" -> "meizu-app-store"
    "95美女秀" -> "95meinvxiu"; "PP视频" -> "pp-video"; "华为视频" -> "huawei-video"
    "南瓜电影" -> "pumpkin-film"; "瞩目" -> "zumu"
    "三角洲行动" -> "delta-force"; "山姆会员商店" -> "sams-club"; "菜鸟" -> "cainiao"
    "米家" -> "mijia"
    // 仓库里早就带着这些图，但一直没有名字映射，等于白装。
    "Kimi" -> "kimi"; "腾讯文档" -> "tencent-docs"; "金山文档" -> "kdocs"
    "美团外卖" -> "meituan-waimai"; "123云盘" -> "123pan"; "115网盘" -> "115-netdisk"
    // 两个私有云 App：特征库 2026-09-22 才补上（9-232-1-0 / 9-233-1-0），图配套。
    "绿联云" -> "ugreen-nas"; "飞牛私有云" -> "fnos"
    "爱回收" -> "aihuishou"; "不背单词" -> "bubei-danci"; "粉笔" -> "fenbi"
    "幕布" -> "mubu"; "i4Tools" -> "i4tools"; "Epic Games" -> "epic-games"
    "Xbox" -> "xbox"
    else -> name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "missing" }
    }
}
