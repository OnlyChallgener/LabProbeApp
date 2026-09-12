"""Static contracts for followed-device presence history and its compact UI."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "app/src/main/kotlin/com/labprobe/app/FollowedDevicePresence.kt"
UI = ROOT / "app/src/main/kotlin/com/labprobe/app/FollowedDevicePresenceUi.kt"
DETAIL = ROOT / "app/src/main/kotlin/com/labprobe/app/DeviceDetailV2.kt"
DEVICE_EVENTS = ROOT / "app/src/main/kotlin/com/labprobe/app/DeviceEvents.kt"


class FollowedDevicePresenceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = MODEL.read_text(encoding="utf-8")
        cls.ui = UI.read_text(encoding="utf-8")
        cls.detail = DETAIL.read_text(encoding="utf-8")
        cls.device_events = DEVICE_EVENTS.read_text(encoding="utf-8")

    def test_model_is_followed_mac_scoped_and_reuses_event_normalization(self):
        self.assertIn("device.followedOverride != true", self.model)
        self.assertIn("!isValidMac(deviceMac)", self.model)
        self.assertIn("cleanMac(event.mac) == deviceMac", self.model)
        self.assertIn("normalizeDeviceEvents(", self.model)
        self.assertIn("lastOfflineAtByKey.remove(key)", self.device_events)
        self.assertIn('event.type == "device_online" || event.type == "device_offline"', self.model)

    def test_open_session_requires_current_device_online_state(self):
        self.assertIn("buildPresenceSessions(normalized, now, device.online)", self.model)
        self.assertIn("deviceOnline && !it.isAfter(now)", self.model)

    def test_model_exposes_bounded_chart_windows(self):
        self.assertIn("dailyPresence(sessions, todayDate, 10", self.model)
        self.assertIn("last10Days.takeLast(7)", self.model)
        self.assertIn("(0..23).map", self.model)

    def test_detail_adds_only_the_local_presence_component(self):
        self.assertIn("if (device.followedOverride == true)", self.detail)
        self.assertIn("FollowedDevicePresenceSection(device = device, presence = presence, now = now, zoneId = zoneId)", self.detail)
        self.assertNotIn("HubApi", self.ui)
        self.assertNotIn("RouterRepository", self.ui)

    def test_presence_ui_keeps_chart_opaque_and_typography_tokenized(self):
        self.assertIn("CompactListCard(coreSurface = true)", self.ui)
        self.assertIn("CompactBottomSheet", self.ui)
        self.assertIn("LabTypography", self.ui)
        self.assertNotIn("fontSize =", self.ui)
        self.assertNotIn("labFrostedSurface", self.ui)
        self.assertNotIn("LabV2.Purple", self.ui)
        self.assertNotIn("HorizontalDivider(color = LabV2.Ink", self.ui)


if __name__ == "__main__":
    unittest.main()
