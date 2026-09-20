package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject

internal data class RdpiSignatureValidation(
    val name: String,
    val index: String,
    val ruleCount: Int
)

/** Validates the rule forms present in the router's official RDPI database. */
internal fun validateRdpiSignature(json: String): Result<RdpiSignatureValidation> = runCatching {
    val root = JSONObject(json.trim())
    val app = if (root.has("app")) {
        require(root.fieldNames().all { it in RDPI_ENVELOPE_FIELDS }) { "包含不支持的外层字段" }
        root.optJSONObject("app") ?: throw IllegalArgumentException("app 必须是对象")
    } else root
    require(app.fieldNames().all { it in RDPI_APP_FIELDS }) { "包含不支持的应用字段" }
    val index = app.stringValue("index")
    require(index.matches(Regex("^\\d+-\\d+-\\d+-\\d+$"))) { "索引必须为 X-X-X-X" }
    val name = app.stringValue("name")
    require(name.isNotEmpty()) { "应用名称不能为空" }
    val rules = app.optJSONArray("rules") ?: throw IllegalArgumentException("rules 必须至少包含一条规则")
    require(rules.length() > 0) { "rules 必须至少包含一条规则" }
    for (i in 0 until rules.length()) validateRdpiRule(rules.optJSONObject(i), i)
    RdpiSignatureValidation(name, index, rules.length())
}

private fun validateRdpiRule(rule: JSONObject?, position: Int) {
    requireNotNull(rule) { "规则 ${position + 1} 必须是对象" }
    require(rule.fieldNames().all { it in RDPI_RULE_FIELDS }) { "规则 ${position + 1} 包含不支持字段" }
    val protocol = rule.optString("protocol").trim().lowercase()
    require(protocol.isEmpty() || protocol in RDPI_PROTOCOLS) { "规则 ${position + 1} 的 protocol 不受支持" }
    fun list(field: String): JSONArray { require(!rule.has(field) || rule.optJSONArray(field) != null) { "规则 ${position + 1} 的 $field 必须是列表" }; return rule.optJSONArray(field) ?: JSONArray() }
    val hosts = list("hosts")
    val payloads = list("payloads")
    val httpGets = list("http-gets")
    val httpPosts = list("http-posts")
    val userAgents = list("user-agents")
    val payloadLengths = list("payload_length")
    val portLimits = list("port_limit")
    require(hosts.length() > 0 || payloads.length() > 0 || httpGets.length() > 0 || httpPosts.length() > 0 || userAgents.length() > 0 || payloadLengths.length() > 0) {
        "规则 ${position + 1} 只有 protocol，缺少有效匹配条件"
    }
    for (i in 0 until hosts.length()) {
        require(hosts.stringAt(i).isNotEmpty()) { "规则 ${position + 1} 含空或错误类型的 hosts 条件" }
    }
    for (i in 0 until payloads.length()) validatePayload(payloads.optJSONObject(i), position)
    validateNonEmptyConditions(httpGets, position, "http-gets")
    validateNonEmptyConditions(httpPosts, position, "http-posts")
    validateNonEmptyConditions(userAgents, position, "user-agents")
    validateNonEmptyConditions(payloadLengths, position, "payload_length")
    for (i in 0 until portLimits.length()) {
        val limit = portLimits.optJSONObject(i)
        require(limit != null && limit.strictNonNegativeInt("min") != null && limit.strictNonNegativeInt("max") != null) { "规则 ${position + 1} 含无效 port_limit" }
    }
}

private val RDPI_PROTOCOLS = setOf("tcp", "udp", "host", "http-gets", "http-posts", "user-agent", "https-all-bitstream")
private val RDPI_ENVELOPE_FIELDS = setOf("\$schema", "comment", "app")
private val RDPI_APP_FIELDS = setOf("index", "name", "rules", "note")
private val RDPI_RULE_FIELDS = setOf("protocol", "hosts", "payloads", "payload_length", "http-gets", "http-posts", "user-agents", "note", "notes", "name", "port_limit", "extra_packet")
private val RDPI_PAYLOAD_FIELDS = setOf("payload", "pos", "length", "stage", "note", "t_pos", "t_length")

private fun validateNonEmptyConditions(values: JSONArray, rulePosition: Int, field: String) {
    for (i in 0 until values.length()) {
        val value = values.opt(i)
        when (field) {
            "payload_length" -> require(value is JSONObject && value.strictNonNegativeInt("stage") != null && value.strictNonNegativeInt("length") != null) { "规则 ${rulePosition + 1} 含无效 $field 条件" }
            else -> require(value is String && value.trim().isNotEmpty()) { "规则 ${rulePosition + 1} 含空或错误类型的 $field 条件" }
        }
    }
}

private fun validatePayload(payload: JSONObject?, rulePosition: Int) {
    requireNotNull(payload) { "规则 ${rulePosition + 1} 的 payload 必须是对象" }
    val pos = if (payload.has("pos")) payload.strictNonNegativeInt("pos") ?: -1 else payload.strictNonNegativeInt("t_pos") ?: -1
    require(payload.fieldNames().all { it in RDPI_PAYLOAD_FIELDS }) { "规则 ${rulePosition + 1} 的 payload 包含不支持字段" }
    val length = if (payload.has("length")) payload.strictNonNegativeInt("length") ?: -1 else payload.strictNonNegativeInt("t_length") ?: -1
    require(pos >= 0) { "规则 ${rulePosition + 1} 的 payload pos 必须为非负整数" }
    require(length >= 0) { "规则 ${rulePosition + 1} 的 payload length 必须为非负整数" }
    val hex = payload.stringValue("payload")
    require(hex.isNotEmpty()) { "规则 ${rulePosition + 1} 的 payload 不能为空" }
    val bytes = hex.split(Regex("\\s+")).filter { it.isNotEmpty() }
    require(bytes.all { it.matches(Regex("^[0-9a-fA-F]{2}$")) }) { "规则 ${rulePosition + 1} 的 payload 必须是两位十六进制字节" }
    // Official rules use length as the inspected packet span; the hex fragment may be shorter.
    for (field in listOf("stage", "t_pos", "t_length")) if (payload.has(field)) require(payload.strictNonNegativeInt(field) != null) { "规则 ${rulePosition + 1} 的 payload $field 必须为非负整数" }
}

private fun JSONObject.stringValue(key: String): String = (opt(key) as? String)?.trim().orEmpty()

/** org.json on Android has no keySet(); iterate keys() instead. */
private fun JSONObject.fieldNames(): Set<String> = keys().asSequence().toSet()

private fun JSONArray.stringAt(index: Int): String = (opt(index) as? String)?.trim().orEmpty()

private fun JSONObject.strictNonNegativeInt(key: String): Int? = strictInt(key)?.takeIf { it >= 0 }
private fun JSONObject.strictPositiveInt(key: String): Int? = strictInt(key)?.takeIf { it > 0 }
private fun JSONObject.strictInt(key: String): Int? {
    val raw = opt(key)
    return when (raw) {
        is Int -> raw
        is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }
}
