package com.labprobe.shinyahub

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import android.widget.LinearLayout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class StunAddressNavigationTest {
    private fun click(service: String, endpoint: String, enabled: Boolean = true, fresh: Boolean = true): Pair<String,Intent?> {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val view=HubToolsView(activity,"stun")
        try {
            activity.setContentView(view);shadowOf(Looper.getMainLooper()).idle()
            for ((name,value) in listOf("sn" to "test", "project" to "test"))
                HubToolsView::class.java.getDeclaredField(name).apply{isAccessible=true}.set(view,value)
            val rule=JSONObject().put("id","rule").put("name","Test").put("serviceType",service)
                .put("enabled",enabled).put("actualState","mapped").put("transportProtocol","TCP")
                .put("forwardMode","router_native").put("nativeMappingState","ready")
                .put("runtime",JSONObject().put("mappingFresh",fresh).put("publicEndpoint",endpoint))
            HubToolsView::class.java.getDeclaredMethod("render",JSONObject::class.java).apply{isAccessible=true}
                .invoke(view,JSONObject().put("rules",JSONArray().put(rule)))
            @Suppress("UNCHECKED_CAST") val cards=HubToolsView::class.java.getDeclaredField("ruleCards")
                .apply{isAccessible=true}.get(view) as Map<String,LinearLayout>
            val card=cards.getValue("rule")
            (0 until card.childCount).map{card.getChildAt(it)}.filterIsInstance<TextView>()
                .single{it.text.startsWith("公网地址：")||it.text.startsWith("上次地址：")}.performClick()
            val clip=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return clip.primaryClip!!.getItemAt(0).text.toString() to shadowOf(activity).nextStartedActivity
        } finally {view.dispose();activity.finish()}
    }
    @Test fun httpAddressCopiesFullUrlAndLaunchesBrowser() {
        val (copied,intent)=click("HTTP","203.0.113.5:8080")
        assertEquals("http://203.0.113.5:8080",copied)
        assertEquals(Intent.ACTION_VIEW,intent!!.action)
        assertEquals(copied,intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
    @Test fun httpsIpv6AddressCopiesBracketsAndLaunchesBrowser() {
        val (copied,intent)=click("HTTPS","[2001:db8::5]:8443")
        assertEquals("https://[2001:db8::5]:8443",copied);assertEquals(copied,intent!!.dataString)
    }
    @Test fun stoppedHttpAddressCopiesButDoesNotNavigate() {
        val (copied,intent)=click("HTTP","203.0.113.5:8080",enabled=false)
        assertEquals("http://203.0.113.5:8080",copied);assertNull(intent)
    }
    @Test fun staleHttpsAddressDoesNotNavigate() {
        val (_,intent)=click("HTTPS","203.0.113.5:8443",fresh=false);assertNull(intent)
    }
    @Test fun sshOnlyCopiesOriginalEndpoint() {
        val (copied,intent)=click("SSH","203.0.113.5:22")
        assertEquals("203.0.113.5:22",copied);assertNull(intent)
    }
    @Test fun urlNormalizationAndRejection() {
        assertEquals("https://nas.example.com:8443/a?x=1",HubToolsView.stunWebAddress("HTTPS","http://nas.example.com:8443/a?x=1"))
        assertEquals("https://[2001:db8::5]:8443",HubToolsView.stunWebAddress("HTTPS","2001:db8::5:8443"))
        for (endpoint in listOf("javascript:alert(1)","user:password@example.com:443","example.com:99999","bad host:80"))
            assertEquals("",HubToolsView.stunWebAddress("HTTP",endpoint))
    }
}
