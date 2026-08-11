package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

class ServiceAccessModelTest {
    @Test
    fun parsesHttpAndTcpEndpointsWithoutChangingTheirAddress() {
        val https = parseServiceEndpoint("https://nas.example.com:20000/admin")
        assertEquals("https", https?.scheme)
        assertEquals("nas.example.com", https?.host)
        assertEquals(20000, https?.port)

        val tcp = parseServiceEndpoint("tcp://[2409:8a50::10]:22")
        assertEquals("tcp", tcp?.scheme)
        assertEquals("2409:8a50::10", tcp?.host)
        assertEquals(22, tcp?.port)
    }

    @Test
    fun rejectsEndpointWithoutHostOrPort() {
        assertNull(parseServiceEndpoint("https:///admin"))
        assertNull(parseServiceEndpoint("tcp://host"))
    }

    @Test
    fun accessStatusUsesOnlyUserFacingReasons() {
        assertEquals(
            "当前不可达 · 当前网络无 IPv6",
            serviceAccessStatus(ServiceAccessReport(false, reason = "当前网络无法使用 IPv6 远程访问")),
        )
        assertEquals(
            "服务不可达 · 目标端口无响应",
            serviceAccessStatus(ServiceAccessReport(false, reason = "HTTPS 证书校验失败")),
        )
        assertEquals("内网直连", serviceAccessStatus(ServiceAccessReport(true, path = "内网直连")))
    }

    @Test
    fun wildcardListenerIsNotTreatedAsRemoteHost() {
        val wildcard = parseServiceEndpoint("tcp://[::]:20000")
        assertEquals("::", wildcard?.host)
        assertTrue(wildcard?.port == 20000)
        assertTrue(isWildcardServiceEndpoint("tcp://[::]:20000"))
    }

    @Test
    fun tlsWarningRemainsReachableAfterTcpSucceeds() {
        val report = ServiceAccessReport(
            reachable = true,
            path = "澶栫綉璁块棶",
            tcp = "鍙揪",
            https = "璇佷功璀﹀憡",
            reason = "鏈嶅姟鍙闂?路 璇佷功鏍￠獙寮傚父",
        )
        assertEquals("澶栫綉璁块棶", serviceAccessStatus(report))
        assertEquals("鍙揪", report.tcp)
        assertEquals("璇佷功璀﹀憡", report.https)
    }

    @Test
    fun onlyCertificateFailuresBecomeCertificateWarnings() {
        val certificate = SSLHandshakeException("certificate validation failed").apply {
            initCause(CertificateException("expired certificate"))
        }
        assertTrue(isCertificateFailure(certificate))
        assertFalse(isTlsHandshakeFailure(certificate))

        val protocol = SSLProtocolException("unsupported protocol")
        assertFalse(isCertificateFailure(protocol))
        assertTrue(isTlsHandshakeFailure(protocol))
    }

    @Test
    fun udpServicesAreNotMisreportedAsTcpUnreachable() {
        assertEquals(
            "服务未验证",
            serviceAccessStatus(ServiceAccessReport(false, udp = "未验证", reason = "UDP 服务不能通过通用探测确认")),
        )
    }

    @Test
    fun udpServiceAddressesCopyAsHostAndPort() {
        assertEquals(
            "[2409:8a50::53]:53",
            favoriteAddressForCopy("tcp://[2409:8a50::53]:53", "DNS"),
        )
        assertEquals(
            "vpn.example.com:51820",
            favoriteAddressForCopy("tcp://vpn.example.com:51820", "WireGuard"),
        )
    }
}
