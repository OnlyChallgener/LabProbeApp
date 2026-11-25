package com.labprobe.app.feature.router.ipv6

import com.labprobe.app.AppPrefs
import com.labprobe.app.RouterControlApi

/** Page-scoped IPv6 repository; deliberately separate from RouterRepository/WSS sync. */
class Ipv6Repository internal constructor(private val api: RouterControlApi) {
    constructor(prefs: AppPrefs) : this(RouterControlApi(prefs))

    suspend fun status(wanId: String? = null): Ipv6Status = api.ipv6Status(wanId)

    suspend fun config(wanId: String? = null): Ipv6Config = api.ipv6Config(wanId)

    suspend fun clients(): List<Dhcpv6Client> = api.dhcpv6Clients()

    suspend fun save(form: Ipv6FormState): Ipv6Config = api.saveIpv6Config(form.toJson())
}
