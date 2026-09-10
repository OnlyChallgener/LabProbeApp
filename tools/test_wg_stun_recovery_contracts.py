"""Static contracts for the bounded WG/STUN recovery UI policy.

These checks deliberately do not compile or execute Android/Kotlin code.  The
Android test/build remains a GitHub-only verification step for this repository.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]


def source(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def section(text: str, start: str, end: str) -> str:
    begin = text.find(start)
    if begin < 0:
        raise AssertionError(f"missing source marker: {start}")
    finish = text.find(end, begin + len(start))
    if finish < 0:
        raise AssertionError(f"missing source end marker: {end}")
    return text[begin:finish]


class NetworkOperationRecoveryContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.operations = source(
            "app/src/main/kotlin/com/labprobe/app/NetworkOperation.kt"
        )

    def test_error_consumption_is_prefix_scoped_and_clears_state(self) -> None:
        consume = section(self.operations, "fun consumeCompletedError", "fun acknowledgeCompletedError")
        self.assertIn("current.running", consume)
        self.assertIn("startsWith(targetPrefix)", consume)
        self.assertIn("current.copy(error = null)", consume)
        self.assertIn("NetworkOperationError", consume)

    def test_operation_state_keeps_errors_until_page_acknowledges_them(self) -> None:
        self.assertRegex(self.operations, r"error\s*=\s*error")
        self.assertIn("acknowledgeCompletedError", self.operations)


class StunRecoveryUiContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.stun = source(
            "app/src/main/kotlin/com/labprobe/app/StunPenetration.kt"
        )
        cls.core = source(
            "app/src/main/kotlin/com/labprobe/app/WireGuardClient.kt"
        )

    def test_stun_errors_are_isolated_from_wg_operations(self) -> None:
        self.assertIn('STUN_OPERATION_PREFIX = "stun:"', self.stun)
        self.assertIn("isStunOperationTarget", self.stun)
        self.assertIn("completedStunError", self.stun)
        self.assertIn("acknowledgeCompletedError(STUN_OPERATION_PREFIX)", self.stun)
        self.assertIn("unrelatedOperationChanged", self.stun)
        self.assertIn("A WG/gateway failure is not a STUN page error", self.stun)

    def test_error_has_user_acknowledgement_and_successful_refresh_path(self) -> None:
        self.assertIn('Text("关闭"', self.stun)
        self.assertIn("isSuccessfulStunRefresh", self.stun)
        self.assertIn("shownStunErrorVersion", self.stun)

    def test_dependency_policy_does_not_use_port_or_name_matching(self) -> None:
        policy = section(self.stun, "internal fun stunWireGuardMutationPolicy", "internal fun isWireGuardManagedStunRule")
        self.assertIn("localProfileNames", policy)
        self.assertIn("remoteProfileNames", policy)
        self.assertNotIn("targetPort", policy)
        self.assertNotIn("serviceType", policy)
        self.assertIn("BLOCK_LOCAL_REFERENCE", policy)
        self.assertIn("CONFIRM_REMOTE_RESIDUE", policy)

    def test_residual_cleanup_requires_explicit_confirmation_callback(self) -> None:
        self.assertIn('"清理残留并继续"', self.stun)
        self.assertIn("cleanupOrphanedStunBinding", self.stun)
        self.assertIn("WireGuardProfileStore(context.applicationContext, prefs).load()", self.stun)
        self.assertIn("pending.ruleId", self.stun)
        self.assertIn("WireGuardRemoteMutationResult.Applied", self.stun)
        self.assertIn("WireGuardRemoteMutationResult.PendingVerification", self.stun)
        self.assertIn("WireGuardRemoteMutationResult.NotSubmitted", self.stun)
        self.assertIn("未修改 STUN 规则", self.stun)
        self.assertIn("stunWireGuardDependencySnapshot", self.stun)

    def test_core_dependency_contract_is_explicit_and_rule_id_scoped(self) -> None:
        self.assertIn("suspend fun loadStunDependencySnapshot", self.core)
        self.assertIn("suspend fun cleanupOrphanedStunBinding", self.core)
        cleanup = section(self.core, "suspend fun cleanupOrphanedStunBinding", "suspend fun transitionAutomaticProfile")
        self.assertIn("ruleId: String", cleanup)
        self.assertIn("localProfiles: List<WireGuardProfile>", cleanup)
        self.assertIn("parseWireGuardStunDependencySnapshot(latest).forRule(exactRuleId)", cleanup)
        self.assertIn("本机仍有 WireGuard 配置引用该穿透", cleanup)
        self.assertNotIn("targetPort", cleanup)
        self.assertNotIn("serviceType", cleanup)

    def test_managed_label_is_id_based(self) -> None:
        managed = section(self.stun, "internal fun isWireGuardManagedStunRule", "internal fun stunResidualConfirmationText")
        self.assertIn("ruleId in localBindingIds + remoteManagedRuleIds", managed)
        self.assertNotIn("targetPort", managed)
        self.assertNotIn("serviceType", managed)
        self.assertIn("WireGuard 自动管理", self.stun)

    def test_remote_only_binding_is_marked_before_user_mutates_rule(self) -> None:
        self.assertIn("loadStunDependencySnapshot().references", self.stun)
        self.assertIn("wireGuardManagedRuleIds = localBindingIds + remoteBindingIds", self.stun)


class WireGuardRecoveryUiContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ui = source("app/src/main/kotlin/com/labprobe/app/WireGuardUi.kt")

    def test_binding_selector_requires_wireguard_service_identity(self) -> None:
        selector = section(
            self.ui,
            "internal fun selectableWireGuardStunRules",
            "internal fun wireGuardServerConfigMatchesDesired",
        )
        self.assertIn('serviceType.equals("WireGuard", ignoreCase = true)', selector)
        self.assertIn("isWireGuardStunTarget", selector)

    def test_uncertain_gateway_submit_preserves_desired_state_for_read_only_reconciliation(self) -> None:
        self.assertIn("catch (pending: WireGuardPendingVerificationException)", self.ui)
        self.assertIn("pendingGatewayConfig = serverConfig.copy", self.ui)
        self.assertIn("wireGuardServerConfigMatchesDesired(state.config, desired)", self.ui)
        self.assertIn("请勿重复提交", self.ui)


if __name__ == "__main__":
    unittest.main()
