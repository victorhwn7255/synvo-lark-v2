#!/usr/bin/env python3
"""Deterministic JSONL peer for runner protocol acceptance tests."""

from __future__ import annotations

import json
import os
import sys
import time
from typing import Any


def send(message: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


scenario = sys.argv[1]
for line in sys.stdin:
    message = json.loads(line)
    method = message.get("method")
    request_id = message.get("id")
    if method == "initialize":
        capabilities = message.get("params", {}).get("capabilities")
        if capabilities and capabilities.get("experimentalApi"):
            send(
                {
                    "id": request_id,
                    "error": {"code": -32600, "message": "experimental rejected"},
                }
            )
        else:
            send({"id": request_id, "result": {"serverInfo": {"version": "0.148.0"}}})
    elif method == "test/interleave":
        send({"method": "turn/started", "params": {"turn": {"id": "turn-1"}}})
        send(
            {
                "id": "approval-1",
                "method": "item/commandExecution/requestApproval",
                "params": {
                    "threadId": "thread-1",
                    "turnId": "turn-1",
                    "itemId": "item-1",
                    "command": "never persisted",
                },
            }
        )
    elif request_id == "approval-1":
        send({"id": 2, "result": {"correlated": True}})
    elif method == "test/malformed":
        sys.stdout.write("not-json\n")
        sys.stdout.flush()
    elif method == "test/exit":
        os._exit(17)
    elif method == "test/hang":
        time.sleep(60)
    elif request_id is not None:
        send({"id": request_id, "result": {}})

