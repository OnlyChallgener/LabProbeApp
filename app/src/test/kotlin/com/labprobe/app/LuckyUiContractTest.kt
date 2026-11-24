package com.labprobe.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.captureRoboImage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class LuckyUiContractTest {
    @get:Rule val compose = createComposeRule()

    @Test fun certificateResponseRetainsLegacyAccounts() {
        val payload = JSONObject("""
            {"ok":true,"items":[{"id":"cert","name":"NAS"}],"accounts":[{"id":"account","name":"Aliyun","provider":"alidns"}]}
        """.trimIndent()).luckyCertificatePayload()

        assertEquals("cert", payload.first.single().getString("id"))
        assertEquals("account", payload.second!!.single().getString("id"))
        assertNull(JSONObject("""{"ok":true,"items":[]}""").luckyCertificatePayload().second)
    }

    @Test fun legacyAccountsEndpointIsOptional() {
        val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/lucky/nas/accounts") { exchange ->
            val body = """{"ok":false,"error":"method not allowed"}""".toByteArray()
            exchange.sendResponseHeaders(405, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            context.getSharedPreferences(routerWorkspacePreferencesName("lucky-legacy-accounts", ""), android.content.Context.MODE_PRIVATE)
                .edit().putString("token", "test-token").commit()
            val prefs = AppPrefs(context, "lucky-legacy-accounts")
            prefs.hub = "http://127.0.0.1:${server.address.port}"
            assertNull(runBlocking { LuckyApi(prefs).readAccounts("nas") })
        } finally {
            server.stop(0)
        }
    }

    @Test fun openTokenEyeTogglesMaskWithoutChangingInput() {
        compose.setContent { MaterialTheme {
            LuckyConnectionForm("Lucky", "http://nas/be72", "sample-long-test-token", false, false, "", {}, {}, {}, {})
        } }
        fun renderedToken(): String {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.text.text
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertCountEquals(1)
        val masked = renderedToken()
        assertFalse(masked.contains("sample"))
        compose.onNodeWithContentDescription("显示 OpenToken").performClick()
        assertEquals("sample-long-test-token", renderedToken())
        compose.onNodeWithContentDescription("隐藏 OpenToken").performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertCountEquals(1)
        assertEquals(masked, renderedToken())
    }

    @Test fun savedSnapshotAppearsBeforeUnavailableHubResponds() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context, "lucky-snapshot-ui")
        prefs.hubRoot = "http://127.0.0.1:1"
        val snapshot = LuckySnapshotStore(context, prefs.hub, prefs.token)
        snapshot.saveConnections(listOf(JSONObject().put("id", "nas").put("name", "NAS Lucky").put("url", "http://127.0.0.1:16601/be72")))
        val rule = JSONObject("""{"id":"rule","name":"dynv6","tls":true,"port":2186,"enabled":true,"routes":[{"id":"a","domains":["ug.net86.dynv6.net"],"backends":["https://192.168.5.44:9443"],"type":"reverseproxy"},{"id":"b","domains":["fs.net86.dynv6.net"],"backends":["https://192.168.5.152:12769"],"type":"reverseproxy"}]}""")
        snapshot.saveInstance("nas", LuckyInstanceSnapshot(listOf(rule)))
        snapshot.select("nas", 0)
        compose.setContent { MaterialTheme { LuckyManagementScreen(prefs, onBack = {}) } }
        compose.onNodeWithText("https://ug.net86.dynv6.net:2186").assertIsDisplayed()
        compose.onNodeWithText("→ https://192.168.5.44:9443").assertIsDisplayed()
        compose.onNodeWithText("https://fs.net86.dynv6.net:2186").assertIsDisplayed()
        compose.onNodeWithText("→ https://192.168.5.152:12769").assertIsDisplayed()
        compose.onNodeWithText("添加连接").assertDoesNotExist()
        captureScreenRoboImage("build/outputs/roborazzi/lucky-routes-snapshot.png")
    }
    @Test fun disconnectedPageHasOnePrimaryConnectionAction() {
        var connects = 0
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().background(LabV2.BackgroundTop).padding(horizontal = 16.dp, vertical = 10.dp)) {
                LuckyPageHeader(false, {}, {}, {})
                LuckyEmptyConnectionState(false, Modifier.weight(1f)) { connects++ }
            }
        } }
        compose.onAllNodesWithText("连接 Lucky").assertCountEquals(1)
        compose.onNodeWithText("添加连接").performClick()
        assertEquals(1, connects)
        compose.onNodeWithContentDescription("连接设置").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/lucky-disconnected-page.png")
    }
    @Test fun connectionDialogRetainsTestActionAndWrappingMessageInCompactLayout() {
        var tests = 0
        compose.setContent { MaterialTheme {
            LabEditorDialog("连接 Lucky", {}, actions = {
                androidx.compose.material3.OutlinedButton({}, Modifier.weight(1f)) { androidx.compose.material3.Text("取消") }
                androidx.compose.material3.Button({}, Modifier.weight(1f)) { androidx.compose.material3.Text("验证并保存") }
            }) {
                LuckyConnectionForm("Lucky", "https://192.168.5.46:1661/be72", "sample-test-token", false, false,
                    "Lucky 请求未确认，请刷新核对；检查地址、证书及网络", {}, {}, {}, { tests++ })
            }
        } }
        compose.onNodeWithText("测试连接").performClick()
        assertEquals(1, tests)
        compose.onNodeWithText("验证并保存").assertIsDisplayed()
        compose.onNodeWithText("Lucky 请求未确认，请刷新核对；检查地址、证书及网络").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/lucky-connection-compact.png")
    }
    @Test fun compactRuleMenuKeepsActionsInDropdown() {
        var edited = false
        val rule = JSONObject().put("name", "绿联 NAS").put("domain", "nas.example.com").put("backend", "http://192.168.5.46:5666").put("port", 8443).put("tls", true).put("enabled", true)
        compose.setContent { MaterialTheme { Column(Modifier.padding(16.dp)) {
            LuckyRuleCard(rule, false, { edited = true }, {}, {})
        } } }
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onNodeWithContentDescription("规则操作").performClick()
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onNodeWithText("停用").assertDoesNotExist()
        compose.onNodeWithText("删除").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/lucky-rule-menu.png")
        compose.onNodeWithText("编辑").performClick()
        assertTrue(edited)
        compose.onNodeWithContentDescription("规则操作").performClick()
        compose.onNodeWithText("复制").performClick()
        val clipboard = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(luckyRuleCopyText(rule), clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    @Config(qualifiers = "w393dp-h300dp-xhdpi")
    fun subruleSwitchAndMenuOnlyTargetSelectedChild() {
        val rule = JSONObject("""{"name":"NAS","enabled":true,"tls":true,"port":2186,"routes":[{"id":"a","name":"UGREEN","domains":["ug.example.com"],"backends":["http://192.168.5.44:80"],"type":"reverseproxy","enabled":true,"editable":true},{"id":"b","domains":["fs.example.com"],"backends":["http://192.168.5.152:80"],"type":"reverseproxy","enabled":false}]}""")
        var toggled = ""; var edited = ""; var deleted = ""
        compose.setContent { MaterialTheme { Column(Modifier.padding(16.dp)) {
            LuckyRuleCard(rule, false, {}, {}, {}, { edited = it.id }, { toggled = it.id }, { deleted = it.id })
        } } }
        compose.onNodeWithContentDescription("子规则开关 b").assertIsOff().performClick()
        assertEquals("b", toggled)
        compose.onNodeWithContentDescription("子规则开关 a").assertIsOn()
        captureScreenRoboImage("build/outputs/roborazzi/lucky-subrule-spacing.png")
        compose.onNodeWithContentDescription("子规则操作 a").performClick()
        compose.onNodeWithText("复制").performClick()
        val clipboard = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.ClipboardManager::class.java)
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.contains("ug.example.com")); assertFalse(copied.contains("fs.example.com"))
        compose.onNodeWithContentDescription("子规则操作 b").performClick()
        compose.onNodeWithText("编辑").performClick(); assertEquals("b", edited)
        compose.onNodeWithContentDescription("子规则操作 a").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNode(isPopup()).captureRoboImage("build/outputs/roborazzi/lucky-subrule-menu.png")
        compose.onNodeWithText("删除").performClick(); assertEquals("a", deleted)
    }

    @Test fun subruleEditorSavesOnlyBasicChildFields() {
        var saved: JSONObject? = null
        compose.setContent { MaterialTheme {
            LuckySubruleEditor(JSONObject("""{"id":"a","name":"NAS","domains":["nas.example.com","backup.example.com"],"backends":["http://192.168.5.44:80"]}"""), false, "", {}, { saved = it })
        } }
        compose.onNodeWithText("监听端口").assertDoesNotExist()
        compose.onNodeWithText("HTTPS").assertDoesNotExist()
        captureScreenRoboImage("build/outputs/roborazzi/lucky-subrule-editor.png")
        compose.onNodeWithText("保存").performClick()
        assertEquals(setOf("name", "domains", "backends"), saved!!.keys().asSequence().toSet())
        assertEquals(2, saved!!.getJSONArray("domains").length())
    }

    @Test fun parentRuleCanOpenSubruleCreationWithoutEditingParent() {
        var creates = 0
        val rule = JSONObject("""{"id":"parent","name":"NAS","enabled":true,"tls":true,"port":2186,"routes":[{"id":"a","domains":["nas.example.com"],"backends":["http://192.168.5.46:80"],"type":"reverseproxy","enabled":true}]}""")
        compose.setContent { MaterialTheme { Column(Modifier.padding(16.dp)) {
            LuckyRuleCard(rule, false, {}, {}, {}, onAddSubrule = { creates++ })
        } } }
        compose.onNodeWithText("添加子规则").performClick()
        assertEquals(1, creates)
    }

    @Test fun editorSourceSelectionPreservesCustomPathAndQuery() {
        var saved: JSONObject? = null
        val rule = JSONObject().put("name", "NAS").put("domain", "nas.example.com").put("backend", "http://192.168.5.46:80/be72?x=1").put("port", 8443).put("tls", true).put("network", "tcp6").put("listenIp", "2001:db8::10")
        val option = FavoriteAddressOption(FavoriteEndpointBinding("stun", "s1", "wan"), "STUN 规则", "绿联", "203.0.113.9:6012", "HTTP")
        compose.setContent { MaterialTheme {
            LuckyRuleEditor(rule, false, "", listOf(option), {}, { saved = it })
        } }
        compose.onNodeWithContentDescription("从已有地址选择").performClick()
        compose.onNodeWithText("STUN 规则").performClick()
        compose.onNodeWithText("绿联 - 203.0.113.9:6012").performClick()
        compose.onNodeWithText("http://203.0.113.9:6012/be72?x=1").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/lucky-basic-editor.png")
        compose.onNodeWithText("IPv6").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals("http://203.0.113.9:6012/be72?x=1", saved!!.getString("backend"))
        assertEquals("tcp6", saved!!.getString("network"))
        assertEquals("2001:db8::10", saved!!.getString("listenIp"))
        assertTrue(saved!!.getString("operationId").isNotBlank())
    }
}
