from __future__ import annotations

import http.client
import json
import threading
import unittest
from dataclasses import dataclass
from typing import Any

from synvo_runner.engine import EngineBusy, EngineTask, RunMode
from synvo_runner.http_api import RunnerApplication, RunnerHttpServer


@dataclass
class FakeOperation:
    operation_id: str = "operation-1"
    terminal: bool = False

    def events(self, after_sequence: int) -> list[dict[str, Any]]:
        if after_sequence >= 0:
            return []
        return [
            {
                "sequence": 0,
                "kind": "message_delta",
                "payload": {"text": "hello", "truncated": False},
                "terminal": False,
            }
        ]

    def wait_events(self, after_sequence, _stopped, *, timeout_seconds):
        return self.events(after_sequence)


class FakeEngine:
    def __init__(self) -> None:
        self.operation_value = FakeOperation()
        self.ready_value = True
        self.health_value = "ready"
        self.created = []
        self.decisions = []
        self.stopped = []
        self.steered = []

    def ready(self):
        return self.ready_value

    def health(self):
        return self.health_value if self.ready_value else "unavailable"

    def capabilities(self):
        return {"model": "gpt-5.6-sol", "reasoningEfforts": ["low"]}

    def account(self):
        return {"authentication": "chatgpt", "plan": "pro"}

    def create_task(self, workspace, mode):
        self.created.append((workspace, mode))
        return EngineTask("engine-thread-1", "gpt-5.6-sol")

    def fork_task(self, engine_ref, workspace):
        return EngineTask("engine-thread-2", "gpt-5.6-sol")

    def resume_task(self, engine_ref, workspace):
        return EngineTask(engine_ref, "gpt-5.6-sol")

    def start_turn(
        self, engine_ref, workspace, mode, text, effort, inputs=None, skill_name=None
    ):
        if text == "busy":
            raise EngineBusy("busy")
        return self.operation_value

    def start_review(self, engine_ref, workspace, target):
        return self.operation_value

    def operation(self, operation_id):
        if operation_id != "operation-1":
            raise KeyError("missing")
        return self.operation_value

    def pending_interactions(self, operation_id):
        return [
            {
                "interactionId": "interaction-1",
                "operationId": operation_id,
                "workspaceId": "workspace-1",
                "kind": "command",
                "category": "shell command",
                "reason": "Run tests",
                "availableDecisions": ["accept", "decline"],
                "detail": {"command": "python3 -m unittest"},
                "expiresAt": 123,
            }
        ]

    def decide(self, operation_id, interaction_id, decision, content=None):
        self.decisions.append((operation_id, interaction_id, decision, content))

    def stop(self, operation_id):
        self.stopped.append(operation_id)

    def steer(self, operation_id, text):
        self.steered.append((operation_id, text))

    def rename_task(self, engine_ref, name):
        return None

    def archive_task(self, engine_ref):
        return None

    def unarchive_task(self, engine_ref):
        return None

    def delete_task(self, engine_ref):
        return None

    def skills(self, workspace):
        return [{"name": "safe", "description": "Safe"}]

    def mcp_status(self, engine_ref):
        return [{"name": "fixture", "authStatus": "unsupported", "tools": ["read"]}]

    def set_goal(self, engine_ref, objective, status=None):
        self.goal_value = {
            "objective": objective,
            "status": status or getattr(self, "goal_value", {}).get("status", "active"),
        }

    def goal(self, engine_ref):
        return getattr(self, "goal_value", None)

    def clear_goal(self, engine_ref):
        self.goal_value = None


class RunnerHttpApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = FakeEngine()
        self.app = RunnerApplication(enabled=True, engine=self.engine)
        self.server = RunnerHttpServer(("127.0.0.1", 0), self.app)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=1)

    def request(self, method: str, path: str, body: dict[str, Any] | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
        encoded = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if encoded is not None else {}
        connection.request(method, path, body=encoded, headers=headers)
        response = connection.getresponse()
        payload = json.loads(response.read() or b"{}")
        headers_result = dict(response.getheaders())
        connection.close()
        return response.status, headers_result, payload

    def test_health_account_and_capabilities_are_safe_and_no_store(self) -> None:
        status, headers, health = self.request("GET", "/health")
        self.assertEqual(200, status)
        self.assertEqual("ready", health["state"])
        self.assertEqual("no-store", headers["Cache-Control"])

        self.assertEqual(200, self.request("GET", "/v1/account")[0])
        self.assertEqual(200, self.request("GET", "/v1/capabilities")[0])

        self.engine.ready_value = False
        unavailable = self.request("GET", "/health")[2]
        self.assertEqual("unavailable", unavailable["state"])

        self.engine.ready_value = True
        for state in ("recovering", "protocolIncompatible"):
            self.engine.health_value = state
            self.assertEqual(state, self.request("GET", "/health")[2]["state"])

    def test_create_turn_events_and_interaction_decision(self) -> None:
        status, _headers, task = self.request(
            "POST",
            "/v1/tasks",
            {
                "workspaceId": "workspace-1",
                "workspacePath": "/workspace/private",
                "mode": "readOnly",
            },
        )
        self.assertEqual(201, status)
        self.assertEqual("engine-thread-1", task["engineRef"])

        status, _headers, operation = self.request(
            "POST",
            "/v1/tasks/engine-thread-1/turns",
            {
                "workspaceId": "workspace-1",
                "workspacePath": "/workspace/private",
                "mode": "readOnly",
                "text": "Inspect",
                "effort": "low",
            },
        )
        self.assertEqual(202, status)
        self.assertEqual("operation-1", operation["operationId"])

        events = self.request("GET", "/v1/operations/operation-1/events?after=-1")[2]
        self.assertEqual("message_delta", events["events"][0]["kind"])
        interactions = self.request(
            "GET", "/v1/operations/operation-1/interactions"
        )[2]
        self.assertEqual("command", interactions["interactions"][0]["kind"])

        decision_status = self.request(
            "POST",
            "/v1/operations/operation-1/decisions",
            {"interactionId": "interaction-1", "decision": "decline"},
        )[0]
        self.assertEqual(204, decision_status)
        self.assertEqual(
            [("operation-1", "interaction-1", "decline", None)],
            self.engine.decisions,
        )

    def test_busy_invalid_json_unknown_fields_and_body_limit_are_bounded(self) -> None:
        busy = self.request(
            "POST",
            "/v1/tasks/engine-thread-1/turns",
            {
                "workspaceId": "workspace-1",
                "workspacePath": "/workspace/private",
                "mode": "readOnly",
                "text": "busy",
                "effort": "low",
            },
        )
        self.assertEqual(409, busy[0])
        self.assertEqual("ENGINE_BUSY", busy[2]["error"])

        unknown = self.request(
            "POST",
            "/v1/tasks",
            {
                "workspaceId": "workspace-1",
                "workspacePath": "/workspace/private",
                "mode": "readOnly",
                "vendorMethod": "thread/start",
            },
        )
        self.assertEqual(400, unknown[0])

        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
        connection.request(
            "POST",
            "/v1/tasks",
            body=b"x" * (RunnerApplication.MAX_BODY_BYTES + 1),
            headers={"Content-Type": "application/json"},
        )
        response = connection.getresponse()
        self.assertEqual(413, response.status)
        response.read()
        connection.close()

    def test_review_goal_resume_and_inventory_are_focused_resources(self) -> None:
        resume = self.request(
            "POST",
            "/v1/tasks/engine-thread-1/resume",
            {"workspaceId": "workspace-1", "workspacePath": "/workspace/private"},
        )
        self.assertEqual(200, resume[0])
        review = self.request(
            "POST",
            "/v1/tasks/engine-thread-1/reviews",
            {
                "workspaceId": "workspace-1",
                "workspacePath": "/workspace/private",
                "target": {"type": "uncommittedChanges"},
            },
        )
        self.assertEqual(202, review[0])
        self.assertEqual("operation-1", review[2]["operationId"])

        self.assertEqual(
            204,
            self.request(
                "POST",
                "/v1/tasks/engine-thread-1/goal",
                {"objective": "Ship safely"},
            )[0],
        )
        self.assertEqual(
            "Ship safely",
            self.request("GET", "/v1/tasks/engine-thread-1/goal")[2]["objective"],
        )
        self.assertEqual(
            204,
            self.request(
                "POST",
                "/v1/tasks/engine-thread-1/goal",
                {"objective": "Ship safely", "status": "paused"},
            )[0],
        )
        self.assertEqual(
            "paused",
            self.request("GET", "/v1/tasks/engine-thread-1/goal")[2]["status"],
        )
        self.assertEqual(
            400,
            self.request(
                "POST",
                "/v1/tasks/engine-thread-1/goal",
                {"objective": "Ship safely", "status": "blocked"},
            )[0],
        )
        self.assertEqual(
            204,
            self.request("DELETE", "/v1/tasks/engine-thread-1/goal")[0],
        )
        inventory = self.request(
            "POST",
            "/v1/tasks/engine-thread-1/inventory",
            {"workspaceId": "workspace-1", "workspacePath": "/workspace/private"},
        )
        self.assertEqual("safe", inventory[2]["skills"][0]["name"])

    def test_disabled_mode_is_healthy_but_credential_free_and_unavailable(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=1)
        disabled = RunnerHttpServer(
            ("127.0.0.1", 0), RunnerApplication(enabled=False, engine=None)
        )
        thread = threading.Thread(target=disabled.serve_forever, daemon=True)
        thread.start()
        port = disabled.server_address[1]
        try:
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
            connection.request("GET", "/health")
            health = connection.getresponse()
            self.assertEqual("disabled", json.loads(health.read())["state"])
            connection.request("GET", "/v1/account")
            unavailable = connection.getresponse()
            self.assertEqual(503, unavailable.status)
            self.assertEqual("RUNNER_DISABLED", json.loads(unavailable.read())["error"])
            connection.close()
        finally:
            disabled.shutdown()
            disabled.server_close()
            thread.join(timeout=1)


if __name__ == "__main__":
    unittest.main()
