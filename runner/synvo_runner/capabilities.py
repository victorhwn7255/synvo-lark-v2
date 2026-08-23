"""Stable-only runtime policy independent of App Server wire records."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable


class CapabilityError(RuntimeError):
    """The installed runtime cannot satisfy the approved capability envelope."""


@dataclass(frozen=True)
class RuntimeFeature:
    name: str
    stage: str
    enabled: bool
    default_enabled: bool


@dataclass(frozen=True)
class CapabilityReport:
    runtime_version: str
    model: str
    enabled_stable_features: tuple[str, ...]
    disabled_below_stable_features: tuple[str, ...]
    retired_runtime_records: tuple[str, ...]


class CapabilityPolicy:
    """Fail-closed gate for one pinned stable App Server runtime."""

    STABLE_STAGE = "stable"
    REMOVED_STAGE = "removed"
    PINNED_REMOVED_FEATURES = frozenset(
        {
            "apply_patch_freeform",
            "apps_mcp_path_override",
            "code_mode_buffered_exec",
            "codex_git_commit",
            "collaboration_modes",
            "elevated_windows_sandbox",
            "enable_fanout",
            "experimental_windows_sandbox",
            "external_migration",
            "image_detail_original",
            "item_ids",
            "js_repl",
            "js_repl_tools_only",
            "multi_agent_mode",
            "plugin_hooks",
            "remote_control",
            "remote_models",
            "request_rule",
            "resize_all_images",
            "responses_websockets",
            "responses_websockets_v2",
            "search_tool",
            "skill_env_var_dependency_prompt",
            "sqlite",
            "steer",
            "terminal_resize_reflow",
            "tool_search",
            "tool_search_always_defer_mcp_tools",
            "tui_app_server",
            "unavailable_dummy_tools",
            "undo",
            "use_linux_sandbox_bwrap",
            "workspace_owner_usage_nudge",
        }
    )
    REQUIRED_STABLE_FEATURES = frozenset(
        {
            "code_mode_host",
            "goals",
            "multi_agent",
            "shell_snapshot",
            "shell_tool",
            "skill_search",
            "tool_call_mcp_elicitation",
            "unified_exec",
        }
    )
    OUT_OF_SCOPE_STABLE_FEATURES = frozenset(
        {
            "apps",
            "browser_use",
            "browser_use_external",
            "browser_use_full_cdp_access",
            "computer_use",
            "fast_mode",
            "hooks",
            "image_generation",
            "in_app_browser",
            "in_app_updates",
            "plugin_sharing",
            "plugins",
            "recommended_plugins",
            "remote_plugin",
            "skill_mcp_dependency_install",
            "tool_suggest",
            "unbounded_connection_retries",
            "view_image",
            "workspace_dependencies",
        }
    )
    EXPLICIT_UNSAFE_FEATURES = frozenset(
        {
            "default_mode_request_user_input",
            "network_proxy",
            "request_permissions_tool",
            "standalone_web_search",
        }
    )

    def __init__(self, *, runtime_version: str, required_model: str) -> None:
        self.runtime_version = runtime_version
        self.required_model = required_model

    def verify(
        self,
        installed_version: str,
        model_ids: Iterable[str],
        features: Iterable[RuntimeFeature],
    ) -> CapabilityReport:
        if installed_version != self.runtime_version:
            raise CapabilityError("runner runtime version mismatch")
        if self.required_model not in set(model_ids):
            raise CapabilityError("required model unavailable")

        rows = tuple(features)
        removed_features = {
            row.name for row in rows if row.stage == self.REMOVED_STAGE
        }
        if removed_features - self.PINNED_REMOVED_FEATURES:
            raise CapabilityError("the pinned runtime feature matrix changed")
        enabled_non_stable = sorted(
            row.name
            for row in rows
            if row.enabled
            and row.stage != self.STABLE_STAGE
            and not (
                row.stage == self.REMOVED_STAGE
                and row.name in self.PINNED_REMOVED_FEATURES
            )
        )
        if enabled_non_stable:
            raise CapabilityError("a non-Stable runtime feature is enabled")

        enabled_excluded = sorted(
            row.name
            for row in rows
            if row.enabled and row.name in self.OUT_OF_SCOPE_STABLE_FEATURES
        )
        if enabled_excluded:
            raise CapabilityError("an out-of-scope stable feature is enabled")

        enabled_stable = {
            row.name
            for row in rows
            if row.enabled and row.stage == self.STABLE_STAGE
        }
        if self.REQUIRED_STABLE_FEATURES - enabled_stable:
            raise CapabilityError("a required Stable runtime feature is unavailable")

        return CapabilityReport(
            runtime_version=installed_version,
            model=self.required_model,
            enabled_stable_features=tuple(sorted(enabled_stable)),
            disabled_below_stable_features=tuple(
                sorted(
                    row.name
                    for row in rows
                    if not row.enabled
                    and row.stage not in {self.STABLE_STAGE, self.REMOVED_STAGE}
                )
            ),
            retired_runtime_records=tuple(sorted(removed_features)),
        )

    def launch_feature_arguments(self) -> tuple[str, ...]:
        return tuple(
            f"features.{name}=false"
            for name in sorted(
                self.OUT_OF_SCOPE_STABLE_FEATURES
                | self.EXPLICIT_UNSAFE_FEATURES
                | self.PINNED_REMOVED_FEATURES
            )
        )
