package com.labprobe.app.feature.router.ipv6

import com.labprobe.app.AppPrefs
import com.labprobe.app.RouterControlApi

/** Page-scoped IPv6 repository; deliberately separate from RouterRepository/WSS sync. */
class Ipv6Repository internal constructor(private val api: RouterControlApi) {
    constructor(prefs: AppPrefs) : this(RouterControlApi(prefs))

    suspend fun status(): Ipv6Status = api.ipv6Status()

    suspend fun config(): Ipv6Config = api.ipv6Config()

    suspend fun clients(): List<Dhcpv6Client> = api.dhcpv6Clients()

    suspend fun save(form: Ipv6FormState): Ipv6Config = api.saveIpv6Config(form.toJson())
}
