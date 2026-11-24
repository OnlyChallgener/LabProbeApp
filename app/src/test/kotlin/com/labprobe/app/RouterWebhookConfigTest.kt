package com.labprobe.app

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RouterWebhookConfigTest {
    @get:Rule val compose = createComposeRule()

    @Test fun autoUrlPreservesRouterPathAndEncodesHookToken() {
        val url = buildWebhookUrl(defaultWebhookEndpoint("http://[::1]:58443/r/be72"), "hook+token/value")

        assertEquals("http://[::1]:58443/r/be72/hook/lucky?token=hook%2Btoken%2Fvalue", url)
        assertFalse(url.contains("APP_TOKEN"))
    }

    @Test fun customUrlKeepsReverseProxyPathAndOtherQueryParameters() {
        val parsed = parseWebhookUrl("https://hooks.example.com/proxy/lucky?source=lucky&token=old%2Btoken")

        assertEquals("https://hooks.example.com/proxy/lucky?source=lucky", parsed.baseUrl)
        assertEquals("old+token", parsed.token)
        assertEquals(
            "https://hooks.example.com/proxy/lucky?source=lucky&token=new%2Btoken",
            buildWebhookUrl(parsed.baseUrl, "new+token"),
        )
    }

    @Test fun customIpv6UrlIsValidAndMasked() {
        val parsed = parseWebhookUrl("https://[2001:db8::10]:9443/r/office/hook/lucky?token=secret")
        val actual = buildWebhookUrl(parsed.baseUrl, parsed.token)

        assertEquals("https://[2001:db8::10]:9443/r/office/hook/lucky?token=secret", actual)
        assertEquals("https://[2001:db8::10]:9443/r/office/hook/lucky?token=••••••••", maskWebhookUrl(actual))
        assertFalse(maskWebhookUrl(actual).contains("secret"))
    }

    @Test fun invalidCustomUrlsAreRejected() {
        for (value in listOf(
            "ftp://hooks.example.com/lucky",
            "https://user:pass@hooks.example.com/lucky",
            "https://hooks.example.com:70000/lucky",
            "https://hooks.example.com/lucky#fragment",
            "not-a-url",
        )) {
            assertThrows(IllegalArgumentException::class.java) { parseWebhookUrl(value) }
        }
    }

    @Test fun blankHookTokenNeverFallsBackToAnotherCredential() {
        assertEquals("http://hub.example.test/hook/lucky", buildWebhookUrl("http://hub.example.test/hook/lucky", ""))
        assertTrue(parseWebhookUrl("http://hub.example.test/hook/lucky").token.isEmpty())
    }

    @Test fun customAddressIsScopedByWorkspaceAndHub() {
        val context = RuntimeEnvironment.getApplication()
        RouterWorkspaceStore.resetActive()
        val firstHub = "http://hub-one.example.test"
        val secondHub = "http://hub-two.example.test"
        val be72 = AppPrefs(context, "be72")
        RouterWorkspaceStore.bindHubRoot(context, "be72", firstHub)
        be72.webhookCustomEnabled = true
        be72.webhookCustomUrl = "https://hooks.example.com/be72?token=must-not-persist&source=lucky"

        assertEquals("https://hooks.example.com/be72?source=lucky", be72.webhookCustomUrl)
        val be50 = AppPrefs(context, "be50")
        RouterWorkspaceStore.bindHubRoot(context, "be50", firstHub)
        assertFalse(be50.webhookCustomEnabled)
        assertEquals("", be50.webhookCustomUrl)

        RouterWorkspaceStore.bindHubRoot(context, "be72", secondHub)
        assertFalse(be72.webhookCustomEnabled)
        assertEquals("", be72.webhookCustomUrl)
    }

    @Test fun saveAndRestoreDefaultProduceIndependentSettings() {
        val defaultUrl = defaultWebhookEndpoint("http://hub.example.test/r/be72")
        val custom = webhookEndpointSettings(
            true,
            "https://hooks.example.com/proxy?token=pasted%2Btoken",
            "",
            defaultUrl,
        )
        assertEquals("https://hooks.example.com/proxy", custom.baseUrl)
        assertEquals("pasted+token", custom.hookToken)

        val restored = webhookEndpointSettings(false, "", custom.hookToken, defaultUrl)
        assertFalse(restored.customEnabled)
        assertEquals(defaultUrl, restored.baseUrl)
        assertEquals(custom.hookToken, restored.hookToken)
        assertThrows(IllegalArgumentException::class.java) {
            webhookEndpointSettings(false, "", "", defaultUrl)
        }
    }

    @Test fun freshConfigurationContainsNoPersonalNetworkDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context, "fresh-webhook-defaults")
        RouterWorkspaceStore.bindHubRoot(context, "fresh-webhook-defaults", "http://hub.example.test")

        assertEquals("", prefs.tcpHost)
        assertEquals("", prefs.sshHost)
        assertEquals("", prefs.traceHost)
        assertEquals("", PortMapDraft().targetIpv4)
        assertFalse(prefs.webhookCustomEnabled)
        assertEquals("", prefs.webhookCustomUrl)
    }

    @Test fun copyButtonUsesCompleteConfiguredUrl() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context, "webhook-copy-test")
        prefs.hub = "http://hub.example.test"
        compose.setContent {
            MaterialTheme { RouterWebhookScreen(prefs, null, emptyList(), onBack = {}) }
        }

        compose.onNodeWithText("复制链接").performClick()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("http://hub.example.test/r/webhook-copy-test/hook/lucky", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun endpointCardOpensAutoAndCustomEditor() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context, "webhook-ui-test")
        prefs.hub = "http://hub.example.test"
        compose.setContent {
            MaterialTheme { RouterWebhookScreen(prefs, null, emptyList(), onBack = {}) }
        }

        compose.onNodeWithContentDescription("编辑 Webhook 地址").performClick()
        compose.onNodeWithText("自动生成").assertIsDisplayed()
        compose.onNodeWithText("自定义地址").assertIsDisplayed()
        compose.onNodeWithText("HOOK_TOKEN").assertIsDisplayed()
        assertTrue(prefs.hookToken.isBlank())
    }
}
