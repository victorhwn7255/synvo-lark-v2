from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

from synvo_runner.capabilities import CapabilityError, CapabilityPolicy
from synvo_runner.runtime import RunnerSettings, RuntimeFactory


FIXTURE = Path(__file__).parent / "fixtures" / "fake_codex.py"


class RunnerSettingsTest(unittest.TestCase):
    def test_disabled_defaults_require_no_command_or_credential_directory(self) -> None:
        settings = RunnerSettings.from_environment({})

        self.assertFalse(settings.enabled)
        self.assertIsNone(settings.codex_home)

    def test_enabled_settings_require_absolute_dedicated_codex_home(self) -> None:
        with self.assertRaises(ValueError):
            RunnerSettings.from_environment(
                {
                    "SYNVO_CODEX_RUNNER_ENABLED": "true",
                    "SYNVO_CODEX_HOME": "relative",
                }
            )


class RuntimeFactoryTest(unittest.TestCase):
    def test_verifies_version_and_builds_stable_only_launch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            settings = RunnerSettings(
                enabled=True,
                host="127.0.0.1",
                port=0,
                codex_command=(sys.executable, str(FIXTURE)),
                codex_home=Path(directory),
                runtime_version="0.148.0",
                allowed_mcp_servers=frozenset({"fixture"}),
            )
            factory = RuntimeFactory(settings)

            self.assertEqual("0.148.0", factory.installed_version())
            command = factory.app_server_command(
                CapabilityPolicy(
                    runtime_version="0.148.0", required_model="gpt-5.6-sol"
                )
            )

            self.assertIn("app-server", command)
            self.assertIn("--strict-config", command)
            self.assertNotIn("--experimental", command)
            self.assertIn("features.network_proxy=false", command)
            self.assertIn("features.browser_use=false", command)

            factory.verify_sandbox()

    def test_version_mismatch_is_a_hard_failure_without_stderr_leak(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            settings = RunnerSettings(
                enabled=True,
                host="127.0.0.1",
                port=0,
                codex_command=(sys.executable, str(FIXTURE)),
                codex_home=Path(directory),
                runtime_version="0.147.0",
                allowed_mcp_servers=frozenset(),
            )
            with self.assertRaises(CapabilityError):
                RuntimeFactory(settings).installed_version()

    def test_sandbox_preflight_fails_closed_without_stderr_leak(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            settings = RunnerSettings(
                enabled=True,
                host="127.0.0.1",
                port=0,
                codex_command=(sys.executable, str(FIXTURE)),
                codex_home=Path(directory),
                runtime_version="0.148.0",
                allowed_mcp_servers=frozenset(),
            )
            previous = os.environ.get("FAKE_CODEX_SANDBOX_ERROR")
            os.environ["FAKE_CODEX_SANDBOX_ERROR"] = "true"
            try:
                with self.assertRaisesRegex(
                    CapabilityError, "pinned Codex sandbox is unavailable"
                ) as failure:
                    RuntimeFactory(settings).verify_sandbox()
            finally:
                if previous is None:
                    os.environ.pop("FAKE_CODEX_SANDBOX_ERROR", None)
                else:
                    os.environ["FAKE_CODEX_SANDBOX_ERROR"] = previous

            self.assertNotIn("sensitive sandbox diagnostic", str(failure.exception))


class RunnerContainerContractTest(unittest.TestCase):
    def test_enabled_overlay_keeps_namespace_exception_hardened(self) -> None:
        repository = Path(__file__).parents[2]
        overlay = (repository / "compose.codex.yaml").read_text(encoding="utf-8")
        dockerfile = (repository / "runner" / "Dockerfile").read_text(
            encoding="utf-8"
        )

        self.assertRegex(overlay, r"(?s)codex-runner:.*?cap_drop:\s*- ALL")
        self.assertIn("no-new-privileges:true", overlay)
        self.assertIn("seccomp=unconfined", overlay)
        self.assertIn("pids_limit: 256", overlay)
        self.assertRegex(
            dockerfile,
            r"FROM node:22-bookworm-slim@sha256:[0-9a-f]{64}",
        )
        self.assertRegex(
            dockerfile,
            r"FROM eclipse-temurin:21-jdk@sha256:[0-9a-f]{64}",
        )
        self.assertRegex(
            dockerfile,
            r"FROM python:3\.13-slim-bookworm@sha256:[0-9a-f]{64}",
        )
        self.assertIn("bubblewrap=0.8.0-2+deb12u1", dockerfile)


if __name__ == "__main__":
    unittest.main()
