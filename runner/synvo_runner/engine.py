"""Deep workspace-agent engine built on the private App Server client."""

from __future__ import annotations

import threading
import time
import uuid
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol

from .capabilities import CapabilityPolicy, RuntimeFeature
from .interactions import InteractionRegistry
from .normalization import EventNormalizer, ProtocolIncompatibility, bounded_text
from .protocol import (
    EventBuffer,
    RunnerError,
    RunnerProtocolError,
    RunnerUnavailable,
    ServerRequest,
)


_RESPONSE_DEVELOPER_INSTRUCTIONS = (
    "Use workspace-relative paths in every user-visible response. Never reveal "
    "the runner's absolute workspace path or create local-file links. Present "
    "source references as plain workspace-relative paths, and include a line "
    "number only when it was verified from the workspace. When shell work is "
    "needed, batch naturally related document and data reads, parsing, "
    "calculations, validation, and bounded report changes into the fewest clear "
    "commands practical. Do not combine unrelated actions merely to reduce "
    "interactions, hide a consequential action inside a larger command, "
    "weaken validation, or expand the workspace, network, or permission boundary. "
    "For read-only structured-data validation, prefer direct standard-library "
    "commands. Do not transport validation scripts through base64, create "
    "temporary scripts, or repeatedly retry complex shell quoting. If a command "
    "fails before reading the data, simplify the command once while preserving "
    "the same validation criteria. "
    "A declined command-elevation request does not mean the selected workspace "
    "is read-only. Treat it as an internal tool-choice failure, retry with a "
    "workspace-relative file operation or a simpler command that stays inside "
    "the sandbox, and do not report a permission blocker unless the available "
    "in-boundary mechanisms also fail. "
    "Complete work using tools that stay inside the selected sandbox. If an "
    "action would need additional command authority, use an in-boundary "
    "alternative or report the limitation; do not request expanded command permission. "
    "If file-changing work is denied because the current task sandbox is read-only, "
    "do not claim that an approval or user decision is pending. State that no file "
    "was changed and that the same task must be opened in H5, changed to Full Edit, "
    "and submitted again."
)


class _StreamingWorkspacePathSanitizer:
    """Remove one private path even when App Server splits it across deltas."""

    def __init__(self, workspace_path: str) -> None:
        self._literal = workspace_path.rstrip("/")
        self._pending = ""

    def feed(self, value: str, *, final: bool = False) -> str:
        combined = self._pending + value
        self._pending = ""
        output: list[str] = []
        cursor = 0
        while True:
            match = combined.find(self._literal, cursor)
            if match < 0:
                break
            output.append(combined[cursor:match])
            after_match = match + len(self._literal)
            if after_match == len(combined) and not final:
                self._pending = self._literal
                return "".join(output)
            replacement = (
                "."
                if after_match < len(combined) and combined[after_match] == "/"
                else "workspace root"
            )
            output.append(replacement)
            cursor = after_match

        tail = combined[cursor:]
        if final or not tail:
            output.append(tail)
            return "".join(output)

        max_suffix = min(len(tail), len(self._literal) - 1)
        for suffix_length in range(max_suffix, 0, -1):
            if tail.endswith(self._literal[:suffix_length]):
                self._pending = tail[-suffix_length:]
                output.append(tail[:-suffix_length])
                return "".join(output)
        output.append(tail)
        return "".join(output)


class EngineFailure(RuntimeError):
    pass


class EngineBusy(EngineFailure):
    pass


class RunMode(str, Enum):
    READ_ONLY = "readOnly"
    WORKSPACE_WRITE = "workspaceWrite"


class EngineHealth(str, Enum):
    READY = "ready"
    RECOVERING = "recovering"
    PROTOCOL_INCOMPATIBLE = "protocolIncompatible"
    UNAVAILABLE = "unavailable"


@dataclass(frozen=True)
class Workspace:
    workspace_id: str
    path: str

    def __post_init__(self) -> None:
        if not self.workspace_id or not self.path or not Path(self.path).is_absolute():
            raise ValueError("workspace requires an id and absolute path")


@dataclass(frozen=True)
class EngineTask:
    engine_ref: str
    model: str

    def as_public_dict(self) -> dict[str, str]:
        return {"engineRef": self.engine_ref, "model": self.model}


class EngineClient(Protocol):
    def start(self) -> None: ...
    def close(self) -> None: ...
    def on_notification(self, handler: Any) -> None: ...
    def on_server_request(self, handler: Any) -> None: ...
    def on_failure(self, handler: Any) -> None: ...
    def request(
        self, method: str, params: Mapping[str, Any] | None, **kwargs: Any
    ) -> dict[str, Any]: ...


class Operation:
    _STREAM_KINDS = frozenset(
        {
            "message_delta",
            "plan_delta",
            "reasoning_delta",
            "command_output",
            "file_output",
            "diff",
        }
    )
    _COMPLETION_STREAMS = {
        "message_completed": frozenset({"message_delta"}),
        "plan_completed": frozenset({"plan_delta"}),
        "reasoning_completed": frozenset({"reasoning_delta"}),
        "command_completed": frozenset({"command_output"}),
        "file_change_completed": frozenset({"file_output", "diff"}),
    }

    def __init__(
        self,
        operation_id: str,
        engine_ref: str,
        workspace_id: str,
        workspace_path: str,
        interactions: InteractionRegistry,
        on_terminal: Any,
        *,
        max_events: int,
    ) -> None:
        self.operation_id = operation_id
        self.engine_ref = engine_ref
        self.workspace_id = workspace_id
        self.workspace_path = workspace_path
        self.turn_ref: str | None = None
        self._interactions = interactions
        self._on_terminal = on_terminal
        self._events = EventBuffer(max_events=max_events)
        self._terminal = threading.Event()
        self._state_lock = threading.RLock()
        self._pending_events: list[tuple[str, dict[str, Any], bool, str]] = []
        self._max_pending_events = max_events
        self._sequence = 0
        self._path_sanitizers: dict[
            tuple[str, str | None], _StreamingWorkspacePathSanitizer
        ] = {}
        self.stop_requested = False

    @property
    def terminal(self) -> bool:
        return self._terminal.is_set()

    def bind_turn(self, turn_ref: str) -> None:
        # Notifications can precede the turn/start response on stdout. Keep
        # their order, but publish only the turn confirmed by that response.
        with self._state_lock:
            self.turn_ref = turn_ref
            pending, self._pending_events = self._pending_events, []
            for kind, payload, terminal, source_turn_ref in pending:
                self.publish(kind, payload, terminal=terminal, source_turn_ref=source_turn_ref)

    def publish(
        self, kind: str, payload: Mapping[str, Any], *, terminal: bool = False,
        source_turn_ref: str | None = None,
    ) -> None:
        with self._state_lock:
            if self._terminal.is_set():
                return
            if source_turn_ref is not None:
                if self.turn_ref is None:
                    if len(self._pending_events) < self._max_pending_events:
                        self._pending_events.append((kind, dict(payload), terminal, source_turn_ref))
                        return
                    kind, payload, terminal = "turn_completed", {"status": "protocolIncompatible"}, True
                elif source_turn_ref != self.turn_ref:
                    return

            prepared = self._prepare_events(kind, payload)
            for index, (prepared_kind, prepared_payload) in enumerate(prepared):
                prepared_terminal = terminal and index == len(prepared) - 1
                self._publish_locked(
                    prepared_kind,
                    prepared_payload,
                    terminal=prepared_terminal,
                )
            if terminal:
                self._pending_events.clear()
                self._terminal.set()
        if terminal:
            self._on_terminal(self)

    def _prepare_events(
        self, kind: str, payload: Mapping[str, Any]
    ) -> list[tuple[str, dict[str, Any]]]:
        prepared: list[tuple[str, dict[str, Any]]] = []
        item_ref = payload.get("itemRef")
        safe_item_ref = item_ref if isinstance(item_ref, str) else None

        flush_kinds = self._COMPLETION_STREAMS.get(kind)
        if kind == "turn_completed":
            prepared.extend(self._flush_streams())
        elif flush_kinds:
            prepared.extend(self._flush_streams(flush_kinds, safe_item_ref))

        safe_payload = dict(payload)
        if kind in self._STREAM_KINDS:
            text = safe_payload.get("text")
            if not isinstance(text, str):
                text = ""
            key = (kind, safe_item_ref)
            sanitizer = self._path_sanitizers.setdefault(
                key, _StreamingWorkspacePathSanitizer(self.workspace_path)
            )
            safe_payload["text"] = sanitizer.feed(text)
            if not safe_payload["text"]:
                return prepared
        prepared.append((kind, safe_payload))
        return prepared

    def _flush_streams(
        self,
        kinds: frozenset[str] | None = None,
        item_ref: str | None = None,
    ) -> list[tuple[str, dict[str, Any]]]:
        flushed: list[tuple[str, dict[str, Any]]] = []
        for key in list(self._path_sanitizers):
            kind, stream_item_ref = key
            if kinds is not None and kind not in kinds:
                continue
            if item_ref is not None and stream_item_ref != item_ref:
                continue
            tail = self._path_sanitizers.pop(key).feed("", final=True)
            if tail:
                flushed.append(
                    (
                        kind,
                        {
                            "text": tail,
                            "truncated": False,
                            "itemRef": stream_item_ref,
                        },
                    )
                )
        return flushed

    def _publish_locked(
        self, kind: str, payload: Mapping[str, Any], *, terminal: bool
    ) -> None:
        event = {
            "sequence": self._sequence,
            "kind": kind,
            "payload": dict(payload),
            "terminal": terminal,
        }
        self._sequence += 1
        self._events.publish(event, terminal=terminal)

    def events(self, after_sequence: int) -> list[dict[str, Any]]:
        return self._events.snapshot(after_sequence=after_sequence)

    def wait_events(
        self,
        after_sequence: int,
        stopped: threading.Event,
        *,
        timeout_seconds: float,
    ) -> list[dict[str, Any]]:
        return self._events.wait(
            after_sequence, stopped, timeout_seconds=timeout_seconds
        )

    def wait_terminal(self, *, timeout_seconds: float) -> bool:
        return self._terminal.wait(timeout=timeout_seconds)

    def wait_for_interaction(self, *, timeout_seconds: float) -> dict[str, Any]:
        return self._interactions.wait_for_pending(
            self.operation_id, timeout_seconds=timeout_seconds
        ).as_public_dict()


class CodexEngine:
    """Expose tasks and turns while hiding all App Server protocol mechanics."""

    MODEL = "gpt-5.6-sol"
    MAX_RECENT_OPERATIONS = 50
    DEFAULT_HEALTH_PROBE_INTERVAL_SECONDS = 15.0
    DEFAULT_HEALTH_PROBE_TIMEOUT_SECONDS = 1.0
    DEFAULT_RECOVERY_ATTEMPTS = 3
    DEFAULT_RECOVERY_BACKOFF_SECONDS = (0.25, 1.0, 2.0)
    DEFAULT_RECOVERY_COOLDOWN_SECONDS = 30.0

    def __init__(
        self,
        *,
        client: EngineClient,
        installed_version: str,
        capability_policy: CapabilityPolicy,
        turn_timeout_seconds: float = 1_800,
        interaction_timeout_seconds: float = 300,
        max_events: int = 1_000,
        allowed_mcp_servers: set[str] | None = None,
        client_factory: Callable[[], EngineClient] | None = None,
        health_probe_interval_seconds: float = DEFAULT_HEALTH_PROBE_INTERVAL_SECONDS,
        health_probe_timeout_seconds: float = DEFAULT_HEALTH_PROBE_TIMEOUT_SECONDS,
        recovery_attempts: int = DEFAULT_RECOVERY_ATTEMPTS,
        recovery_backoff_seconds: tuple[float, ...] = DEFAULT_RECOVERY_BACKOFF_SECONDS,
        recovery_cooldown_seconds: float = DEFAULT_RECOVERY_COOLDOWN_SECONDS,
    ) -> None:
        if health_probe_interval_seconds < 0 or health_probe_timeout_seconds <= 0:
            raise ValueError("health probe timing is invalid")
        if recovery_attempts < 1 or len(recovery_backoff_seconds) < recovery_attempts:
            raise ValueError("recovery policy is invalid")
        if any(delay < 0 for delay in recovery_backoff_seconds):
            raise ValueError("recovery backoff is invalid")
        if recovery_cooldown_seconds < 0:
            raise ValueError("recovery cooldown is invalid")
        self._client = client
        self._client_factory = client_factory
        self._installed_version = installed_version
        self._capability_policy = capability_policy
        self._turn_timeout = turn_timeout_seconds
        self._max_events = max_events
        self._allowed_mcp_servers = allowed_mcp_servers
        self._interactions = InteractionRegistry(
            default_timeout_seconds=interaction_timeout_seconds
        )
        self._normalizer = EventNormalizer()
        self._operations: dict[str, Operation] = {}
        self._active: Operation | None = None
        self._lock = threading.Lock()
        self._goal_lock = threading.Lock()
        self._goal_snapshots: dict[str, dict[str, Any]] = {}
        self._capability_snapshot: dict[str, Any] | None = None
        self._closed = False
        self._shutdown = False
        self._health_probe_interval = health_probe_interval_seconds
        self._health_probe_timeout = health_probe_timeout_seconds
        self._recovery_attempts = recovery_attempts
        self._recovery_backoff = recovery_backoff_seconds
        self._recovery_cooldown = recovery_cooldown_seconds
        self._health_state = EngineHealth.UNAVAILABLE
        self._last_protocol_success = 0.0
        self._next_recovery_at = 0.0
        self._state_monitor = threading.Lock()
        self._health_probe_lock = threading.Lock()
        self._recovery_thread: threading.Thread | None = None
        self._shutdown_event = threading.Event()

    def start(self) -> None:
        client = self._client
        self._bind_client(client)
        client.start()
        try:
            snapshot = self._verified_snapshot(client)
        except Exception:
            self._closed = True
            client.close()
            raise
        with self._state_monitor:
            self._capability_snapshot = snapshot
            self._last_protocol_success = time.monotonic()
            self._health_state = EngineHealth.READY

    def _verified_snapshot(self, client: EngineClient) -> dict[str, Any]:
        model_rows = self._paged_rows(
            client, "model/list", {"includeHidden": True}, page_size=100
        )
        model_ids = [
            row.get("id") or row.get("model")
            for row in model_rows
            if isinstance(row.get("id") or row.get("model"), str)
            and (row.get("id") or row.get("model"))
        ]
        selected = next(
            (
                row
                for row in model_rows
                if row.get("id") == self.MODEL or row.get("model") == self.MODEL
            ),
            None,
        )
        feature_rows_list: list[RuntimeFeature] = []
        feature_names: set[str] = set()
        for row in self._paged_rows(
            client, "experimentalFeature/list", {}, page_size=200
        ):
            name = row.get("name")
            stage = row.get("stage")
            enabled = row.get("enabled")
            default_enabled = row.get("defaultEnabled")
            if (
                not isinstance(name, str)
                or not name
                or not isinstance(stage, str)
                or not stage
                or not isinstance(enabled, bool)
                or not isinstance(default_enabled, bool)
                or name in feature_names
            ):
                raise EngineFailure("runtime feature inventory is incompatible")
            feature_names.add(name)
            feature_rows_list.append(
                RuntimeFeature(
                    name=name,
                    stage=stage,
                    enabled=enabled,
                    default_enabled=default_enabled,
                )
            )
        feature_rows = tuple(feature_rows_list)
        report = self._capability_policy.verify(
            self._installed_version, model_ids, feature_rows
        )
        if selected is None:
            raise EngineFailure("required model unavailable")
        efforts = [
            effort.get("reasoningEffort")
            for effort in (selected.get("supportedReasoningEfforts") or [])
            if isinstance(effort, dict)
            and isinstance(effort.get("reasoningEffort"), str)
        ]
        if not efforts:
            raise EngineFailure("required model exposes no reasoning efforts")
        self._verify_mcp_configuration(client)
        return {
            "runtimeVersion": report.runtime_version,
            "model": report.model,
            "reasoningEfforts": efforts,
            "enabledFeatures": list(report.enabled_stable_features),
            "disabledBelowStableFeatures": list(
                report.disabled_below_stable_features
            ),
            "retiredRuntimeRecords": list(report.retired_runtime_records),
        }

    def _paged_rows(
        self,
        client: EngineClient,
        method: str,
        params: Mapping[str, Any],
        *,
        page_size: int,
    ) -> tuple[dict[str, Any], ...]:
        rows: list[dict[str, Any]] = []
        cursor: str | None = None
        seen_cursors: set[str] = set()
        for _page in range(10):
            request_params = {**params, "limit": page_size}
            if cursor is not None:
                request_params["cursor"] = cursor
            result = (
                client.request(method, request_params)
                if client is not None
                else self._request(method, request_params)
            )
            page_rows = result.get("data")
            if not isinstance(page_rows, list) or not all(
                isinstance(row, dict) for row in page_rows
            ):
                raise EngineFailure("runtime inventory is incompatible")
            rows.extend(page_rows)
            next_cursor = result.get("nextCursor")
            if next_cursor is None:
                return tuple(rows)
            if (
                not isinstance(next_cursor, str)
                or not next_cursor
                or next_cursor in seen_cursors
            ):
                raise EngineFailure("runtime inventory is incompatible")
            seen_cursors.add(next_cursor)
            cursor = next_cursor
        raise EngineFailure("runtime inventory exceeds the supported page bound")

    def _verify_mcp_configuration(self, client: EngineClient) -> None:
        allowed = self._allowed_mcp_servers or set()
        cursor: str | None = None
        pages = 0
        while True:
            result = client.request(
                "mcpServerStatus/list",
                {
                    "threadId": None,
                    "detail": "toolsAndAuthOnly",
                    "limit": 100,
                    "cursor": cursor,
                },
            )
            pages += 1
            for row in result.get("data") or []:
                if not isinstance(row, dict) or not isinstance(row.get("name"), str):
                    raise EngineFailure("MCP configuration is incompatible")
                name = row["name"]
                if name not in allowed or any(
                    forbidden in name.lower() for forbidden in ("lark", "feishu")
                ):
                    raise EngineFailure("an MCP server is not allowlisted")
                if row.get("pluginId") is not None:
                    raise EngineFailure("plugin-provided MCP is unavailable")
                tools = row.get("tools")
                if not isinstance(tools, dict):
                    raise EngineFailure("MCP tool metadata is incompatible")
                for tool in tools.values():
                    if not isinstance(tool, dict):
                        raise EngineFailure("MCP tool metadata is incompatible")
                    annotations = tool.get("annotations")
                    metadata = tool.get("_meta")
                    read_only = (
                        isinstance(annotations, dict)
                        and annotations.get("readOnlyHint") is True
                    )
                    guarded_side_effect = (
                        isinstance(metadata, dict)
                        and metadata.get("synvo/approvalBoundary")
                        == "elicitation-before-side-effect"
                    )
                    if not read_only and not guarded_side_effect:
                        raise EngineFailure("an MCP tool has no safe risk classification")
            next_cursor = result.get("nextCursor")
            if next_cursor is None:
                return
            if not isinstance(next_cursor, str) or not next_cursor or pages >= 10:
                raise EngineFailure("MCP inventory is incompatible")
            cursor = next_cursor

    def close(self) -> None:
        with self._state_monitor:
            if self._shutdown:
                return
            self._shutdown = True
            self._closed = True
            self._health_state = EngineHealth.UNAVAILABLE
            client = self._client
            recovery_thread = self._recovery_thread
        self._shutdown_event.set()
        self._terminalize_active("stopped")
        client.close()
        if recovery_thread is not None and recovery_thread is not threading.current_thread():
            recovery_thread.join(timeout=5)

    def ready(self) -> bool:
        return self.health() == EngineHealth.READY.value

    def health(self) -> str:
        with self._state_monitor:
            state = self._health_state
            stale = (
                not self._closed
                and self._capability_snapshot is not None
                and time.monotonic() - self._last_protocol_success
                >= self._health_probe_interval
            )
        if state == EngineHealth.READY and stale:
            self._probe_health()
        elif state != EngineHealth.READY:
            self._start_recovery()
        with self._state_monitor:
            return self._health_state.value

    def _probe_health(self) -> None:
        if not self._health_probe_lock.acquire(blocking=False):
            return
        try:
            with self._state_monitor:
                if self._shutdown or self._closed:
                    return
                client = self._client
            try:
                result = client.request(
                    "model/list",
                    {"includeHidden": False, "limit": 1},
                    timeout_seconds=self._health_probe_timeout,
                )
                if not isinstance(result.get("data"), list):
                    raise RunnerProtocolError("App Server health response is invalid")
            except (RunnerError, EngineFailure) as error:
                self._runtime_failed(client, error)
                return
            self._record_success(client)
        finally:
            self._health_probe_lock.release()

    def _request(
        self,
        method: str,
        params: Mapping[str, Any] | None,
        *,
        timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        with self._state_monitor:
            if self._shutdown or self._closed:
                raise RunnerUnavailable("App Server is recovering")
            client = self._client
        try:
            result = client.request(method, params, timeout_seconds=timeout_seconds)
        except RunnerUnavailable as error:
            self._runtime_failed(client, error)
            raise
        self._record_success(client)
        return result

    def _record_success(self, client: EngineClient) -> None:
        with self._state_monitor:
            if not self._shutdown and self._client is client and not self._closed:
                self._last_protocol_success = time.monotonic()
                self._health_state = EngineHealth.READY

    def _bind_client(self, client: EngineClient) -> None:
        client.on_notification(
            lambda method, params: self._on_notification_from(client, method, params)
        )
        client.on_server_request(
            lambda request: self._on_server_request_from(client, request)
        )
        client.on_failure(lambda error: self._runtime_failed(client, error))

    def _runtime_failed(self, client: EngineClient, error: Exception) -> None:
        with self._state_monitor:
            if self._shutdown or self._client is not client or self._closed:
                return
            self._closed = True
            self._health_state = self._failure_health(error)
        self._terminalize_active("runnerUnavailable")
        self._start_recovery()

    def _terminalize_active(self, status: str) -> None:
        with self._lock:
            operation = self._active
        if operation is not None and not operation.terminal:
            self._interactions.cancel_operation(operation.operation_id)
            operation.publish("turn_completed", {"status": status}, terminal=True)

    def _start_recovery(self) -> None:
        with self._state_monitor:
            if (
                self._shutdown
                or not self._closed
                or self._client_factory is None
                or self._recovery_thread is not None
                or time.monotonic() < self._next_recovery_at
            ):
                return
            self._health_state = EngineHealth.RECOVERING
            recovery_thread = threading.Thread(
                target=self._recover,
                name="codex-app-server-recovery",
                daemon=True,
            )
            self._recovery_thread = recovery_thread
        recovery_thread.start()

    def _recover(self) -> None:
        last_failure: Exception = RunnerUnavailable("App Server recovery failed")
        for attempt in range(self._recovery_attempts):
            if self._shutdown_event.wait(self._recovery_backoff[attempt]):
                break
            candidate: EngineClient | None = None
            try:
                assert self._client_factory is not None
                candidate = self._client_factory()
                self._bind_client(candidate)
                candidate.start()
                snapshot = self._verified_snapshot(candidate)
            except Exception as error:
                last_failure = error
                if candidate is not None:
                    candidate.close()
                continue

            with self._state_monitor:
                shutting_down = self._shutdown
                if shutting_down:
                    previous = None
                else:
                    previous = self._client
                    self._client = candidate
                    self._capability_snapshot = snapshot
                    self._closed = False
                    self._health_state = EngineHealth.READY
                    self._last_protocol_success = time.monotonic()
                    self._next_recovery_at = 0.0
                self._recovery_thread = None
            if shutting_down:
                candidate.close()
                return
            assert previous is not None
            previous.close()
            return

        with self._state_monitor:
            if not self._shutdown:
                self._health_state = self._failure_health(last_failure)
                self._next_recovery_at = (
                    time.monotonic() + self._recovery_cooldown
                )
            self._recovery_thread = None

    @staticmethod
    def _failure_health(error: Exception) -> EngineHealth:
        if isinstance(error, (RunnerProtocolError, EngineFailure)):
            return EngineHealth.PROTOCOL_INCOMPATIBLE
        return EngineHealth.UNAVAILABLE

    def capabilities(self) -> dict[str, Any]:
        if self._capability_snapshot is None:
            raise EngineFailure("engine not initialized")
        return {
            key: list(value) if isinstance(value, list) else value
            for key, value in self._capability_snapshot.items()
        }

    def create_task(self, workspace: Workspace, mode: RunMode) -> EngineTask:
        result = self._request(
            "thread/start",
            {
                "model": self.MODEL,
                "cwd": workspace.path,
                "approvalPolicy": "on-request",
                "approvalsReviewer": "user",
                "developerInstructions": _RESPONSE_DEVELOPER_INSTRUCTIONS,
            },
        )
        return self._task_from_result(result)

    def fork_task(self, engine_ref: str, workspace: Workspace) -> EngineTask:
        result = self._request(
            "thread/fork",
            {
                "threadId": engine_ref,
                "model": self.MODEL,
                "cwd": workspace.path,
                "developerInstructions": _RESPONSE_DEVELOPER_INSTRUCTIONS,
            },
        )
        return self._task_from_result(result)

    def resume_task(self, engine_ref: str, workspace: Workspace) -> EngineTask:
        loaded_result = self._request("thread/loaded/list", None)
        loaded = loaded_result.get("data")
        if not isinstance(loaded, list) or not all(
            isinstance(thread_id, str) for thread_id in loaded
        ):
            raise EngineFailure("loaded task inventory is incompatible")
        if engine_ref in loaded:
            return EngineTask(engine_ref, self.MODEL)

        thread = next(
            (
                row
                for row in self._paged_rows(
                    None,
                    "thread/list",
                    {
                        "sourceKinds": ["appServer"],
                        "archived": False,
                        "cwd": workspace.path,
                    },
                    page_size=100,
                )
                if row.get("id") == engine_ref
            ),
            None,
        )
        if thread is None:
            raise LookupError("engine task is not persisted")
        status = thread.get("status") or {}
        status_type = status.get("type")
        if status_type in {"idle", "active"}:
            return EngineTask(engine_ref, self.MODEL)
        if status_type != "notLoaded":
            raise EngineFailure("engine task state is unavailable")
        result = self._request(
            "thread/resume",
            {
                "threadId": engine_ref,
                "model": self.MODEL,
                "cwd": workspace.path,
                "developerInstructions": _RESPONSE_DEVELOPER_INSTRUCTIONS,
            },
        )
        return self._task_from_result(result)

    def read_task(self, engine_ref: str) -> None:
        result = self._request(
            "thread/read", {"threadId": engine_ref, "includeTurns": False}
        )
        thread = result.get("thread") or {}
        if thread.get("id") != engine_ref:
            raise EngineFailure("engine task mapping is invalid")

    def rename_task(self, engine_ref: str, name: str) -> None:
        safe_name, _ = bounded_text(name, 200)
        if not safe_name.strip():
            raise EngineFailure("task name is required")
        self._request(
            "thread/name/set", {"threadId": engine_ref, "name": safe_name}
        )

    def archive_task(self, engine_ref: str) -> None:
        self._request("thread/archive", {"threadId": engine_ref})

    def unarchive_task(self, engine_ref: str) -> None:
        self._request("thread/unarchive", {"threadId": engine_ref})

    def delete_task(self, engine_ref: str) -> None:
        self._request("thread/delete", {"threadId": engine_ref})
        with self._goal_lock:
            self._goal_snapshots.pop(engine_ref, None)

    def start_turn(
        self,
        engine_ref: str,
        workspace: Workspace,
        mode: RunMode,
        text: str,
        effort: str,
        *,
        inputs: list[dict[str, Any]] | None = None,
        skill_name: str | None = None,
    ) -> Operation:
        capabilities = self.capabilities()
        if effort not in capabilities["reasoningEfforts"]:
            raise EngineFailure("reasoning effort unavailable")
        if not text.strip() and not inputs:
            raise EngineFailure("turn input is required")
        if inputs is not None and skill_name is not None:
            raise EngineFailure("skill and custom inputs cannot be combined")
        turn_inputs = inputs
        if skill_name is not None:
            turn_inputs = [
                {"type": "text", "text": text},
                self._skill_input(workspace, skill_name),
            ]
        operation = self._new_operation(engine_ref, workspace)
        thread = threading.Thread(
            target=self._run_turn,
            args=(operation, workspace, mode, text, effort, turn_inputs),
            name="codex-turn",
            daemon=True,
        )
        thread.start()
        return operation

    def start_review(
        self,
        engine_ref: str,
        workspace: Workspace,
        target: Mapping[str, Any],
    ) -> Operation:
        normalized_target = self._review_target(target)
        operation = self._new_operation(engine_ref, workspace)
        threading.Thread(
            target=self._run_review,
            args=(operation, normalized_target),
            name="codex-review",
            daemon=True,
        ).start()
        return operation

    def set_goal(
        self, engine_ref: str, objective: str, status: str | None = None
    ) -> None:
        safe_objective, truncated = bounded_text(objective, 10_000)
        if not safe_objective.strip():
            raise EngineFailure("goal objective is required")
        if status not in {None, "active", "paused"}:
            raise EngineFailure("goal status is invalid")
        params = {"threadId": engine_ref, "objective": safe_objective}
        if status is not None:
            params["status"] = status
        result = self._request(
            "thread/goal/set",
            params,
        )
        goal = result.get("goal")
        if isinstance(goal, dict):
            self._remember_goal(engine_ref, goal)
            return
        with self._goal_lock:
            previous = self._goal_snapshots.get(engine_ref)
            same_objective = previous is not None and previous["objective"] == safe_objective
            self._goal_snapshots[engine_ref] = {
                "objective": safe_objective,
                "objectiveTruncated": truncated,
                "status": status or (previous["status"] if same_objective else "active"),
                "tokensUsed": previous["tokensUsed"] if same_objective else 0,
                "timeUsedSeconds": previous["timeUsedSeconds"] if same_objective else 0,
            }

    def goal(self, engine_ref: str) -> dict[str, Any] | None:
        result = self._request("thread/goal/get", {"threadId": engine_ref})
        goal = result.get("goal")
        if goal is None:
            with self._goal_lock:
                snapshot = self._goal_snapshots.get(engine_ref)
                if snapshot is None:
                    return None
                if snapshot["status"] == "active":
                    snapshot = {**snapshot, "status": "complete"}
                    self._goal_snapshots[engine_ref] = snapshot
                return dict(snapshot)
        if not isinstance(goal, dict):
            raise EngineFailure("goal record is invalid")
        return self._remember_goal(engine_ref, goal)

    def clear_goal(self, engine_ref: str) -> None:
        self._request("thread/goal/clear", {"threadId": engine_ref})
        with self._goal_lock:
            self._goal_snapshots.pop(engine_ref, None)

    def steer(self, operation_id: str, text: str) -> None:
        operation = self._operation(operation_id)
        if operation.terminal or operation.turn_ref is None:
            raise EngineFailure("active turn cannot be steered")
        self._request(
            "turn/steer",
            {
                "threadId": operation.engine_ref,
                "expectedTurnId": operation.turn_ref,
                "input": [{"type": "text", "text": text}],
            },
        )

    def stop(self, operation_id: str) -> None:
        operation = self._operation(operation_id)
        if operation.terminal:
            return
        operation.stop_requested = True
        self._interactions.cancel_operation(operation_id)
        if operation.turn_ref is not None:
            self._request(
                "turn/interrupt",
                {"threadId": operation.engine_ref, "turnId": operation.turn_ref},
            )

    def decide(
        self,
        operation_id: str,
        interaction_id: str,
        decision: str,
        content: Mapping[str, Any] | None = None,
    ) -> None:
        self._interactions.decide(
            operation_id, interaction_id, decision, content
        )

    def operation(self, operation_id: str) -> Operation:
        return self._operation(operation_id)

    def pending_interactions(self, operation_id: str) -> list[dict[str, Any]]:
        self._operation(operation_id)
        return [
            interaction.as_public_dict()
            for interaction in self._interactions.pending(operation_id)
        ]

    def account(self) -> dict[str, Any]:
        account_result = self._request(
            "account/read", {"refreshToken": False}
        )
        try:
            limits_result = self._request("account/rateLimits/read", None)
        except RunnerProtocolError:
            # Usage metadata is optional. A pinned App Server can reject this
            # request while the authenticated account and task APIs remain live.
            limits_result = {}
        account = account_result.get("account") or {}
        limits = limits_result.get("rateLimits") or {}
        primary = limits.get("primary") or {}
        return {
            "authentication": account.get("type")
            if isinstance(account.get("type"), str)
            else "none",
            "requiresAuthentication": account_result.get("requiresOpenaiAuth") is True
            and not bool(account),
            "plan": limits.get("planType")
            if isinstance(limits.get("planType"), str)
            else None,
            "usedPercent": primary.get("usedPercent")
            if isinstance(primary.get("usedPercent"), (int, float))
            else None,
            "resetsAt": primary.get("resetsAt")
            if isinstance(primary.get("resetsAt"), int)
            else None,
        }

    def skills(self, workspace: Workspace) -> list[dict[str, Any]]:
        result = self._request("skills/list", {"cwds": [workspace.path]})
        skills: list[dict[str, Any]] = []
        for row in result.get("data") or []:
            if not isinstance(row, dict):
                continue
            for skill in row.get("skills") or []:
                if (
                    not isinstance(skill, dict)
                    or skill.get("enabled") is not True
                    or not isinstance(skill.get("name"), str)
                ):
                    continue
                description, _ = bounded_text(skill.get("description"), 500)
                skills.append({"name": skill["name"], "description": description})
        return skills

    def mcp_status(self, engine_ref: str) -> list[dict[str, Any]]:
        result = self._request(
            "mcpServerStatus/list",
            {"threadId": engine_ref, "detail": "toolsAndAuthOnly", "limit": 100},
        )
        servers: list[dict[str, Any]] = []
        for row in result.get("data") or []:
            if not isinstance(row, dict) or not isinstance(row.get("name"), str):
                continue
            name = row["name"]
            if self._allowed_mcp_servers is not None and name not in self._allowed_mcp_servers:
                continue
            tools = row.get("tools") or {}
            tool_names = sorted(
                tool_name for tool_name in tools if isinstance(tool_name, str)
            )
            servers.append(
                {
                    "name": name,
                    "authStatus": row.get("authStatus")
                    if isinstance(row.get("authStatus"), str)
                    else "unknown",
                    "tools": tool_names,
                }
            )
        return servers

    def _run_turn(
        self,
        operation: Operation,
        workspace: Workspace,
        mode: RunMode,
        text: str,
        effort: str,
        inputs: list[dict[str, Any]] | None,
    ) -> None:
        try:
            result = self._request(
                "turn/start",
                {
                    "threadId": operation.engine_ref,
                    "model": self.MODEL,
                    "effort": effort,
                    "cwd": workspace.path,
                    "approvalPolicy": "on-request",
                    "approvalsReviewer": "user",
                    "sandboxPolicy": self._sandbox(workspace, mode),
                    "input": inputs or [{"type": "text", "text": text}],
                },
            )
            turn_ref = (result.get("turn") or {}).get("id")
            if not isinstance(turn_ref, str):
                raise EngineFailure("turn start returned no reference")
            operation.bind_turn(turn_ref)
            if operation.stop_requested:
                self.stop(operation.operation_id)
            if not operation.wait_terminal(timeout_seconds=self._turn_timeout):
                try:
                    self.stop(operation.operation_id)
                finally:
                    operation.publish(
                        "turn_completed", {"status": "timeout"}, terminal=True
                    )
        except Exception:
            operation.publish(
                "turn_completed", {"status": "engineError"}, terminal=True
            )

    def _run_review(
        self, operation: Operation, target: Mapping[str, Any]
    ) -> None:
        try:
            result = self._request(
                "review/start",
                {
                    "threadId": operation.engine_ref,
                    "delivery": "inline",
                    "target": dict(target),
                },
            )
            turn_ref = (result.get("turn") or {}).get("id")
            if not isinstance(turn_ref, str):
                raise EngineFailure("review start returned no reference")
            operation.bind_turn(turn_ref)
            if operation.stop_requested:
                self.stop(operation.operation_id)
            if not operation.wait_terminal(timeout_seconds=self._turn_timeout):
                try:
                    self.stop(operation.operation_id)
                finally:
                    operation.publish(
                        "turn_completed", {"status": "timeout"}, terminal=True
                    )
        except Exception:
            operation.publish(
                "turn_completed", {"status": "engineError"}, terminal=True
            )

    def _on_notification_from(
        self,
        client: EngineClient,
        method: str,
        params: Mapping[str, Any],
    ) -> None:
        with self._state_monitor:
            if self._shutdown or self._closed or self._client is not client:
                return
        self._on_notification(method, params)

    def _on_notification(self, method: str, params: Mapping[str, Any]) -> None:
        if method == "thread/goal/updated":
            engine_ref = params.get("threadId")
            goal = params.get("goal")
            if isinstance(engine_ref, str) and isinstance(goal, dict):
                self._remember_goal(engine_ref, goal)
            return
        if method == "thread/goal/cleared":
            # Explicit Synvo clears update the cache in clear_goal. A provider
            # clear after completion must retain the terminal presentation.
            return
        with self._lock:
            operation = self._active
        if operation is None or operation.terminal:
            return
        thread_ref = params.get("threadId")
        if thread_ref is not None and thread_ref != operation.engine_ref:
            return
        turn = params.get("turn")
        turn_ref = params.get("turnId")
        if turn_ref is None and isinstance(turn, dict):
            turn_ref = turn.get("id")
        if turn_ref is not None and operation.turn_ref is not None and turn_ref != operation.turn_ref:
            return
        try:
            event = self._normalizer.normalize_notification(
                method, params, workspace_root=operation.workspace_path
            )
        except ProtocolIncompatibility:
            self._interactions.cancel_operation(operation.operation_id)
            operation.publish(
                "turn_completed", {"status": "protocolIncompatible"}, terminal=True
            )
            return
        if event is None:
            return
        terminal = event.kind == "turn_completed"
        operation.publish(
            event.kind, event.payload, terminal=terminal,
            source_turn_ref=turn_ref if isinstance(turn_ref, str) else None,
        )

    def _on_server_request_from(
        self, client: EngineClient, request: ServerRequest
    ) -> Mapping[str, Any]:
        with self._state_monitor:
            if self._shutdown or self._closed or self._client is not client:
                raise EngineFailure("App Server request has no current owner")
        return self._on_server_request(request)

    def _on_server_request(self, request: ServerRequest) -> Mapping[str, Any]:
        with self._lock:
            operation = self._active
        if operation is None or operation.terminal:
            raise EngineFailure("no active operation owns interaction")
        thread_ref = request.params.get("threadId")
        turn_ref = request.params.get("turnId")
        if (thread_ref is not None and thread_ref != operation.engine_ref) or (
            turn_ref is not None and operation.turn_ref is not None
            and turn_ref != operation.turn_ref
        ):
            raise EngineFailure("interaction does not belong to the active turn")
        return self._interactions.hold(
            operation.operation_id,
            operation.workspace_id,
            operation.workspace_path,
            request,
        )

    def _release(self, operation: Operation) -> None:
        with self._lock:
            if self._active is operation:
                self._active = None
            completed = [item for item in self._operations.values() if item.terminal]
            for expired in completed[:-self.MAX_RECENT_OPERATIONS]:
                self._operations.pop(expired.operation_id, None)
                self._interactions.discard_operation(expired.operation_id)

    def _new_operation(self, engine_ref: str, workspace: Workspace) -> Operation:
        with self._lock:
            if self._active is not None and not self._active.terminal:
                raise EngineBusy("another Codex turn is active")
            operation = Operation(
                str(uuid.uuid4()),
                engine_ref,
                workspace.workspace_id,
                workspace.path,
                self._interactions,
                self._release,
                max_events=self._max_events,
            )
            self._operations[operation.operation_id] = operation
            self._active = operation
            return operation

    def _operation(self, operation_id: str) -> Operation:
        with self._lock:
            operation = self._operations.get(operation_id)
        if operation is None:
            raise EngineFailure("operation not found")
        return operation

    def _task_from_result(self, result: Mapping[str, Any]) -> EngineTask:
        thread = result.get("thread") or {}
        engine_ref = thread.get("id")
        model = thread.get("model")
        if not isinstance(engine_ref, str):
            raise EngineFailure("task create returned no reference")
        if model not in (None, self.MODEL):
            raise EngineFailure("required model was substituted")
        return EngineTask(engine_ref, self.MODEL)

    def _remember_goal(
        self, engine_ref: str, goal: Mapping[str, Any]
    ) -> dict[str, Any]:
        objective, truncated = bounded_text(goal.get("objective"), 10_000)
        if not objective.strip():
            raise EngineFailure("goal record is invalid")
        status = goal.get("status")
        if status not in {
            "active",
            "paused",
            "blocked",
            "usageLimited",
            "budgetLimited",
            "complete",
        }:
            status = "blocked"
        snapshot = {
            "objective": objective,
            "objectiveTruncated": truncated,
            "status": status,
            "tokensUsed": goal.get("tokensUsed")
            if isinstance(goal.get("tokensUsed"), int)
            else 0,
            "timeUsedSeconds": goal.get("timeUsedSeconds")
            if isinstance(goal.get("timeUsedSeconds"), int)
            else 0,
        }
        with self._goal_lock:
            self._goal_snapshots[engine_ref] = snapshot
        return dict(snapshot)

    def _skill_input(self, workspace: Workspace, skill_name: str) -> dict[str, Any]:
        if not skill_name or len(skill_name) > 200:
            raise EngineFailure("skill name is invalid")
        result = self._request("skills/list", {"cwds": [workspace.path]})
        for row in result.get("data") or []:
            if not isinstance(row, dict):
                continue
            for skill in row.get("skills") or []:
                if not isinstance(skill, dict) or skill.get("name") != skill_name:
                    continue
                if skill.get("enabled") is not True:
                    raise EngineFailure("skill is unavailable")
                path = skill.get("path")
                if not isinstance(path, str) or not Path(path).is_absolute():
                    raise EngineFailure("skill path is invalid")
                return {"type": "skill", "name": skill_name, "path": path}
        raise EngineFailure("skill is unavailable")

    @staticmethod
    def _review_target(target: Mapping[str, Any]) -> dict[str, Any]:
        target_type = target.get("type")
        if target_type == "uncommittedChanges" and set(target) == {"type"}:
            return {"type": target_type}
        if target_type == "baseBranch" and set(target) == {"type", "branch"}:
            branch = target.get("branch")
            if isinstance(branch, str) and 0 < len(branch) <= 200:
                return {"type": target_type, "branch": branch}
        if target_type == "commit" and set(target).issubset({"type", "sha", "title"}):
            sha = target.get("sha")
            if isinstance(sha, str) and re.fullmatch(r"[0-9a-fA-F]{7,64}", sha):
                return {"type": target_type, "sha": sha}
        if target_type == "custom" and set(target) == {"type", "instructions"}:
            instructions, truncated = bounded_text(target.get("instructions"), 10_000)
            if instructions.strip() and not truncated:
                return {"type": target_type, "instructions": instructions}
        raise EngineFailure("review target is invalid")

    @staticmethod
    def _sandbox(workspace: Workspace, mode: RunMode) -> dict[str, Any]:
        if mode == RunMode.READ_ONLY:
            return {"type": "readOnly", "networkAccess": False}
        if mode == RunMode.WORKSPACE_WRITE:
            return {
                "type": "workspaceWrite",
                "writableRoots": [workspace.path],
                "networkAccess": False,
                "excludeSlashTmp": True,
                "excludeTmpdirEnvVar": True,
            }
        raise EngineFailure("unsupported sandbox mode")
