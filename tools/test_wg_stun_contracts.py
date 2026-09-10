"""Static regression checks for the WG/STUN operation-sync change.

These tests intentionally inspect source contracts only.  They do not import,
compile, or emulate the Kotlin implementation, so a passing run is evidence
that the guarded source shape is present—not evidence that Android behavior
has executed successfully.  Runtime verification belongs to GitHub Actions.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
KOTLIN_ROOT = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "labprobe" / "app"


def source(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def section(text: str, start: str, end: str) -> str:
    """Return a named source section without parsing or executing Kotlin."""

    start_at = text.find(start)
    if start_at < 0:
        raise AssertionError(f"missing source marker: {start}")
    end_at = text.find(end, start_at + len(start))
    if end_at < 0:
        raise AssertionError(f"missing source end marker: {end}")
    return text[start_at:end_at]


def has_pattern(text: str, pattern: str) -> bool:
    return re.search(pattern, text, flags=re.DOTALL) is not None


class WireGuardStaticContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.client = source("app/src/main/kotlin/com/labprobe/app/WireGuardClient.kt")
        cls.ui = source("app/src/main/kotlin/com/labprobe/app/WireGuardUi.kt")

    def test_stun_binding_uses_gateway_port_and_requires_exact_binding(self) -> None:
        binding = section(self.client, "suspend fun ensureStunBinding", "suspend fun provision")

        self.assertIn("loadServerConfig().listenPort", binding)
        self.assertNotIn("DEFAULT_WIREGUARD_PORT", binding)
        self.assertIn("isWireGuardStunTarget", binding)
        explicit_start = binding.find("profile.endpointBindingId")
        self.assertGreaterEqual(explicit_start, 0, "missing explicit STUN binding branch")
        unbound_start = binding.find("val candidates", explicit_start)
        strict_branch = binding[explicit_start:unbound_start if unbound_start >= 0 else len(binding)]
        self.assertTrue(
            has_pattern(strict_branch, r"(?:require|check|throw)[\s\S]*?(?:it\.id|selected).*?(?:profile\.endpointBindingId|\bid\b)"),
            "explicit STUN binding branch must validate the selected rule by ID",
        )
        self.assertIn("selected.enabled", strict_branch)
        self.assertIn("selected.targetPort == listenPort", strict_branch)
        self.assertIn("isWireGuardStunTarget(selected, routerIp)", strict_branch)
        self.assertNotIn("api.create", strict_branch)
        self.assertNotIn("singleOrNull", strict_branch)
        self.assertIn("api.create", binding, "unbound new profiles may create a new rule")

        bound_rule = section(
            self.client,
            "internal fun boundWireGuardStunRule",
            "data class WireGuardProvisionResult",
        )
        self.assertIn("it.id == profile.endpointBindingId", bound_rule)
        self.assertNotIn("sharedstun", bound_rule.lower())
        self.assertNotIn("publicKey", bound_rule)

    def test_server_config_keeps_manual_endpoint_and_full_revisioned_payload(self) -> None:
        apply_config = section(
            self.client,
            "internal fun applyWireGuardServerConfig(",
            "private val wireGuardProfileStoreLock",
        )
        self.assertRegex(
            apply_config,
            r"MANUAL\s*->\s*profile",
            "manual endpoint settings must not be rewritten by gateway changes",
        )
        self.assertIn("profile.endpointPort", apply_config)

        settings = section(
            self.client,
            "internal fun buildWireGuardServerSettingsPayload(",
            "internal fun wireGuardStunDependents",
        )
        self.assertIn("JSONObject(server.toString())", settings)
        self.assertIn('put("expectedRevision", root.getLong("revision"))', settings)
        self.assertIn('put("listenPort", listenPort)', settings)

        provision = section(
            self.client,
            "internal fun buildWireGuardServerPayload(",
            "internal fun findRouterLanIpv4",
        )
        self.assertIn("JSONObject(existing.toString())", provision)
        self.assertIn('put("expectedRevision", root.optLong("revision", 0L))', provision)

        removal = section(
            self.client,
            "internal fun buildWireGuardProfileRemovalPayload",
            "internal fun isWireGuardServerConfigApplied",
        )
        self.assertIn('row.optString("id")', removal)
        self.assertNotIn('row.optString("publicKey")', removal)
        self.assertNotIn('row.optString("stunRuleId")', removal)
        self.assertNotIn('row.optString("endpointSource")', removal)

        stun_coordinator = section(
            self.client,
            "fun applyStunSnapshot(",
            "internal fun boundWireGuardStunRule",
        )
        self.assertIn("listenPort: Int", stun_coordinator)
        self.assertIn("routerIp: String", stun_coordinator)
        self.assertIn("rule.targetPort != listenPort", stun_coordinator)
        self.assertIn("!isWireGuardStunTarget(rule, routerIp)", stun_coordinator)

    def test_gateway_update_has_configured_sync_method_and_serialized_mutation(self) -> None:
        self.assertRegex(
            self.client,
            r"suspend fun updateServerConfigAndSync\s*\(",
            "gateway changes must expose the configured WG/STUN synchronization path",
        )
        sync = section(
            self.client,
            "suspend fun updateServerConfigAndSync",
            "suspend fun ensureStunBinding",
        )
        self.assertIn("serverMutationMutex.withLock", sync)
        self.assertIn("val before = getServer()", sync)
        self.assertIn("stunApi.update", sync)
        self.assertIn("onProgress", sync)
        self.assertIn("isWireGuardServerConfigApplied", self.client)
        self.assertIn("resolveProfilesForPort: (Int) -> List<WireGuardProfile>", sync)
        self.assertIn("val authoritativeProfiles = resolveProfilesForPort(oldConfig.listenPort)", sync)
        self.assertIn("wireGuardBoundStunIds(before, authoritativeProfiles)", sync)
        self.assertIn("authoritativeProfiles)", sync)
        self.assertIn("previousListenPort = oldConfig.listenPort", sync)

        enable = section(
            self.client,
            "suspend fun enableServerAndAwaitReady",
            "suspend fun updateServerConfig",
        )
        self.assertIn("serverMutationMutex.withLock", enable)

    def test_store_records_server_port_authority_and_applies_provisioned_profile(self) -> None:
        self.assertIn("applyProvisionedProfile", self.client)
        self.assertIn("rememberServerPortAuthority", self.client)

    def test_wireguard_ui_uses_lifetime_operations_and_retains_remote_errors(self) -> None:
        self.assertIn("NetworkOperationRegistry.get(prefs)", self.ui)
        self.assertTrue(
            has_pattern(self.ui, r"(?:networkOperations|operations)\.launch\s*\("),
            "WireGuard writes must use the lifetime operation coordinator",
        )
        self.assertIn("store.markEndpointError", self.ui)
        self.assertIn("配置已保存在本机，但 Agent 同步失败", self.ui)
        self.assertIn("resolveProfilesForPort", self.ui)
        self.assertIn("result.previousListenPort", self.ui)

        delete = section(self.ui, "onDelete = {", "onCopyClientKey")
        save = section(self.ui, "onSave = {", "        )\n    }")
        self.assertIn("operations.launch", delete)
        self.assertIn("operations.launch", save)
        self.assertLess(delete.find("removeAutomaticProfile"), delete.find("store.delete(profile.id)"))
        self.assertIn("throw IllegalStateException", save)


class StunAndFavoriteStaticContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.stun = source("app/src/main/kotlin/com/labprobe/app/StunPenetration.kt")
        cls.favorites = source("app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt")
        cls.network_operations = source("app/src/main/kotlin/com/labprobe/app/NetworkOperation.kt")

    def test_stun_writes_share_lifetime_operations_and_save_merges_returned_rule(self) -> None:
        self.assertIn("NetworkOperationRegistry.get(prefs)", self.stun)
        self.assertTrue(
            has_pattern(self.stun, r"(?:networkOperations|operations)\.launch\s*\("),
            "STUN writes must use the lifetime operation coordinator",
        )

        # A successful create/update must consume the authoritative rule
        # returned by the API before refreshing, so fields not present in the
        # draft (runtime, mapping, and server-generated values) are retained.
        save = section(self.stun, "editor?.let", "historyTarget?.let")
        self.assertTrue(
            has_pattern(save, r"\bval returned\s*=\s*if\s*\(isCreate\)\s*api\.(?:create|update)\("),
            "STUN save must retain the authoritative rule returned by create/update",
        )
        self.assertTrue(
            has_pattern(save, r"snapshot\s*=\s*snapshot\.copy\(\s*rules\s*=.*\breturned\b"),
            "STUN save must merge the returned API rule into the local snapshot",
        )
        self.assertIn("it.id == request.id", save)

    def test_stun_refresh_is_not_a_trylock_drop(self) -> None:
        refresh = section(self.stun, "fun refresh", "val leavePage")
        self.assertNotIn("tryLock", refresh)
        self.assertNotRegex(refresh, r"if\s*\([^\n]*lock[^\n]*\)\s*return@launch")
        self.assertRegex(refresh, r"(?:withLock|\.lock\s*\(\))")

    def test_stun_delete_is_not_optimistic_and_preserves_failure(self) -> None:
        start = self.stun.find("onDelete = {")
        self.assertGreaterEqual(start, 0, "missing STUN delete action")
        end = self.stun.find("\n                    },\n                )", start)
        self.assertGreaterEqual(end, 0, "missing STUN delete action boundary")
        delete = self.stun[start:end]
        api_delete = delete.find("api.delete")
        self.assertGreaterEqual(api_delete, 0)

        # Any local rule/favorite removal must occur only after the remote
        # delete succeeds.  The action may be a direct suspend operation or a
        # callback-based wrapper; the remote call is the ordering boundary.
        for marker in ("removeStunFavorite", "snapshot = snapshot.copy(rules = snapshot.rules.filterNot"):
            position = delete.find(marker)
            if position >= 0:
                self.assertGreater(position, api_delete, marker)
        self.assertTrue(
            "completed.error?.let" in self.stun or has_pattern(delete, r"catch\s*\([^)]*\)[\s\S]*throw"),
            "STUN remote delete failure must remain observable",
        )

    def test_favorite_tombstones_survive_authoritative_reconciliation(self) -> None:
        self.assertIn("dismissedStunRuleIds", self.favorites)
        self.assertIn("parseFavoriteShortcutDocument", self.favorites)
        self.assertIn("serializeFavoriteShortcutDocument", self.favorites)
        self.assertIn("reconcileStunFavoriteItems", self.favorites)
        self.assertIn("dismissedStunRuleIds: Set<String>", self.favorites)
        self.assertRegex(
            self.favorites,
            r"existing\.dismissedStunRuleIds\s*\+\s*dismissedStunRuleIds",
        )
        self.assertIn("rememberDismissal", self.favorites)
        self.assertIn("rememberDismissal = true", self.stun)

    def test_favorite_sync_does_not_turn_unknown_or_failed_lists_into_empty_state(self) -> None:
        refresh = section(self.stun, "fun refresh", "val leavePage")
        self.assertIn("snapshot.rules", refresh)
        self.assertIn("rulesLoaded", refresh)
        self.assertIn("已保留", refresh)
        self.assertNotRegex(refresh, r"catch[\s\S]{0,500}snapshot\s*=\s*StunSnapshot\s*\(")

    def test_stun_refresh_preserves_post_state_and_completed_errors(self) -> None:
        refresh = section(self.stun, "fun refresh", "val leavePage")
        self.assertIn("val acceptsRules = latest.rulesLoaded", refresh)
        self.assertIn("refreshGeneration == mutationGeneration", refresh)
        self.assertIn("operationChanged", refresh)
        self.assertIn("rules = if (acceptsRules) latest.rules else snapshot.rules", refresh)
        self.assertIn("rulesLoaded = if (acceptsRules) true else snapshot.rulesLoaded", refresh)
        self.assertIn("completedVersion > acknowledgedOperationVersion", refresh)
        self.assertIn("?.error?.let(::uiMessageZh).orEmpty()", refresh)

        # An uncertain POST remains recoverable after the list warning, with a
        # deliberate user acknowledgement before the draft can be retried.
        self.assertIn("已核对列表，重新填写", self.stun)
        self.assertIn("uncertainCreateRuleId = null", self.stun)
        self.assertIn("pendingCreateRequest = null", self.stun)

    def test_network_writes_have_cancellation_safe_lifetime_state(self) -> None:
        self.assertIn("SupervisorJob", self.network_operations)
        self.assertIn("MutableStateFlow<NetworkOperationState?>", self.network_operations)
        self.assertIn("CancellationException", self.network_operations)
        self.assertIn("ConcurrentHashMap", self.network_operations)


class RepositoryAndWorkflowStaticContracts(unittest.TestCase):
    def test_no_blocking_network_primitives_in_scoped_sources(self) -> None:
        paths = (
            "WireGuardClient.kt",
            "WireGuardUi.kt",
            "StunPenetration.kt",
            "FavoriteShortcuts.kt",
            "NetworkOperation.kt",
        )
        for name in paths:
            text = (KOTLIN_ROOT / name).read_text(encoding="utf-8")
            self.assertNotRegex(text, r"\brunBlocking\s*\(", name)
            self.assertNotRegex(text, r"\bThread\.sleep\s*\(", name)

    def test_plan_document_is_present_and_describes_static_boundary(self) -> None:
        plan = ROOT / "docs" / "WG_STUN_OPERATION_SYNC_20260910.md"
        self.assertTrue(plan.is_file())
        text = plan.read_text(encoding="utf-8")
        self.assertIn("验证计划", text)
        self.assertIn("Python", text)
        self.assertIn("GitHub", text)
        self.assertIn("不替代 Kotlin 执行", text)

    def test_ci_runs_python_and_preserves_android_release_test_tasks(self) -> None:
        ci = source(".github/workflows/ci.yml")
        self.assertIn("workflow_dispatch:", ci)
        self.assertIn("codex/wg-stun-operation-sync", ci)
        self.assertIn("actions/setup-python@v5", ci)
        self.assertIn("python -m unittest discover -s tools -p 'test_*.py' -v", ci)
        self.assertIn("gradle :app:testDebugUnitTest :app:assembleRelease", ci)
        self.assertIn("actions/upload-artifact@v4", ci)
        self.assertIn("if: always()", ci)
        self.assertIn("app/build/test-results/testDebugUnitTest", ci)
        self.assertIn("app/build/reports/tests/testDebugUnitTest", ci)

    def test_ci_repository_guards_match_current_tracked_layout(self) -> None:
        ci = source(".github/workflows/ci.yml")
        self.assertIn("design.md", ci)
        self.assertIn("ui-material-reference.yml", ci)
        self.assertIn("grep --line-number --fixed-strings 'git push'", ci)
        self.assertIn('[ "$workflow" = ".github/workflows/ci.yml" ] && continue', ci)

        # CI must remain a test/build workflow; release publication belongs to
        # the existing tag workflow and is intentionally not added here.
        self.assertNotIn("gh release", ci)
        self.assertNotIn("softprops/action-gh-release", ci)


if __name__ == "__main__":
    unittest.main()
