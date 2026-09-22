package com.labprobe.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RdpiSignatureValidationTest {
    @Test fun fallbackTemplateIsSafeAndLocallyValid() {
        assertTrue(DEFAULT_FALLBACK_TEMPLATE.contains("game.example.invalid"))
        assertFalse(DEFAULT_FALLBACK_TEMPLATE.contains("ff ff"))
        assertTrue(validateRdpiSignature(DEFAULT_FALLBACK_TEMPLATE).isSuccess)
    }

    @Test fun acceptsOfficialStyleHostAndPayloadRule() {
        val json = """{"app":{"index":"10-1-2-0","name":"微信视频号","rules":[{"protocol":"tcp","hosts":["finderv1.video.qq.com"],"payloads":[{"pos":0,"length":2,"payload":"16 03"}]}]}}"""
        assertTrue(validateRdpiSignature(json).isSuccess)
    }

    @Test fun rejectsProtocolOnlyRule() {
        assertFalse(validateRdpiSignature("""{"index":"999-1-1-0","name":"x","rules":[{"protocol":"tcp"}]}""").isSuccess)
    }

    @Test fun rejectsMalformedPayloadMetadata() {
        assertFalse(validateRdpiSignature("""{"index":"999-1-1-0","name":"x","rules":[{"protocol":"udp","payloads":[{"pos":-1,"length":3,"payload":"ff gg"}]}]}""").isSuccess)
    }

    @Test fun rejectsNonOfficialProtocolButPreservesOfficialMissingProtocol() {
        assertFalse(validateRdpiSignature("""{"index":"999-1-1-0","name":"x","rules":[{"protocol":"any","hosts":["example.com"]}]}""").isSuccess)
        // 官方库里有些规则没有 protocol（WPS Office 就是这样），这种缺字段的要照收。
        assertTrue(validateRdpiSignature("""{"index":"999-1-1-0","name":"x","rules":[{"hosts":["example.com"]}]}""").isSuccess)
    }

    @Test fun rejectsInertHostFormsTheEngineNeverMatches() {
        // 引擎按裸域后缀匹配：带 * 或首尾点的写法永远不会命中。本地检查和 Hub 校验
        // 保持同一条规矩，别让 Studio 说「格式正确」而导入后被静默存成死规则。
        for (bad in listOf("*.example.com", ".example.com", "example.com.")) {
            val json = """{"index":"999-1-1-0","name":"x","rules":[{"protocol":"host","hosts":["$bad"],"payloads":[]}]}"""
            val result = validateRdpiSignature(json)
            assertTrue("应当拒绝 $bad", result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("裸域名") == true)
        }
        assertTrue(validateRdpiSignature(
            """{"index":"999-1-1-0","name":"x","rules":[{"protocol":"host","hosts":["example.com"],"payloads":[]}]}""").isSuccess)
    }

    @Test fun acceptsOfficialHttpAndHostRuleForms() {
        val host = """{"index":"10-1-2-0","name":"视频号","rules":[{"protocol":"host","hosts":["finderv1.video.qq.com"]}]}"""
        val http = """{"index":"10-1-2-0","name":"视频号","rules":[{"protocol":"https-all-bitstream","http-gets":["/finder/"]},{"protocol":"user-agent","user-agents":["MicroMessenger"]}]}"""
        assertTrue(validateRdpiSignature(host).isSuccess)
        assertTrue(validateRdpiSignature(http).isSuccess)
    }

    @Test fun acceptsOfficialShortHexWithinPayloadSpan() {
        val json = """{"index":"999-1-1-0","name":"x","rules":[{"protocol":"tcp","payloads":[{"pos":0,"length":2,"payload":"66"}]}]}"""
        assertTrue(validateRdpiSignature(json).isSuccess)
    }

    @Test fun acceptsAllOfficialProtocolsAndAlternatePayloadMetadata() {
        val protocols = listOf("host", "http-gets", "http-posts", "user-agent", "https-all-bitstream", "tcp", "udp")
        protocols.forEach { protocol ->
            val field = when (protocol) { "host" -> "hosts"; "http-gets" -> "http-gets"; "http-posts" -> "http-posts"; "user-agent" -> "user-agents"; else -> "hosts" }
            assertTrue(validateRdpiSignature("""{"index":"999-1-1-0","name":"x","rules":[{"protocol":"$protocol","$field":["match"]}]}""").isSuccess)
        }
        assertTrue(validateRdpiSignature("""{"index":"999-1-1-0","name":"x","rules":[{"protocol":"udp","payloads":[{"t_pos":0,"t_length":2,"payload":"aa"}]}]}""").isSuccess)
    }

    @Test fun rejectsNullWrongTypesAndDecimals() {
        val invalids = listOf(
            """{"index":null,"name":"x","rules":[{"protocol":"tcp","hosts":["a"]}]}""",
            """{"index":"999-1-1-0","name":"x","rules":[{"protocol":"host","hosts":[null]}]}""",
            """{"index":"999-1-1-0","name":"x","rules":[{"protocol":"tcp","payloads":[{"pos":0.5,"length":2,"payload":"aa"}]}]}""",
            """{"index":"999-1-1-0","name":"x","rules":[{"protocol":"tcp","payloads":[{"pos":0,"length":"2","payload":"aa"}]}]}"""
        )
        invalids.forEach { assertFalse(validateRdpiSignature(it).isSuccess) }
    }

    @Test fun matchesHubFieldsAndLengthBoundaries() {
        assertTrue(validateRdpiSignature("""{"comment":"x","app":{"index":"999-1-1-0","name":"x","note":"n","rules":[{"protocol":"udp","payloads":[{"pos":0,"length":0,"stage":0,"t_pos":0,"t_length":0,"payload":"aa"}]}]}}""").isSuccess)
        assertFalse(validateRdpiSignature("""{"index":"999-1-1-0","name":"x","extra":true,"rules":[{"protocol":"host","hosts":["a"]}]}""").isSuccess)
        assertFalse(validateRdpiSignature("""{"index":"999-1-1-0","name":"x","rules":[{"protocol":"host","hosts":["a"],"unknown":1}]}""").isSuccess)
    }

    @Test fun localOfficialDatabaseIsAcceptedExceptEmptyPlaceholders() {
        val file = File("test/_analysis/extract/rootfs/usr/share/ndpi/db.default.json")
        assumeTrue("Official fixture is not present in this checkout", file.isFile)
        val expectedRejected = mutableListOf<JSONObject>()
        val accepted = mutableListOf<JSONObject>()
        collectOfficialApps(JSONObject(file.readText()), accepted, expectedRejected)
        accepted.forEach { assertTrue("Official rule rejected: $it", validateRdpiSignature(it.toString()).isSuccess) }
        expectedRejected.forEach { assertFalse("Empty placeholder accepted: $it", validateRdpiSignature(it.toString()).isSuccess) }
    }

    private fun collectOfficialApps(node: Any?, accepted: MutableList<JSONObject>, rejected: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val rules = node.optJSONArray("rules")
                if (rules != null) {
                    for (i in 0 until rules.length()) {
                        val rule = rules.optJSONObject(i) ?: continue
                        val wrapper = JSONObject().put("index", "999-1-1-0").put("name", "official").put("rules", JSONArray().put(rule))
                        val hasCondition = listOf("hosts", "payloads", "http-gets", "http-posts", "user-agents", "payload_length").any { rule.optJSONArray(it)?.length()?.let { n -> n > 0 } == true }
                        if (hasCondition) accepted += wrapper else rejected += wrapper
                    }
                }
                val keys = node.keys(); while (keys.hasNext()) collectOfficialApps(node.opt(keys.next()), accepted, rejected)
            }
            is JSONArray -> for (i in 0 until node.length()) collectOfficialApps(node.opt(i), accepted, rejected)
        }
    }
}
