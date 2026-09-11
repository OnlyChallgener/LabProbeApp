"""Static notification contract checks.

These tests inspect source text only. They intentionally do not execute Kotlin or claim to
exercise Android notification delivery.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
EVENTS = ROOT / "app/src/main/kotlin/com/labprobe/app/EventNotifications.kt"
CERTIFICATES = ROOT / "app/src/main/kotlin/com/labprobe/app/CertificateExpiry.kt"
CI = ROOT / ".github/workflows/ci.yml"


def section(source: str, start: str, end: str) -> str:
    start_at = source.index(start)
    end_at = source.index(end, start_at)
    return source[start_at:end_at]


class NotificationContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.events = EVENTS.read_text(encoding="utf-8")
        cls.certificates = CERTIFICATES.read_text(encoding="utf-8")
        cls.ci = CI.read_text(encoding="utf-8")
        cls.notify_events = section(cls.events, "fun notifyNewEvents(", "private fun eventNotificationRequestCode")
        cls.cert_notify = section(cls.certificates, "private fun notify(", "@Composable")

    def require(self, pattern: str, source: str, message: str, flags: int = 0):
        self.assertIsNotNone(re.search(pattern, source, flags), message)

    def test_event_api_accepts_hub_scope_and_baseline_mode(self):
        self.require(
            r"fun\s+notifyNewEvents\(\s*context:\s*Context,\s*events:\s*List<EventItem>,\s*"
            r"hubIdentity:\s*String\s*=\s*\"\",\s*silentBaseline:\s*Boolean\s*=\s*false",
            self.events,
            "event notifier must expose the scoped baseline contract",
            re.S,
        )

    def test_event_seen_state_is_scoped_by_digest_and_bounded_without_reset(self):
        self.require(r"MessageDigest\.getInstance\(\"SHA-256\"\)", self.events, "hub scope must be digested")
        self.require(r"EVENT_SEEN_MAX_KEYS\s*=\s*2000", self.events, "seen state must have a bounded policy")
        self.require(r"takeLast\(limit\)", self.events, "bounded pruning must retain the newest keys")
        self.require(r"private fun eventSeenPreferenceKey\(scopeDigest: String\).*EVENT_SEEN_KEY_PREFIX \+ scopeDigest", self.events, "preference key must include only the scope digest", re.S)
        self.require(r"putString\(eventSeenPreferenceKey\(scopeDigest\)", self.events, "seen state must be stored under the scoped key")
        self.assertNotIn("clear()", self.events, "event initialization must not clear historical seen keys")
        self.assertNotRegex(self.events, r"putString\([^\n]*hubIdentity", "raw hub identity must not be a preference key")

    def test_event_seen_commit_precedes_permission_check_and_notification(self):
        self.require(r"private fun claimEventNotificationBatch", self.events, "new event identities must use a claim helper")
        self.require(r"synchronized\(store\)", self.events, "seen read and claim must be synchronized")
        self.require(r"\.commit\(\)", self.events, "seen claim must use synchronous commit")
        self.require(r"if\s*\(committed\)\s*selection\s*else\s*null", self.events, "notification must require a successful seen commit")
        self.require(r"if\s*\(!selection\.shouldNotify\)\s*return", self.notify_events, "baseline must return quietly")
        commit = self.notify_events.index("claimEventNotificationBatch(")
        permission = self.notify_events.index("POST_NOTIFICATIONS")
        notify = self.notify_events.index("NotificationManagerCompat.from(context).notify(")
        self.assertLess(commit, permission, "permission denial must happen after seen-state commit")
        self.assertLess(commit, notify, "seen-state commit must happen before notification delivery")
        self.assertNotIn(".apply()", self.events, "event seen persistence must not use asynchronous apply")

    def test_event_notifications_are_one_batch_with_unique_events_intent(self):
        self.require(r"internal fun eventNotificationRoute\(events: List<EventItem>\).*device_online.*device_offline.*\"devices\".*\"events\"", self.events, "event routing must use the actual device presence types", re.S)
        self.require(r"internal fun filterEventNotifications\(.*followedDeviceMacs: Set<String>.*cleanMac\(event\.mac\)", self.events, "device presence notifications must be scoped to followed MAC addresses", re.S)
        self.require(r"events\.size == 1.*\"device_detail\"", self.events, "one followed device event must open its detail", re.S)
        self.require(r"val\s+notificationRoute\s*=\s*eventNotificationRoute\(newEvents\)", self.notify_events, "notification route must be selected from the batch")
        self.require(r"val\s+summary\s*=\s*newEvents\.size\s*>\s*1", self.notify_events, "batch size must choose summary mode")
        self.require(r"if\s*\(summary\)\s*setGroup\(EVENT_GROUP_KEY\)\.setGroupSummary\(true\)", self.notify_events, "multi-event batch must be a grouped summary")
        self.require(r"identities\.single\(\)", self.notify_events, "single-event batch must target one event")
        self.require(r"data\s*=\s*Uri\.parse\(\"labprobe://\$notificationRoute/\$\{scopeDigest\}/\$\{sha256Hex\(eventIdentity\)\}\"\)", self.notify_events, "event PendingIntent data must be stable, routed, and hub-scoped")
        self.require(r"putExtra\(\"navigate_route\",\s*notificationRoute\)", self.notify_events, "event PendingIntent must carry the selected allowlisted route")
        self.require(r"putExtra\(\"event_identity\",\s*eventIdentity\)", self.notify_events, "event identity must be carried to MainActivity")
        self.require(r"putExtra\(\"event_hub_key\",\s*scopeDigest\)", self.notify_events, "event hub scope must be carried to MainActivity")
        self.require(r"putExtra\(\"device_mac\",\s*it\)", self.notify_events, "single followed-device notifications must carry the target MAC")
        self.require(r"newEvents\.take\(8\)", self.notify_events, "summary text must have a bounded number of event lines")
        self.require(r"\.notify\(\s*\"labprobe-events-\$scopeDigest\"", self.notify_events, "notification tag must isolate hubs")
        self.assertEqual(1, self.notify_events.count("NotificationManagerCompat.from(context).notify("), "a batch may post only one system notification")
        self.assertIsNone(
            re.search(r"forEach\s*\{[^}]*notify\(", self.notify_events, re.S),
            "event delivery must not post one notification per row",
        )

    def test_app_open_presence_reminder_is_latest_only_hourly_and_shares_realtime_claim(self):
        main = (ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt").read_text(encoding="utf-8")
        self.require(
            r"EVENT_OPEN_REMINDER_COOLDOWN_MS\s*=\s*60L\s*\*\s*60L\s*\*\s*1000L",
            self.events,
            "app-open presence reminders must use an explicit one-hour window",
        )
        self.require(
            r"fun selectLatestFollowedPresenceReminder\(.*filterEventNotifications\(events, followedDeviceMacs\).*"
            r"filter \{ it\.isDevicePresenceEvent\(\) \}.*maxWithOrNull.*elapsed >= cooldownMs",
            self.events,
            "startup reminder policy must choose only the latest followed presence event and enforce cooldown",
            re.S,
        )
        self.require(
            r"private fun claimLatestFollowedPresenceReminder\(.*synchronized\(store\).*"
            r"eventOpenReminderPreferenceKey\(scopeDigest\).*\.commit\(\)",
            self.events,
            "hourly reminder claims must be synchronously persisted per Hub",
            re.S,
        )
        self.require(
            r"fun notifyNewEvents\(.*claimLatestFollowedPresenceReminder\(.*postEventNotification",
            self.events,
            "realtime delivery must claim the same hourly reminder identity before posting",
            re.S,
        )
        self.require(
            r"fun notifyLatestFollowedPresenceOnOpen\(.*claimLatestFollowedPresenceReminder\(.*"
            r"postEventNotification\(context, listOf\(event\), scopeDigest\)",
            self.events,
            "app-open delivery must post one claimed event only",
            re.S,
        )
        self.assertIn("startupPresenceReminderPending = true", main)
        self.assertIn("notifyLatestFollowedPresenceOnOpen", main)
        self.assertIn("if (active && !foregroundActive) startupPresenceReminderPending = true", main)

    def test_certificate_route_is_observed_daily_route_and_keeps_existing_dedupe(self):
        self.require(r"data\s*=\s*Uri\.parse\(\"labprobe://daily/certificate/\$\{Uri\.encode\(item\.id\)\}\"\)", self.cert_notify, "certificate PendingIntent must target the observed daily route")
        self.require(r"putExtra\(\"navigate_route\",\s*\"daily\"\)", self.cert_notify, "certificate route must be allowlisted")
        self.require(r"putExtra\(\"certificate_id\",\s*item\.id\)", self.cert_notify, "certificate identity must be carried to MainActivity")
        self.require(r"val\s+reminderKey\s*=\s*\"\$\{item\.id\}:\$milestone\"", self.certificates, "certificate/day dedupe key must remain intact")
        self.require(r"setPriority\(NotificationCompat\.PRIORITY_HIGH\)", self.cert_notify, "certificate notification priority must remain unchanged")
        self.assertNotIn("tool_certificates", self.certificates, "certificate notifier must not invent an unobserved route")

    def test_ci_runs_static_tests_and_preserves_android_release_work(self):
        self.require(r"codex/wg-stun-operation-sync", self.ci, "CI must run on the requested branch")
        self.require(r"workflow_dispatch:", self.ci, "CI must support manual dispatch")
        self.require(r"actions/setup-python@v5", self.ci, "CI must set up Python")
        self.require(r"python\s+-m\s+unittest\s+discover\s+-s\s+tools\s+-p\s+'test_\*\.py'", self.ci, "CI must discover all scoped Python tests")
        self.require(r"gradle\s+:app:testDebugUnitTest\s+:app:assembleRelease", self.ci, "CI must retain Android tests and release assembly")
        self.require(r"Upload Android test reports[\s\S]*?if:\s*always\(\)", self.ci, "Android reports must upload after failures")

    def test_ai_startup_policy_persists_before_delivery_without_chat_injection(self):
        main = (ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt").read_text(encoding="utf-8")
        api = (ROOT / "app/src/main/kotlin/com/labprobe/app/feature/assistant/AiApi.kt").read_text(encoding="utf-8")
        policy = (ROOT / "app/src/main/kotlin/com/labprobe/app/feature/assistant/AiNotificationPolicy.kt").read_text(encoding="utf-8")
        screens = (ROOT / "app/src/main/kotlin/com/labprobe/app/feature/assistant/AiScreens.kt").read_text(encoding="utf-8")
        poll = section(main, "var baselinePending = true", "DisposableEffect(context)")
        self.assertLess(poll.index("saveLastNotificationId("), poll.index("notifyAssistantMessage("))
        self.assertIn("planAiNotificationBatch(cursor, baselinePending, rows)", poll)
        self.assertIn("maxByOrNull", policy)
        self.assertIn("baselinePending && fresh.isNotEmpty()", policy)
        cursor = section(api, "fun saveLastNotificationId", "private fun notificationCursorKey")
        self.assertIn("maxOf(lastNotificationId", cursor)
        self.assertIn(".commit()", cursor)
        consume = section(screens, "LaunchedEffect(AppNavigator.pendingAiNotice)", "LaunchedEffect(notificationTitle")
        self.assertNotIn("messages.add", consume)
        self.assertNotIn("writeConversation", consume)
        self.assertNotIn("loadingHistory", consume)
        self.assertIn("AiNotificationDetailDialog(", screens)

    def test_notification_intents_are_consumed_and_routes_allowlisted(self):
        main = (ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("setIntent(intent)", main)
        self.assertIn("incoming::removeExtra", main)
        self.assertIn("notificationRoute(incoming.getStringExtra", main)
        self.assertIn("wrongEventHub", main)
        self.assertIn('setOf("events", "devices", "device_detail")', main)
        self.assertIn("AppNavigator.pendingDeviceMac", main)
        self.assertIn('it in setOf("home", "devices", "device_detail"', main)
        self.assertIn("eventNotificationSessionIdentity != notificationIdentity", main)
        self.assertIn("silentBaseline = silentBaseline", main)


if __name__ == "__main__":
    unittest.main()
