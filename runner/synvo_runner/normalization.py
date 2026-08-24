"""Translate stable App Server activity into bounded Synvo vocabulary."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Mapping


class ProtocolIncompatibility(RuntimeError):
    """A required vendor variant is unknown or forbidden by Phase 3 policy."""


@dataclass(frozen=True)
class NormalizedEvent:
    kind: str
    payload: Mapping[str, Any]


_SENSITIVE_PATTERNS = (
    re.compile(
        r"(?i)authorization\s*[:=]\s*(?:bearer\s+)?[^\s'\"]+"
    ),
    re.compile(
        r"(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password)"
        r"\s*[:=]\s*[^\s,;]+"
    ),
)


def redact_text(value: str, *, workspace_root: str | None = None) -> str:
    redacted = _workspace_relative_text(value, workspace_root)
    for pattern in _SENSITIVE_PATTERNS:
        redacted = pattern.sub(lambda match: match.group(0).split(":", 1)[0].split("=", 1)[0] + "=[redacted]", redacted)
    return redacted


def bounded_text(
    value: object, max_bytes: int, *, workspace_root: str | None = None
) -> tuple[str, bool]:
    text = redact_text(
        value if isinstance(value, str) else "", workspace_root=workspace_root
    )
    encoded = text.encode("utf-8")
    if len(encoded) <= max_bytes:
        return text, False
    clipped = encoded[:max_bytes]
    while clipped:
        try:
            return clipped.decode("utf-8") + "…", True
        except UnicodeDecodeError:
            clipped = clipped[:-1]
    return "…", True


class EventNormalizer:
    """Keep optional vendor growth private while failing on required variants."""

    SUPPORTED_ITEMS = frozenset(
        {
            "userMessage",
            "agentMessage",
            "plan",
            "reasoning",
            "commandExecution",
            "fileChange",
            "mcpToolCall",
            "collabAgentToolCall",
            "subAgentActivity",
            "sleep",
            "enteredReviewMode",
            "exitedReviewMode",
            "contextCompaction",
        }
    )
    EXCLUDED_ITEMS = frozenset(
        {
            "hookPrompt",
            "dynamicToolCall",
            "webSearch",
            "imageView",
            "imageGeneration",
        }
    )

    def __init__(self, *, max_text_bytes: int = 16_384) -> None:
        self._max_text_bytes = max_text_bytes

    def normalize_notification(
        self,
        method: str,
        params: Mapping[str, Any],
        *,
        workspace_root: str | None = None,
    ) -> NormalizedEvent | None:
        if method == "item/reasoning/textDelta":
            # App Server may emit private reasoning separately from its safe
            # reasoning summary. Phase 3 never forwards private reasoning.
            return None
        text_kinds = {
            "item/agentMessage/delta": "message_delta",
            "item/plan/delta": "plan_delta",
            "item/reasoning/summaryTextDelta": "reasoning_delta",
            "item/commandExecution/outputDelta": "command_output",
            "item/fileChange/outputDelta": "file_output",
            "item/fileChange/patchUpdated": "diff",
            "turn/diff/updated": "diff",
        }
        if method in text_kinds:
            field = "delta" if "delta" in params else "diff"
            text, truncated = bounded_text(
                params.get(field),
                self._max_text_bytes,
                workspace_root=workspace_root,
            )
            return NormalizedEvent(
                text_kinds[method],
                {
                    "text": text,
                    "truncated": truncated,
                    "itemRef": self._safe_ref(params.get("itemId")),
                },
            )

        if method in {"item/started", "item/completed"}:
            return self._normalize_item(
                method, params, workspace_root=workspace_root
            )
        if method == "turn/started":
            return NormalizedEvent("turn_started", {})
        if method == "turn/completed":
            turn = params.get("turn") if isinstance(params.get("turn"), dict) else {}
            status = turn.get("status") if isinstance(turn.get("status"), str) else "failed"
            error_code = self._normalized_error_code(turn)
            if status == "failed" and error_code == "usageLimitExceeded":
                status = "usageLimited"
            elif status == "failed" and error_code == "unauthorized":
                status = "authenticationRequired"
            return NormalizedEvent(
                "turn_completed",
                {"status": status, "errorCode": error_code},
            )
        if method == "turn/plan/updated":
            return NormalizedEvent("plan_updated", {})
        if method == "item/mcpToolCall/progress":
            return NormalizedEvent(
                "mcp_progress", {"itemRef": self._safe_ref(params.get("itemId"))}
            )
        if method == "thread/compacted":
            return NormalizedEvent("compacted", {})
        if method == "thread/tokenUsage/updated":
            return NormalizedEvent("usage_updated", {})
        if method == "serverRequest/resolved":
            return NormalizedEvent("interaction_resolved", {})
        if method == "model/rerouted":
            raise ProtocolIncompatibility("required model was rerouted")
        return None

    def _normalize_item(
        self,
        method: str,
        params: Mapping[str, Any],
        *,
        workspace_root: str | None = None,
    ) -> NormalizedEvent | None:
        item = params.get("item")
        if not isinstance(item, dict):
            raise ProtocolIncompatibility("item lifecycle record is missing")
        item_type = item.get("type")
        if not isinstance(item_type, str):
            raise ProtocolIncompatibility("item lifecycle type is missing")
        if item_type in self.EXCLUDED_ITEMS or item_type not in self.SUPPORTED_ITEMS:
            raise ProtocolIncompatibility("unsupported App Server item type")
        phase = "started" if method.endswith("started") else "completed"
        item_ref = self._safe_ref(item.get("id"))
        if item_type == "userMessage":
            return None
        if item_type == "agentMessage" and phase == "started":
            # Synvo's public activity vocabulary starts an answer at its first
            # visible delta. The vendor's empty message-start marker carries no
            # user-visible state and stays private to this adapter.
            return None
        if item_type == "enteredReviewMode":
            return NormalizedEvent("review_entered", {"itemRef": item_ref})
        if item_type == "exitedReviewMode":
            return NormalizedEvent("review_exited", {"itemRef": item_ref})
        if item_type == "contextCompaction":
            return NormalizedEvent("compacted", {"itemRef": item_ref})
        if item_type in {"collabAgentToolCall", "subAgentActivity"}:
            return NormalizedEvent(
                f"nested_activity_{phase}", {"itemRef": item_ref}
            )
        category = {
            "agentMessage": "message",
            "plan": "plan",
            "reasoning": "reasoning",
            "commandExecution": "command",
            "fileChange": "file_change",
            "mcpToolCall": "mcp",
            "sleep": "wait",
        }[item_type]
        payload: dict[str, Any] = {"itemRef": item_ref}
        if item_type == "agentMessage" and phase == "completed":
            text, truncated = bounded_text(
                item.get("text"),
                self._max_text_bytes,
                workspace_root=workspace_root,
            )
            payload.update({"text": text, "truncated": truncated})
        return NormalizedEvent(f"{category}_{phase}", payload)

    @staticmethod
    def _safe_ref(value: object) -> str | None:
        if isinstance(value, str) and 0 < len(value) <= 128:
            return value
        return None

    @staticmethod
    def _normalized_error_code(turn: Mapping[str, Any]) -> str | None:
        error = turn.get("error")
        if not isinstance(error, dict):
            return None
        info = error.get("codexErrorInfo")
        if isinstance(info, str):
            return info
        if isinstance(info, dict) and info:
            key = next(iter(info))
            return key if isinstance(key, str) else "providerError"
        return "providerError"


def _workspace_relative_text(value: str, workspace_root: str | None) -> str:
    """Remove a configured runner path from a complete text fragment."""
    if not workspace_root:
        return value
    normalized_root = workspace_root.rstrip("/")
    if not normalized_root:
        return value
    relative = value.replace(f"{normalized_root}/", "./")
    return relative.replace(normalized_root, "workspace root")
