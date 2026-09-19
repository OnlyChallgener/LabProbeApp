"""Contract and behavioral tests for Hub address normalization.

Verifies:
1. 192.168.5.46:58443 -> http://192.168.5.46:58443
2. http://192.168.5.46:58443 -> http://192.168.5.46:58443
3. https://example.com -> https://example.com
4. validateHubTransportAddress uses normalizeHubBaseUrl
5. prefs.hub getter and setter use normalizeHubBaseUrl
6. Settings UI displays simplified address while storing full URL
"""

import ipaddress
from pathlib import Path
import re
import unittest
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
NETWORK_URL_UTILS = ROOT / "app/src/main/kotlin/com/labprobe/app/NetworkUrlUtils.kt"
HUB_SECURITY = ROOT / "app/src/main/kotlin/com/labprobe/app/HubTransportSecurity.kt"
MAIN_ACTIVITY = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
HUB_SECURITY_TEST = ROOT / "app/src/test/kotlin/com/labprobe/app/HubTransportSecurityTest.kt"


def normalize_hub_address_for_display(raw: str) -> str:
    value = raw.strip().rstrip('/')
    if value.lower().startswith("http://"):
        return value[7:].rstrip('/')
    return value


def normalize_hub_base_url(raw: str) -> str:
    value = raw.strip().rstrip('/')
    if not value:
        return ""
    if value.lower().startswith("http://") or value.lower().startswith("https://"):
        return value
    return f"http://{value}"


def is_private_ip(host: str) -> bool:
    try:
        ip = ipaddress.ip_address(host)
        return ip.is_private or ip.is_loopback
    except ValueError:
        return False


def validate_hub_transport_address(raw: str) -> str:
    normalized = normalize_hub_base_url(raw)
    if not normalized:
        return normalized
    uri = urlparse(normalized)
    scheme = (uri.scheme or "").lower()
    host = (uri.hostname or "").lower()
    if scheme == "https":
        return normalized
    if scheme != "http":
        raise ValueError("Hub 仅支持 HTTPS，或局域网内的 HTTP 地址")
    if not host:
        raise ValueError("Hub 地址缺少主机名")
    if host in ("localhost",) or host.endswith((".local", ".lan", ".home")):
        return normalized
    if is_private_ip(host):
        return normalized
    raise ValueError("公网 HTTP Hub 已被阻止，请改用 HTTPS")


class HubAddressNormalizationBehavioralTests(unittest.TestCase):
    def test_user_specified_cases(self):
        # 1. 192.168.5.46:58443 -> http://192.168.5.46:58443
        self.assertEqual("http://192.168.5.46:58443", normalize_hub_base_url("192.168.5.46:58443"))
        self.assertEqual("http://192.168.5.46:58443", validate_hub_transport_address("192.168.5.46:58443"))

        # 2. http://192.168.5.46:58443 -> http://192.168.5.46:58443
        self.assertEqual("http://192.168.5.46:58443", normalize_hub_base_url("http://192.168.5.46:58443"))
        self.assertEqual("http://192.168.5.46:58443", validate_hub_transport_address("http://192.168.5.46:58443"))

        # 3. https://example.com -> https://example.com
        self.assertEqual("https://example.com", normalize_hub_base_url("https://example.com"))
        self.assertEqual("https://example.com", validate_hub_transport_address("https://example.com"))

    def test_display_simplification(self):
        self.assertEqual("192.168.5.46:58443", normalize_hub_address_for_display("192.168.5.46:58443"))
        self.assertEqual("192.168.5.46:58443", normalize_hub_address_for_display("http://192.168.5.46:58443"))
        self.assertEqual("https://example.com", normalize_hub_address_for_display("https://example.com"))

    def test_public_cleartext_rejected(self):
        with self.assertRaises(ValueError):
            validate_hub_transport_address("http://8.8.8.8:58443")
        with self.assertRaises(ValueError):
            validate_hub_transport_address("8.8.8.8:58443")


class HubAddressSourceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.security_src = HUB_SECURITY.read_text(encoding="utf-8")
        cls.main_src = MAIN_ACTIVITY.read_text(encoding="utf-8")
        cls.test_src = HUB_SECURITY_TEST.read_text(encoding="utf-8")

    def test_validate_hub_transport_address_uses_normalize_hub_base_url(self):
        self.assertIn("val normalized = normalizeHubBaseUrl(raw)", self.security_src)
        self.assertNotIn("normalizeHubAddressForDisplay", self.security_src)

    def test_prefs_hub_uses_normalize_hub_base_url(self):
        self.assertRegex(
            self.main_src,
            r'var\s+hub:\s*String\s+get\(\)\s*=\s*normalizeHubBaseUrl\(sp\.getString\("hub",\s*DEFAULT_HUB\)'
        )
        self.assertRegex(
            self.main_src,
            r'set\(v\)\s*=\s*sp\.edit\(\)\.putString\("hub",\s*normalizeHubBaseUrl\(v\)\)\.apply\(\)'
        )

    def test_prefs_sync_hub_uses_normalize_hub_base_url(self):
        self.assertRegex(
            self.main_src,
            r'var\s+syncHub:\s*String\s+get\(\)\s*=\s*normalizeHubBaseUrl\(sp\.getString\("sync_hub_v1",\s*""\)'
        )
        self.assertRegex(
            self.main_src,
            r'set\(v\)\s*=\s*sp\.edit\(\)\.putString\("sync_hub_v1",\s*normalizeHubBaseUrl\(v\)\)\.apply\(\)'
        )

    def test_settings_screen_saves_full_url(self):
        self.assertIn("val cleanHub = normalizeHubBaseUrl(hub)", self.main_src)
        self.assertIn("val displayHub = normalizeHubAddressForDisplay(cleanHub)", self.main_src)
        self.assertIn("prefs.hub = cleanHub", self.main_src)

    def test_unit_test_file_has_user_specified_cases(self):
        self.assertIn('assertEquals("http://192.168.5.46:58443", validateHubTransportAddress("192.168.5.46:58443"))', self.test_src)
        self.assertIn('assertEquals("http://192.168.5.46:58443", validateHubTransportAddress("http://192.168.5.46:58443"))', self.test_src)
        self.assertIn('assertEquals("https://example.com", validateHubTransportAddress("https://example.com"))', self.test_src)


if __name__ == "__main__":
    unittest.main()
