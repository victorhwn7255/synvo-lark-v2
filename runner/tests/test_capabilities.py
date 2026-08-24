from __future__ import annotations

import unittest

from synvo_runner.capabilities import (
    CapabilityError,
    CapabilityPolicy,
    RuntimeFeature,
)


class CapabilityPolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.policy = CapabilityPolicy(
            runtime_version="0.148.0",
            required_model="gpt-5.6-sol",
        )

    @staticmethod
    def required_features() -> list[RuntimeFeature]:
        return [
            RuntimeFeature(name, "stable", True, True)
            for name in sorted(CapabilityPolicy.REQUIRED_STABLE_FEATURES)
        ]

    @staticmethod
    def removed_features() -> list[RuntimeFeature]:
        return [
            RuntimeFeature(name, "removed", True, True)
            for name in sorted(CapabilityPolicy.PINNED_REMOVED_FEATURES)
        ]

    def test_accepts_only_required_model_and_pinned_runtime(self) -> None:
        report = self.policy.verify(
            installed_version="0.148.0",
            model_ids=["gpt-5.6-sol"],
            features=[
                *self.required_features(),
                *self.removed_features(),
                RuntimeFeature("network_proxy", "beta", False, False),
                RuntimeFeature(
                    "request_permissions_tool", "underDevelopment", False, False
                ),
            ],
        )

        self.assertEqual("gpt-5.6-sol", report.model)
        self.assertIn("code_mode_host", report.enabled_stable_features)
        self.assertIn("shell_tool", report.enabled_stable_features)
        self.assertIn("network_proxy", report.disabled_below_stable_features)
        self.assertEqual(
            tuple(sorted(CapabilityPolicy.PINNED_REMOVED_FEATURES)),
            report.retired_runtime_records,
        )

    def test_rejects_version_or_model_substitution(self) -> None:
        with self.assertRaises(CapabilityError):
            self.policy.verify("0.149.0", ["gpt-5.6-sol"], [])
        with self.assertRaises(CapabilityError):
            self.policy.verify("0.148.0", ["gpt-5.6"], [])

    def test_rejects_enabled_below_stable_feature(self) -> None:
        with self.assertRaises(CapabilityError):
            self.policy.verify(
                "0.148.0",
                ["gpt-5.6-sol"],
                [RuntimeFeature("network_proxy", "beta", True, False)],
            )

    def test_rejects_enabled_unknown_deprecated_or_unpinned_removed_stage(self) -> None:
        for stage in ("deprecated", "futureStage", "removed"):
            with self.subTest(stage=stage), self.assertRaises(CapabilityError):
                self.policy.verify(
                    "0.148.0",
                    ["gpt-5.6-sol"],
                    [RuntimeFeature("unrecognized_feature", stage, True, False)],
                )

    def test_rejects_missing_required_stable_feature(self) -> None:
        without_goals = [
            feature
            for feature in self.required_features()
            if feature.name != "goals"
        ]

        with self.assertRaises(CapabilityError):
            self.policy.verify("0.148.0", ["gpt-5.6-sol"], without_goals)

    def test_disables_stable_but_out_of_scope_features(self) -> None:
        arguments = self.policy.launch_feature_arguments()

        self.assertIn("features.browser_use=false", arguments)
        self.assertIn("features.computer_use=false", arguments)
        self.assertIn("features.plugins=false", arguments)
        self.assertIn("features.unbounded_connection_retries=false", arguments)
        self.assertIn("features.collaboration_modes=false", arguments)
        self.assertIn("features.tui_app_server=false", arguments)
        self.assertNotIn("features.code_mode_host=false", arguments)


if __name__ == "__main__":
    unittest.main()
