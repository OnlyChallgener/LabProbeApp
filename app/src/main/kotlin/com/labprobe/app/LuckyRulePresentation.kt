package com.labprobe.app

import org.json.JSONObject

internal data class LuckyRouteDisplay(val id: String, val domains: List<String>, val backends: List<String>, val type: String,
    val name: String = "", val enabled: Boolean = true, val editable: Boolean = false)

internal fun luckyRouteDisplays(rule: JSONObject): List<LuckyRouteDisplay> {
    fun strings(row: JSONObject, key: String): List<String> = row.optJSONArray(key)?.let { a ->
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }.orEmpty()
    val routes = rule.optJSONArray("routes")
    if (routes != null) return routes.luckyRows().map { r -> LuckyRouteDisplay(r.optString("id"), strings(r, "domains"), strings(r, "backends"), r.optString("type"),
        r.optString("name"), r.optBoolean("enabled", true), r.optBoolean("editable")) }
    // A legacy flat response has lost the association. Never zip independent lists to invent it.
    return listOf(LuckyRouteDisplay("legacy", rule.optString("domain").split(", ").filter(String::isNotBlank),
        if (rule.optBoolean("editable")) listOf(rule.optString("backend")).filter(String::isNotBlank) else emptyList(), "legacy"))
}

internal fun luckyPublicUrl(rule: JSONObject, domain: String): String =
    "${if (rule.optBoolean("tls")) "https" else "http"}://$domain:${rule.optInt("port")}"

internal fun luckyRouteBackendLabel(route: LuckyRouteDisplay): String = when {
    route.type == "legacy" && route.backends.isEmpty() -> "映射关系待刷新"
    route.type in setOf("fileserver", "file", "staticfiles", "filebrowser") -> "文件服务"
    route.type.isNotBlank() && route.type != "reverseproxy" -> "由 Lucky 处理"
    else -> "未设置后端"
}

internal fun luckyRuleCopyText(rule: JSONObject): String = buildString {
    appendLine(rule.optString("name"))
    appendLine(if (rule.optBoolean("enabled")) "已启用" else "已停用")
    luckyRouteDisplays(rule).forEach { route ->
        route.domains.ifEmpty { listOf("未匹配的域名") }.forEach { domain ->
            appendLine(if (domain == "未匹配的域名") domain else luckyPublicUrl(rule, domain))
            if (route.backends.isEmpty()) appendLine("→ ${luckyRouteBackendLabel(route)}")
            else route.backends.forEach { appendLine("→ $it") }
        }
    }
}.trimEnd()

internal fun luckyRouteCopyText(rule: JSONObject, route: LuckyRouteDisplay): String = buildString {
    appendLine(route.name.ifBlank { rule.optString("name") })
    appendLine(if (route.enabled) "已启用" else "已停用")
    route.domains.ifEmpty { listOf("未匹配的域名") }.forEach { domain ->
        appendLine(if (domain == "未匹配的域名") domain else luckyPublicUrl(rule, domain))
        if (route.backends.isEmpty()) appendLine("→ ${luckyRouteBackendLabel(route)}")
        else route.backends.forEach { appendLine("→ $it") }
    }
}.trimEnd()

internal fun luckyCertificateCopyText(cert: JSONObject): String = buildString {
    appendLine(cert.optString("name"))
    cert.optJSONArray("domains")?.let { a -> (0 until a.length()).forEach { appendLine(a.optString(it)) } }
    appendLine("到期：${cert.optString("expires").ifBlank { "待签发" }}")
    appendLine(if (cert.optBoolean("enabled")) "已启用" else "已停用")
    append(if (cert.optBoolean("automatic")) "Lucky 自动续签" else "导入证书")
}
