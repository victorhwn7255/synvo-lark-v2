"""Environment parsing and construction for the pinned runner runtime."""

from __future__ import annotations

import os
import shlex
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from .capabilities import CapabilityError, CapabilityPolicy
from .engine import CodexEngine
from .protocol import AppServerClient


@dataclass(frozen=True)
class RunnerSettings:
    enabled: bool
    host: str
    port: int
    codex_command: tuple[str, ...]
    codex_home: Path | None
    runtime_version: str
    allowed_mcp_servers: frozenset[str]

    @classmethod
    def from_environment(cls, environment: Mapping[str, str]) -> "RunnerSettings":
        enabled = cls._boolean(
            environment.get("SYNVO_CODEX_RUNNER_ENABLED", "false")
        )
        host = environment.get("SYNVO_CODEX_RUNNER_HOST", "0.0.0.0")
        try:
            port = int(environment.get("SYNVO_CODEX_RUNNER_PORT", "8090"))
        except ValueError as error:
            raise ValueError("runner port is invalid") from error
        if not 0 <= port <= 65_535:
            raise ValueError("runner port is invalid")
        runtime_version = environment.get(
            "SYNVO_CODEX_RUNTIME_VERSION", "0.148.0"
        )
        if not enabled:
            return cls(
                enabled=False,
                host=host,
                port=port,
                codex_command=(),
                codex_home=None,
                runtime_version=runtime_version,
                allowed_mcp_servers=frozenset(),
            )
        command = tuple(
            shlex.split(
                environment.get(
                    "SYNVO_CODEX_COMMAND",
                    "/opt/codex/node_modules/.bin/codex",
                )
            )
        )
        if not command:
            raise ValueError("Codex command is required")
        codex_home = Path(
            environment.get("SYNVO_CODEX_HOME", "/var/lib/synvo-codex")
        )
        if not codex_home.is_absolute():
            raise ValueError("Codex home must be absolute")
        allowed = frozenset(
            value.strip()
            for value in environment.get("SYNVO_CODEX_MCP_ALLOWLIST", "").split(",")
            if value.strip()
        )
        return cls(
            enabled=True,
            host=host,
            port=port,
            codex_command=command,
            codex_home=codex_home,
            runtime_version=runtime_version,
            allowed_mcp_servers=allowed,
        )

    @staticmethod
    def _boolean(value: str) -> bool:
        normalized = value.strip().lower()
        if normalized == "true":
            return True
        if normalized == "false":
            return False
        raise ValueError("runner enabled flag is invalid")


class RuntimeFactory:
    def __init__(self, settings: RunnerSettings) -> None:
        if not settings.enabled or settings.codex_home is None:
            raise ValueError("runtime factory requires enabled settings")
        self.settings = settings

    def installed_version(self) -> str:
        try:
            completed = subprocess.run(
                [*self.settings.codex_command, "--version"],
                check=False,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                timeout=10,
                env={
                    **os.environ,
                    "CODEX_HOME": str(self.settings.codex_home),
                },
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise CapabilityError("pinned Codex runtime is unavailable") from error
        prefix = "codex-cli "
        output = completed.stdout.strip()
        if completed.returncode != 0 or not output.startswith(prefix):
            raise CapabilityError("Codex runtime version is unreadable")
        version = output[len(prefix) :]
        if version != self.settings.runtime_version:
            raise CapabilityError("runner runtime version mismatch")
        return version

    def verify_sandbox(self) -> None:
        try:
            completed = subprocess.run(
                [*self.settings.codex_command, "sandbox", "--", "true"],
                check=False,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
                env={
                    **os.environ,
                    "CODEX_HOME": str(self.settings.codex_home),
                },
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise CapabilityError("pinned Codex sandbox is unavailable") from error
        if completed.returncode != 0:
            raise CapabilityError("pinned Codex sandbox is unavailable")

    def app_server_command(self, policy: CapabilityPolicy) -> tuple[str, ...]:
        command = [*self.settings.codex_command, "app-server", "--strict-config"]
        for override in policy.launch_feature_arguments():
            command.extend(("-c", override))
        return tuple(command)

    def create_engine(self) -> CodexEngine:
        policy = CapabilityPolicy(
            runtime_version=self.settings.runtime_version,
            required_model=CodexEngine.MODEL,
        )
        installed_version = self.installed_version()
        self.verify_sandbox()
        client = AppServerClient(
            command=self.app_server_command(policy),
            environment={"CODEX_HOME": str(self.settings.codex_home)},
        )
        return CodexEngine(
            client=client,
            installed_version=installed_version,
            capability_policy=policy,
            allowed_mcp_servers=set(self.settings.allowed_mcp_servers),
        )
