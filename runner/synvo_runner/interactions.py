"""Transient, bounded stable interaction lifecycle inside the runner."""

from __future__ import annotations

import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import parse_qsl, urlsplit

from .normalization import bounded_text
from .protocol import ServerRequest


class InteractionError(RuntimeError):
    pass


class InteractionConflict(InteractionError):
    pass


class UnsupportedInteraction(InteractionError):
    pass


_SENSITIVE_FIELD = re.compile(
    r"(?i)(token|secret|password|credential|api[_-]?key|authorization)"
)
_ONE_TIME_DECISIONS = frozenset({"accept", "decline", "cancel"})


@dataclass
class Interaction:
    interaction_id: str
    operation_id: str
    workspace_id: str
    kind: str
    category: str
    reason: str
    available_decisions: tuple[str, ...]
    detail: dict[str, Any]
    expires_at: float
    deadline: float = field(repr=False)
    vendor_request_id: int | str = field(repr=False)
    decision: str | None = None
    decision_content: Mapping[str, Any] | None = field(default=None, repr=False)

    def as_public_dict(self) -> dict[str, Any]:
        return {
            "interactionId": self.interaction_id,
            "operationId": self.operation_id,
            "workspaceId": self.workspace_id,
            "kind": self.kind,
            "category": self.category,
            "reason": self.reason,
            "availableDecisions": list(self.available_decisions),
            "detail": dict(self.detail),
            "expiresAt": self.expires_at,
        }


class InteractionRegistry:
    """Hold App Server requests without leaking their records or identifiers."""

    STABLE_METHODS = {
        "item/fileChange/requestApproval": "file",
        "mcpServer/elicitation/request": "mcp_elicitation",
    }
    BELOW_STABLE_METHODS = {
        "item/tool/requestUserInput",
        "item/permissions/requestApproval",
        "item/tool/call",
    }

    def __init__(self, *, default_timeout_seconds: float = 300) -> None:
        self._timeout = default_timeout_seconds
        self._condition = threading.Condition()
        self._interactions: dict[str, Interaction] = {}

    def hold(
        self,
        operation_id: str,
        workspace_id: str,
        workspace_path: str,
        request: ServerRequest,
    ) -> dict[str, Any]:
        if request.method == "item/commandExecution/requestApproval":
            return {"decision": "decline"}
        if request.method in self.BELOW_STABLE_METHODS:
            raise UnsupportedInteraction("below-Stable interaction rejected")
        kind = self.STABLE_METHODS.get(request.method)
        if kind is None:
            raise UnsupportedInteraction("unknown interaction rejected")

        interaction = self._new_interaction(
            operation_id, workspace_id, workspace_path, request, kind
        )
        with self._condition:
            self._interactions[interaction.interaction_id] = interaction
            self._condition.notify_all()
            while interaction.decision is None:
                remaining = interaction.deadline - time.monotonic()
                if remaining <= 0:
                    interaction.decision = "cancel"
                    break
                self._condition.wait(timeout=remaining)
            decision = interaction.decision
            content = interaction.decision_content

        if kind == "mcp_elicitation":
            response: dict[str, Any] = {"action": decision}
            if decision == "accept" and content is not None:
                response["content"] = dict(content)
            return response
        return {"decision": decision}

    def wait_for_pending(
        self, operation_id: str, *, timeout_seconds: float
    ) -> Interaction:
        deadline = time.monotonic() + timeout_seconds
        with self._condition:
            while True:
                pending = next(
                    (
                        interaction
                        for interaction in self._interactions.values()
                        if interaction.operation_id == operation_id
                        and interaction.decision is None
                    ),
                    None,
                )
                if pending is not None:
                    return pending
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("no pending interaction")
                self._condition.wait(timeout=remaining)

    def pending(self, operation_id: str) -> list[Interaction]:
        with self._condition:
            return [
                interaction
                for interaction in self._interactions.values()
                if interaction.operation_id == operation_id
                and interaction.decision is None
            ]

    def decide(
        self,
        operation_id: str,
        interaction_id: str,
        decision: str,
        content: Mapping[str, Any] | None = None,
    ) -> None:
        with self._condition:
            interaction = self._interactions.get(interaction_id)
            if interaction is None or interaction.operation_id != operation_id:
                raise InteractionConflict("interaction does not belong to operation")
            if decision not in interaction.available_decisions:
                raise InteractionConflict("decision is unavailable")
            normalized_content = self._normalize_content(
                interaction, decision, content
            )
            if interaction.decision is not None:
                if (
                    interaction.decision == decision
                    and interaction.decision_content == normalized_content
                ):
                    return
                raise InteractionConflict("interaction already has another decision")
            interaction.decision = decision
            interaction.decision_content = normalized_content
            self._condition.notify_all()

    def cancel_operation(self, operation_id: str) -> None:
        with self._condition:
            for interaction in self._interactions.values():
                if interaction.operation_id == operation_id and interaction.decision is None:
                    interaction.decision = "cancel"
            self._condition.notify_all()

    def _new_interaction(
        self,
        operation_id: str,
        workspace_id: str,
        workspace_path: str,
        request: ServerRequest,
        kind: str,
    ) -> Interaction:
        detail: dict[str, Any] = {}
        category = "workspace change"
        reason = "Codex requests a bounded workspace decision."
        if kind == "file":
            decisions = self._advertised_decisions(
                request.params,
                _ONE_TIME_DECISIONS,
                required=False,
                fallback=("accept", "decline", "cancel"),
            )
            category = "file change"
            reason = "Codex requests permission to change workspace files."
            affected_paths = self._affected_paths(workspace_path, request.params)
            if affected_paths:
                detail["affectedPaths"] = affected_paths
        else:
            decisions = ("accept", "decline", "cancel")
            server_name = request.params.get("serverName") or request.params.get(
                "server"
            )
            tool_name = request.params.get("toolName") or request.params.get("tool")
            if isinstance(server_name, str) and 0 < len(server_name) <= 100:
                detail["mcpServer"] = server_name
            if isinstance(tool_name, str) and 0 < len(tool_name) <= 200:
                detail["mcpTool"] = tool_name
                kind = "mcp_tool"
                category = "MCP tool"
                reason = "Codex requests permission to use an allowlisted MCP tool."
            else:
                category = "MCP request"
                reason = "Codex requests input for an allowlisted MCP request."
            message, truncated = bounded_text(request.params.get("message"), 1_024)
            detail["message"] = message
            detail["messageTruncated"] = truncated
            mode = request.params.get("mode")
            if mode in {"form", "openai/form", "url"}:
                detail["mode"] = mode
            if mode in {"form", "openai/form"}:
                detail["fields"] = self._elicitation_fields(request.params)
            elif mode == "url":
                detail["elicitationUrl"] = self._elicitation_url(request.params)
        return Interaction(
            interaction_id=str(uuid.uuid4()),
            operation_id=operation_id,
            workspace_id=workspace_id,
            kind=kind,
            category=category,
            reason=reason,
            available_decisions=decisions,
            detail=detail,
            expires_at=time.time() + self._timeout,
            deadline=time.monotonic() + self._timeout,
            vendor_request_id=request.request_id,
        )

    @staticmethod
    def _advertised_decisions(
        params: Mapping[str, Any],
        allowed: frozenset[str],
        *,
        required: bool,
        fallback: tuple[str, ...] = (),
    ) -> tuple[str, ...]:
        values = params.get("availableDecisions")
        if values is None and not required:
            return fallback
        if (
            not isinstance(values, list)
            or not 0 < len(values) <= 12
            or not all(isinstance(value, str) for value in values)
        ):
            raise UnsupportedInteraction("available decisions are unavailable")
        decisions = tuple(dict.fromkeys(value for value in values if value in allowed))
        if not decisions:
            raise UnsupportedInteraction("available decisions are unsupported")
        return decisions

    @staticmethod
    def _elicitation_fields(params: Mapping[str, Any]) -> list[dict[str, Any]]:
        schema = params.get("requestedSchema")
        if not isinstance(schema, dict) or schema.get("type") != "object":
            raise UnsupportedInteraction("MCP form schema is unavailable")
        properties = schema.get("properties")
        if not isinstance(properties, dict) or len(properties) > 20:
            raise UnsupportedInteraction("MCP form schema is unavailable")
        required_value = schema.get("required") or []
        if not isinstance(required_value, list) or not all(
            isinstance(name, str) for name in required_value
        ):
            raise UnsupportedInteraction("MCP form schema is unavailable")
        required = set(required_value)
        if not required.issubset(properties):
            raise UnsupportedInteraction("MCP form schema is unavailable")
        fields: list[dict[str, Any]] = []
        for name, definition in properties.items():
            if (
                not isinstance(name, str)
                or not 0 < len(name) <= 100
                or _SENSITIVE_FIELD.search(name)
                or not isinstance(definition, dict)
            ):
                raise UnsupportedInteraction("MCP form field is unavailable")
            value_type = definition.get("type")
            options = definition.get("enum")
            if isinstance(options, list):
                if (
                    value_type != "string"
                    or not 0 < len(options) <= 20
                    or not all(
                        isinstance(option, str) and 0 < len(option) <= 200
                        for option in options
                    )
                ):
                    raise UnsupportedInteraction("MCP select field is unavailable")
                field_type = "select"
            elif value_type in {"string", "boolean", "number", "integer"}:
                field_type = value_type
                options = []
            else:
                raise UnsupportedInteraction("MCP form field type is unavailable")
            label, _ = bounded_text(definition.get("title") or name, 120)
            maximum = definition.get("maxLength")
            max_length = (
                min(maximum, 2_000)
                if isinstance(maximum, int) and maximum > 0
                else 2_000
            )
            fields.append(
                {
                    "name": name,
                    "label": label or name,
                    "type": field_type,
                    "required": name in required,
                    "options": list(options),
                    "maxLength": max_length if field_type == "string" else 0,
                }
            )
        return fields

    @staticmethod
    def _elicitation_url(params: Mapping[str, Any]) -> str:
        value = params.get("url") or params.get("elicitationUrl")
        if not isinstance(value, str) or not 0 < len(value) <= 2_048:
            raise UnsupportedInteraction("MCP elicitation URL is unavailable")
        parsed = urlsplit(value)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
        ):
            raise UnsupportedInteraction("MCP elicitation URL is unavailable")
        if any(_SENSITIVE_FIELD.search(name) for name, _value in parse_qsl(parsed.query)):
            raise UnsupportedInteraction("MCP elicitation URL is unavailable")
        return value

    @staticmethod
    def _normalize_content(
        interaction: Interaction,
        decision: str,
        content: Mapping[str, Any] | None,
    ) -> Mapping[str, Any] | None:
        supplied = dict(content or {})
        if interaction.kind != "mcp_elicitation" or decision != "accept":
            if supplied:
                raise InteractionConflict("decision content is unavailable")
            return None
        fields = interaction.detail.get("fields") or []
        if not isinstance(fields, list):
            raise InteractionConflict("interaction fields are unavailable")
        definitions = {
            field.get("name"): field
            for field in fields
            if isinstance(field, dict) and isinstance(field.get("name"), str)
        }
        if set(supplied) - set(definitions):
            raise InteractionConflict("decision content contains an unknown field")
        normalized: dict[str, Any] = {}
        for name, field in definitions.items():
            if name not in supplied:
                if field.get("required") is True:
                    raise InteractionConflict("decision content is incomplete")
                continue
            value = supplied[name]
            field_type = field.get("type")
            if field_type == "boolean":
                if value not in (True, False, "true", "false"):
                    raise InteractionConflict("boolean decision content is invalid")
                normalized[name] = value is True or value == "true"
            elif field_type == "integer":
                try:
                    normalized[name] = int(value)
                except (TypeError, ValueError) as error:
                    raise InteractionConflict("integer decision content is invalid") from error
            elif field_type == "number":
                try:
                    number = float(value)
                except (TypeError, ValueError) as error:
                    raise InteractionConflict("number decision content is invalid") from error
                if number != number or number in (float("inf"), float("-inf")):
                    raise InteractionConflict("number decision content is invalid")
                normalized[name] = number
            elif field_type in {"string", "select"}:
                if not isinstance(value, str):
                    raise InteractionConflict("text decision content is invalid")
                if field_type == "select" and value not in field.get("options", []):
                    raise InteractionConflict("select decision content is invalid")
                if field_type == "string" and len(value) > field.get("maxLength", 2_000):
                    raise InteractionConflict("text decision content is too long")
                normalized[name] = value
            else:
                raise InteractionConflict("decision field type is unavailable")
        return normalized

    @classmethod
    def _affected_paths(
        cls, workspace_path: str, params: Mapping[str, Any]
    ) -> list[str]:
        candidates: list[object] = []
        for key in ("path", "filePath", "grantRoot"):
            if key in params:
                candidates.append(params[key])
        for key in ("paths", "affectedPaths"):
            value = params.get(key)
            if isinstance(value, list):
                if len(value) > 100:
                    raise UnsupportedInteraction("file path set is unavailable")
                candidates.extend(value)
        changes = params.get("changes")
        if isinstance(changes, list):
            if len(changes) > 100:
                raise UnsupportedInteraction("file path set is unavailable")
            for change in changes:
                if isinstance(change, dict):
                    candidates.append(change.get("path"))
        elif isinstance(changes, dict):
            if len(changes) > 100:
                raise UnsupportedInteraction("file path set is unavailable")
            candidates.extend(changes)
        paths: list[str] = []
        for candidate in candidates:
            relative = cls._relative_path(workspace_path, candidate)
            if relative is None:
                raise UnsupportedInteraction("file path is unavailable")
            if relative not in paths:
                paths.append(relative)
        if not paths:
            raise UnsupportedInteraction("file path set is unavailable")
        return paths

    @staticmethod
    def _relative_path(workspace_path: str, value: object) -> str | None:
        if not isinstance(value, str) or not value or len(value) > 4_096:
            return None
        root = Path(workspace_path).resolve()
        candidate = Path(value)
        resolved = candidate.resolve() if candidate.is_absolute() else (root / candidate).resolve()
        try:
            relative = resolved.relative_to(root)
        except ValueError:
            return None
        rendered = relative.as_posix()
        return rendered if rendered else "."
