#!/usr/bin/env python3
"""Harmless MCP fixture for the tracked Phase 3 end-to-end verification."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any


ELICITATION_ID = 9_001
MARKER_NAME = ".synvo-mcp-fixture-approved"
pending_tool_call: int | str | None = None


def send(message: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def tool_result(request_id: int | str, text: str, *, is_error: bool = False) -> None:
    send(
        {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "content": [{"type": "text", "text": text}],
                "isError": is_error,
            },
        }
    )


def fixture_root() -> Path:
    configured = os.environ.get("SYNVO_MCP_FIXTURE_ROOT")
    if not configured:
        raise RuntimeError("fixture root is unavailable")
    root = Path(configured).resolve(strict=True)
    if not root.is_dir():
        raise RuntimeError("fixture root is unavailable")
    return root


def handle_request(message: dict[str, Any]) -> None:
    global pending_tool_call

    request_id = message.get("id")
    method = message.get("method")
    params = message.get("params") or {}
    if method == "initialize":
        send(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "protocolVersion": params.get("protocolVersion", "2025-11-25"),
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": "synvo-safe-fixture", "version": "1.0.0"},
                },
            }
        )
    elif method == "ping":
        send({"jsonrpc": "2.0", "id": request_id, "result": {}})
    elif method == "tools/list":
        send(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "tools": [
                        {
                            "name": "read_fixture",
                            "description": "Return a harmless fixed verification marker.",
                            "inputSchema": {"type": "object", "properties": {}},
                            "annotations": {
                                "readOnlyHint": True,
                                "destructiveHint": False,
                            },
                        },
                        {
                            "name": "write_fixture_marker",
                            "description": "Create one fixed marker after explicit elicitation.",
                            "inputSchema": {"type": "object", "properties": {}},
                            "annotations": {
                                "readOnlyHint": False,
                                "destructiveHint": False,
                            },
                            "_meta": {
                                "synvo/approvalBoundary": "elicitation-before-side-effect"
                            },
                        },
                    ]
                },
            }
        )
    elif method in ("resources/list", "resources/templates/list"):
        key = "resources" if method == "resources/list" else "resourceTemplates"
        send({"jsonrpc": "2.0", "id": request_id, "result": {key: []}})
    elif method == "tools/call":
        name = params.get("name")
        if name == "read_fixture":
            tool_result(request_id, "SYNVO_MCP_READ_OK")
        elif name == "write_fixture_marker":
            if pending_tool_call is not None:
                tool_result(request_id, "fixture interaction already pending", is_error=True)
                return
            pending_tool_call = request_id
            send(
                {
                    "jsonrpc": "2.0",
                    "id": ELICITATION_ID,
                    "method": "elicitation/create",
                    "params": {
                        "mode": "form",
                        "message": "Create the fixed harmless verification marker?",
                        "requestedSchema": {
                            "type": "object",
                            "properties": {},
                        },
                    },
                }
            )
        else:
            tool_result(request_id, "unknown tool", is_error=True)
    elif request_id is not None:
        send(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32601, "message": "Method not found"},
            }
        )


def handle_response(message: dict[str, Any]) -> None:
    global pending_tool_call

    if message.get("id") != ELICITATION_ID or pending_tool_call is None:
        return
    result = message.get("result") or {}
    request_id = pending_tool_call
    pending_tool_call = None
    if result.get("action") == "accept":
        try:
            marker = fixture_root() / MARKER_NAME
            marker.write_text("fixture-ok\n", encoding="utf-8")
            tool_result(request_id, "SYNVO_MCP_WRITE_OK")
        except (OSError, RuntimeError):
            tool_result(request_id, "fixture root is unavailable", is_error=True)
    else:
        tool_result(request_id, "SYNVO_MCP_WRITE_DECLINED", is_error=True)


def run() -> None:
    for line in sys.stdin:
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(message, dict):
            continue
        if "method" in message:
            handle_request(message)
        elif "id" in message:
            handle_response(message)


if __name__ == "__main__":
    run()
