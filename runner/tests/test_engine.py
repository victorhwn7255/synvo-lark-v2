from __future__ import annotations

import threading
import time
import unittest
from typing import Any, Mapping

from synvo_runner.capabilities import CapabilityPolicy
from synvo_runner.engine import (
    CodexEngine,
    EngineBusy,
    EngineFailure,
    EngineHealth,
    RunMode,
    Workspace,
)
from synvo_runner.protocol import (
    RunnerProtocolError,
    RunnerUnavailable,
    ServerRequest,
)


class FakeClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, Mapping[str, Any]]] = []
        self.notification_handler = lambda _method, _params: None
        self.request_handler = lambda _request: {}
        self.failure_handler = lambda _error: None
        self.started = False
        self.closed = False
        self.paginate_models = False
        self.paginate_features = False
        self.thread_status = "idle"
        self.reject_rate_limits = False
        self.reject_health_probe = False
        self.loaded_thread_ids = ["engine-thread-1"]
        self.persisted_thread_ids = ["engine-thread-1"]
        self.goal_value: dict[str, Any] | None = {
            "threadId": "engine-thread-1",
            "objective": "Ship safely",
            "status": "active",
            "tokensUsed": 12,
            "timeUsedSeconds": 3,
            "createdAt": 1,
            "updatedAt": 2,
        }
        self.mcp_rows = [
            {
                "name": "allowed_fixture",
                "authStatus": "unsupported",
                "tools": {
                    "read_marker": {
                        "name": "read_marker",
                        "description": "Read",
                        "annotations": {"readOnlyHint": True},
                    }
                },
            }
        ]

    def start(self) -> None:
        self.started = True

    def close(self) -> None:
        self.closed = True

    def on_notification(self, handler) -> None:
        self.notification_handler = handler

    def on_server_request(self, handler) -> None:
        self.request_handler = handler

    def on_failure(self, handler) -> None:
        self.failure_handler = handler

    def request(self, method: str, params: Mapping[str, Any] | None, **_kwargs):
        safe_params = dict(params or {})
        self.calls.append((method, safe_params))
        if method == "model/list":
            if self.reject_health_probe and safe_params.get("includeHidden") is False:
                raise RunnerUnavailable("stale App Server")
            if self.paginate_models and safe_params.get("cursor") is None:
                return {
                    "data": [
                        {
                            "id": "another-model",
                            "supportedReasoningEfforts": [
                                {"reasoningEffort": "low"}
                            ],
                        }
                    ],
                    "nextCursor": "model-page-2",
                }
            return {
                "data": [
                    {
                        "id": "gpt-5.6-sol",
                        "supportedReasoningEfforts": [
                            {"reasoningEffort": "low"},
                            {"reasoningEffort": "high"},
                        ],
                    }
                ]
            }
        if method == "experimentalFeature/list":
            rows = [
                *[
                    {
                        "name": name,
                        "stage": "stable",
                        "enabled": True,
                        "defaultEnabled": True,
                    }
                    for name in sorted(CapabilityPolicy.REQUIRED_STABLE_FEATURES)
                ],
                {
                    "name": "network_proxy",
                    "stage": "beta",
                    "enabled": False,
                    "defaultEnabled": False,
                },
                *[
                    {
                        "name": name,
                        "stage": "removed",
                        "enabled": True,
                        "defaultEnabled": True,
                    }
                    for name in sorted(CapabilityPolicy.PINNED_REMOVED_FEATURES)
                ],
            ]
            if self.paginate_features:
                midpoint = len(rows) // 2
                if safe_params.get("cursor") is None:
                    return {
                        "data": rows[:midpoint],
                        "nextCursor": "feature-page-2",
                    }
                return {"data": rows[midpoint:]}
            return {"data": rows}
        if method == "thread/start":
            return {"thread": {"id": "engine-thread-1", "model": "gpt-5.6-sol"}}
        if method == "thread/fork":
            return {"thread": {"id": "engine-thread-2", "model": "gpt-5.6-sol"}}
        if method == "thread/resume":
            return {
                "thread": {
                    "id": safe_params["threadId"],
                    "model": "gpt-5.6-sol",
                }
            }
        if method == "thread/loaded/list":
            return {"data": self.loaded_thread_ids}
        if method == "thread/list":
            return {
                "data": [
                    {
                        "id": thread_id,
                        "status": {"type": self.thread_status},
                    }
                    for thread_id in self.persisted_thread_ids
                ]
            }
        if method == "thread/read":
            return {
                "thread": {
                    "id": safe_params["threadId"],
                    "status": {"type": self.thread_status},
                    "turns": [],
                }
            }
        if method == "turn/start":
            return {"turn": {"id": "engine-turn-1"}}
        if method == "review/start":
            return {"turn": {"id": "engine-review-1"}}
        if method == "turn/steer":
            return {"turnId": safe_params["expectedTurnId"]}
        if method == "account/read":
            return {
                "account": {"type": "chatgpt", "email": "private@example.invalid"},
                "requiresOpenaiAuth": True,
            }
        if method == "account/rateLimits/read":
            if self.reject_rate_limits:
                raise RunnerProtocolError("optional rate limits unavailable")
            return {
                "rateLimits": {
                    "planType": "pro",
                    "primary": {"usedPercent": 12.5, "resetsAt": 12345},
                }
            }
        if method == "thread/goal/get":
            return {"goal": self.goal_value}
        if method == "thread/goal/set":
            previous = self.goal_value or {}
            same_objective = previous.get("objective") == safe_params.get("objective")
            self.goal_value = {
                "threadId": safe_params["threadId"],
                "objective": safe_params["objective"],
                "status": safe_params.get("status")
                or (previous.get("status") if same_objective else "active"),
                "tokensUsed": previous.get("tokensUsed", 0) if same_objective else 0,
                "timeUsedSeconds": previous.get("timeUsedSeconds", 0)
                if same_objective
                else 0,
                "createdAt": 1,
                "updatedAt": 2,
            }
            return {"goal": self.goal_value}
        if method == "thread/goal/clear":
            self.goal_value = None
            return {"cleared": True}
        if method == "skills/list":
            return {
                "data": [
                    {
                        "cwd": "/workspace/private",
                        "skills": [
                            {
                                "name": "safe-skill",
                                "description": "Harmless skill",
                                "enabled": True,
                                "path": "/workspace/private/.agents/skills/safe/SKILL.md",
                            },
                            {
                                "name": "disabled-skill",
                                "description": "Unavailable skill",
                                "enabled": False,
                                "path": "/workspace/private/.agents/skills/disabled/SKILL.md",
                            }
                        ],
                    }
                ]
            }
        if method == "mcpServerStatus/list":
            return {"data": self.mcp_rows}
        return {}

    def emit(self, method: str, params: Mapping[str, Any]) -> None:
        self.notification_handler(method, params)

    def server_request(self, request: ServerRequest) -> Mapping[str, Any]:
        return self.request_handler(request)

    def fail(self) -> None:
        self.failure_handler(
            RunnerUnavailable("raw provider detail must not escape")
        )


class CodexEngineTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = FakeClient()
        self.engine = CodexEngine(
            client=self.client,
            installed_version="0.148.0",
            capability_policy=CapabilityPolicy(
                runtime_version="0.148.0", required_model="gpt-5.6-sol"
            ),
            turn_timeout_seconds=2,
            interaction_timeout_seconds=1,
            allowed_mcp_servers={"allowed_fixture"},
        )
        self.engine.start()
        self.assertTrue(self.engine.ready())
        self.workspace = Workspace("workspace-1", "/workspace/private")

    def tearDown(self) -> None:
        self.engine.close()

    def test_startup_verifies_model_features_and_reasoning_efforts(self) -> None:
        snapshot = self.engine.capabilities()

        self.assertEqual("gpt-5.6-sol", snapshot["model"])
        self.assertEqual(["low", "high"], snapshot["reasoningEfforts"])
        self.assertNotIn("network_proxy", snapshot["enabledFeatures"])
        self.assertNotIn("collaboration_modes", snapshot["enabledFeatures"])
        self.assertIn("collaboration_modes", snapshot["retiredRuntimeRecords"])

    def test_startup_reads_every_model_and_feature_inventory_page(self) -> None:
        client = FakeClient()
        client.paginate_models = True
        client.paginate_features = True
        engine = CodexEngine(
            client=client,
            installed_version="0.148.0",
            capability_policy=CapabilityPolicy(
                runtime_version="0.148.0", required_model="gpt-5.6-sol"
            ),
            allowed_mcp_servers={"allowed_fixture"},
        )

        engine.start()

        self.assertTrue(engine.ready())
        self.assertEqual("gpt-5.6-sol", engine.capabilities()["model"])
        self.assertEqual(
            2,
            sum(1 for method, _params in client.calls if method == "model/list"),
        )
        self.assertEqual(
            2,
            sum(
                1
                for method, _params in client.calls
                if method == "experimentalFeature/list"
            ),
        )
        engine.close()

    def test_startup_rejects_unallowlisted_or_unclassified_mcp_tools(self) -> None:
        client = FakeClient()
        engine = CodexEngine(
            client=client,
            installed_version="0.148.0",
            capability_policy=CapabilityPolicy(
                runtime_version="0.148.0", required_model="gpt-5.6-sol"
            ),
            allowed_mcp_servers=set(),
        )

        with self.assertRaises(EngineFailure):
            engine.start()

        self.assertTrue(client.closed)

        client = FakeClient()
        client.mcp_rows[0]["tools"]["read_marker"].pop("annotations")
        engine = CodexEngine(
            client=client,
            installed_version="0.148.0",
            capability_policy=CapabilityPolicy(
                runtime_version="0.148.0", required_model="gpt-5.6-sol"
            ),
            allowed_mcp_servers={"allowed_fixture"},
        )

        with self.assertRaises(EngineFailure):
            engine.start()

        self.assertTrue(client.closed)

    def test_task_create_and_fork_never_expose_workspace_path(self) -> None:
        task = self.engine.create_task(self.workspace, RunMode.READ_ONLY)
        forked = self.engine.fork_task(task.engine_ref, self.workspace)

        self.assertEqual("engine-thread-1", task.engine_ref)
        self.assertEqual("engine-thread-2", forked.engine_ref)
        self.assertNotIn("/workspace/private", str(task.as_public_dict()))
        create = next(params for method, params in self.client.calls if method == "thread/start")
        self.assertEqual("gpt-5.6-sol", create["model"])
        self.assertEqual("/workspace/private", create["cwd"])
        self.assertIn("workspace-relative", create["developerInstructions"])
        self.assertIn("verified", create["developerInstructions"])
        self.assertIn(
            "batch naturally related document and data",
            create["developerInstructions"],
        )
        self.assertIn(
            "Do not combine unrelated actions", create["developerInstructions"]
        )
        self.assertIn(
            "Do not transport validation scripts through base64",
            create["developerInstructions"],
        )
        self.assertIn(
            "simplify the command once while preserving the same validation criteria",
            create["developerInstructions"],
        )
        self.assertIn(
            "does not mean the selected workspace is read-only",
            create["developerInstructions"],
        )
        self.assertIn(
            "do not report a permission blocker",
            create["developerInstructions"],
        )
        self.assertIn(
            "do not claim that an approval or user decision is pending",
            create["developerInstructions"],
        )
        self.assertIn("changed to Full Edit", create["developerInstructions"])
        self.assertNotIn("/workspace/private", create["developerInstructions"])
        self.assertEqual("on-request", create["approvalPolicy"])
        self.assertEqual("user", create["approvalsReviewer"])
        fork = next(params for method, params in self.client.calls if method == "thread/fork")
        self.assertEqual(create["developerInstructions"], fork["developerInstructions"])

    def test_resume_loaded_task_does_not_reopen_the_live_thread(self) -> None:
        task = self.engine.resume_task("engine-thread-1", self.workspace)

        self.assertEqual("engine-thread-1", task.engine_ref)
        self.assertTrue(
            any(
                method == "thread/loaded/list"
                for method, _params in self.client.calls
            )
        )
        self.assertFalse(
            any(method == "thread/resume" for method, _params in self.client.calls)
        )

    def test_resume_unloaded_task_reopens_the_persisted_thread(self) -> None:
        self.client.loaded_thread_ids = []
        self.client.thread_status = "notLoaded"

        task = self.engine.resume_task("engine-thread-1", self.workspace)

        self.assertEqual("engine-thread-1", task.engine_ref)
        resume = next(
            params for method, params in self.client.calls if method == "thread/resume"
        )
        self.assertEqual("gpt-5.6-sol", resume["model"])
        self.assertEqual("/workspace/private", resume["cwd"])
        self.assertIn("workspace-relative", resume["developerInstructions"])

    def test_resume_fails_closed_for_an_unusable_thread_state(self) -> None:
        self.client.loaded_thread_ids = []
        self.client.thread_status = "systemError"

        with self.assertRaises(EngineFailure):
            self.engine.resume_task("engine-thread-1", self.workspace)

        self.assertFalse(
            any(method == "thread/resume" for method, _params in self.client.calls)
        )

    def test_resume_reports_a_zero_turn_task_lost_across_restart_as_missing(self) -> None:
        self.client.loaded_thread_ids = []
        self.client.persisted_thread_ids = []

        with self.assertRaises(LookupError):
            self.engine.resume_task("engine-thread-1", self.workspace)

        self.assertFalse(
            any(method == "thread/resume" for method, _params in self.client.calls)
        )

    def test_read_only_turn_streams_and_releases_global_lease_once(self) -> None:
        operation = self.engine.start_turn(
            "engine-thread-1", self.workspace, RunMode.READ_ONLY, "Inspect the project", "low"
        )
        with self.assertRaises(EngineBusy):
            self.engine.start_turn(
                "engine-thread-2", self.workspace, RunMode.READ_ONLY, "Compete", "low"
            )
        self._wait_for_call("turn/start")
        turn_params = next(
            params for method, params in self.client.calls if method == "turn/start"
        )
        self.assertEqual(
            {"type": "readOnly", "networkAccess": False},
            turn_params["sandboxPolicy"],
        )
        self.assertEqual("on-request", turn_params["approvalPolicy"])
        self.assertEqual("user", turn_params["approvalsReviewer"])
        self.client.emit(
            "item/agentMessage/delta",
            {
                "threadId": "engine-thread-1",
                "turnId": "engine-turn-1",
                "itemId": "item-1",
                "delta": " \n",
            },
        )
        self.client.emit(
            "turn/completed",
            {"turn": {"id": "engine-turn-1", "status": "completed"}},
        )
        self.assertTrue(operation.wait_terminal(timeout_seconds=1))

        events = operation.events(after_sequence=-1)
        self.assertEqual(" \n", events[0]["payload"]["text"])
        self.assertEqual("turn_completed", events[-1]["kind"])
        next_operation = self.engine.start_turn(
            "engine-thread-2", self.workspace, RunMode.READ_ONLY, "Next", "low"
        )
        self.assertNotEqual(operation.operation_id, next_operation.operation_id)

    def test_streamed_message_never_exposes_a_workspace_path_split_across_deltas(self) -> None:
        operation = self.engine.start_turn(
            "engine-thread-1", self.workspace, RunMode.READ_ONLY, "Inspect", "low"
        )
        self._wait_for_call("turn/start")
        self.client.emit(
            "item/agentMessage/delta",
            {"itemId": "item-1", "delta": "Source: [README.md](/workspace/pri"},
        )
        self.client.emit(
            "item/agentMessage/delta",
            {"itemId": "item-1", "delta": "vate/README.md:271)"},
        )
        self.client.emit(
            "item/completed",
            {
                "item": {
                    "id": "item-1",
                    "type": "agentMessage",
                    "text": "Source: [README.md](/workspace/private/README.md:271)",
                }
            },
        )
        self.client.emit(
            "turn/completed",
            {"turn": {"id": "engine-turn-1", "status": "completed"}},
        )
        self.assertTrue(operation.wait_terminal(timeout_seconds=1))

        events = operation.events(after_sequence=-1)
        visible = "".join(
            event["payload"].get("text", "")
            for event in events
            if event["kind"] == "message_delta"
        )
        self.assertEqual("Source: [README.md](./README.md:271)", visible)
        self.assertNotIn("/workspace/private", str(events))

    def test_workspace_write_is_confined_and_network_off(self) -> None:
        self.engine.start_turn(
            "engine-thread-1", self.workspace, RunMode.WORKSPACE_WRITE, "Edit", "high"
        )
        self._wait_for_call("turn/start")
        params = next(params for method, params in self.client.calls if method == "turn/start")
        self.assertEqual("workspaceWrite", params["sandboxPolicy"]["type"])
        self.assertEqual(["/workspace/private"], params["sandboxPolicy"]["writableRoots"])
        self.assertFalse(params["sandboxPolicy"]["networkAccess"])

    def test_explicit_skill_invocation_keeps_skill_path_private(self) -> None:
        self.engine.start_turn(
            "engine-thread-1",
            self.workspace,
            RunMode.READ_ONLY,
            "Use the skill",
            "low",
            skill_name="safe-skill",
        )
        self._wait_for_call("turn/start")
        params = next(params for method, params in self.client.calls if method == "turn/start")
        skill = next(value for value in params["input"] if value["type"] == "skill")
        self.assertEqual("safe-skill", skill["name"])
        self.assertEqual(
            "/workspace/private/.agents/skills/safe/SKILL.md", skill["path"]
        )
        self.assertNotIn("path", str(self.engine.skills(self.workspace)))

    def test_disabled_skill_is_neither_listed_nor_invocable(self) -> None:
        self.assertNotIn("disabled-skill", str(self.engine.skills(self.workspace)))

        with self.assertRaises(EngineFailure):
            self.engine.start_turn(
                "engine-thread-1",
                self.workspace,
                RunMode.READ_ONLY,
                "Use the disabled skill",
                "low",
                skill_name="disabled-skill",
            )

        self.assertFalse(
            any(method == "turn/start" for method, _params in self.client.calls)
        )

    def test_goal_and_review_use_focused_stable_operations(self) -> None:
        self.engine.set_goal("engine-thread-1", "Ship safely")
        self.engine.set_goal("engine-thread-1", "Ship safely", "paused")
        goal = self.engine.goal("engine-thread-1")
        self.engine.clear_goal("engine-thread-1")

        self.assertEqual("Ship safely", goal["objective"])
        self.assertNotIn("threadId", goal)
        goal_calls = [
            params for method, params in self.client.calls
            if method == "thread/goal/set"
        ]
        self.assertNotIn("status", goal_calls[0])
        self.assertEqual("paused", goal_calls[1]["status"])
        review = self.engine.start_review(
            "engine-thread-1",
            self.workspace,
            {"type": "uncommittedChanges"},
        )
        self._wait_for_call("review/start")
        review_call = next(
            params for method, params in self.client.calls if method == "review/start"
        )
        self.assertEqual("inline", review_call["delivery"])
        self.client.emit(
            "turn/completed",
            {"turn": {"id": "engine-review-1", "status": "completed"}},
        )
        self.assertTrue(review.wait_terminal(timeout_seconds=1))

    def test_completed_goal_remains_visible_after_runtime_removes_current_goal(self) -> None:
        self.engine.set_goal("engine-thread-1", "Maintain verified reports")
        self.client.emit(
            "thread/goal/updated",
            {
                "threadId": "engine-thread-1",
                "goal": {
                    "threadId": "engine-thread-1",
                    "objective": "Maintain verified reports",
                    "status": "complete",
                    "tokensUsed": 321,
                    "timeUsedSeconds": 9,
                },
            },
        )
        self.client.goal_value = None

        goal = self.engine.goal("engine-thread-1")

        self.assertEqual("complete", goal["status"])
        self.assertEqual(321, goal["tokensUsed"])
        self.assertEqual(9, goal["timeUsedSeconds"])
        self.engine.clear_goal("engine-thread-1")
        self.assertIsNone(self.engine.goal("engine-thread-1"))

    def test_missing_active_goal_is_normalized_as_complete(self) -> None:
        self.engine.set_goal("engine-thread-1", "Maintain verified reports")
        self.client.goal_value = None

        goal = self.engine.goal("engine-thread-1")

        self.assertEqual("complete", goal["status"])

        with self.assertRaises(EngineFailure):
            self.engine.start_review(
                "engine-thread-1",
                self.workspace,
                {"type": "custom", "instructions": "x" * 10_001},
            )

    def test_command_elevation_is_declined_without_an_operation_interaction(self) -> None:
        operation = self.engine.start_turn(
            "engine-thread-1", self.workspace, RunMode.READ_ONLY, "Run tests", "low"
        )
        self._wait_for_call("turn/start")
        response = self.client.server_request(
            ServerRequest(
                "vendor-approval",
                "item/commandExecution/requestApproval",
                {
                    "command": "python3 -m unittest",
                    "reason": "Run tests",
                    "availableDecisions": [
                        "accept", "acceptForSession", "decline", "cancel"
                    ],
                },
            )
        )
        self.assertEqual({"decision": "decline"}, response)
        with self.assertRaises(TimeoutError):
            operation.wait_for_interaction(timeout_seconds=0.01)

    def test_stop_and_protocol_failure_have_one_safe_terminal(self) -> None:
        operation = self.engine.start_turn(
            "engine-thread-1", self.workspace, RunMode.READ_ONLY, "Work", "low"
        )
        self._wait_for_call("turn/start")
        self.engine.stop(operation.operation_id)
        self.client.fail()

        self.assertTrue(operation.wait_terminal(timeout_seconds=1))
        terminal = [event for event in operation.events(-1) if event["terminal"]]
        self.assertEqual(1, len(terminal))
        self.assertNotIn("raw provider detail", str(terminal))
        self.assertFalse(self.engine.ready())

    def test_live_readiness_detects_a_stale_runtime_instead_of_trusting_startup(self) -> None:
        client = FakeClient()
        engine = CodexEngine(
            client=client,
            installed_version="0.148.0",
            capability_policy=CapabilityPolicy(
                runtime_version="0.148.0", required_model="gpt-5.6-sol"
            ),
            allowed_mcp_servers={"allowed_fixture"},
            health_probe_interval_seconds=0,
        )
        engine.start()
        client.reject_health_probe = True

        self.assertEqual(EngineHealth.UNAVAILABLE.value, engine.health())
        self.assertFalse(engine.ready())
        engine.close()

    def test_transport_failure_recovers_once_without_replaying_the_active_turn(self) -> None:
        original = FakeClient()
        replacement = FakeClient()
        created: list[FakeClient] = []

        def replace() -> FakeClient:
            created.append(replacement)
            return replacement

        engine = CodexEngine(
            client=original,
            client_factory=replace,
            installed_version="0.148.0",
            capability_policy=CapabilityPolicy(
                runtime_version="0.148.0", required_model="gpt-5.6-sol"
            ),
            allowed_mcp_servers={"allowed_fixture"},
            recovery_attempts=1,
            recovery_backoff_seconds=(0,),
        )
        engine.start()
        operation = engine.start_turn(
            "engine-thread-1",
            Workspace("workspace-1", "/workspace/private"),
            RunMode.READ_ONLY,
            "Inspect",
            "low",
        )
        self._wait_for_client_call(original, "turn/start")

        original.fail()
        original.fail()

        self._wait_for_engine_health(engine, EngineHealth.READY)
        self.assertEqual(1, len(created))
        self.assertTrue(original.closed)
        self.assertTrue(replacement.started)
        self.assertFalse(
            any(method == "turn/start" for method, _params in replacement.calls)
        )
        terminal = [event for event in operation.events(-1) if event["terminal"]]
        self.assertEqual(1, len(terminal))
        self.assertEqual("runnerUnavailable", terminal[0]["payload"]["status"])
        engine.close()

    def test_incompatible_replacement_remains_fail_closed_after_bounded_attempt(self) -> None:
        original = FakeClient()
        incompatible = FakeClient()
        incompatible.mcp_rows[0]["tools"]["read_marker"].pop("annotations")
        engine = CodexEngine(
            client=original,
            client_factory=lambda: incompatible,
            installed_version="0.148.0",
            capability_policy=CapabilityPolicy(
                runtime_version="0.148.0", required_model="gpt-5.6-sol"
            ),
            allowed_mcp_servers={"allowed_fixture"},
            recovery_attempts=1,
            recovery_backoff_seconds=(0,),
            recovery_cooldown_seconds=60,
        )
        engine.start()

        original.fail()

        self._wait_for_engine_health(engine, EngineHealth.PROTOCOL_INCOMPATIBLE)
        self.assertTrue(incompatible.closed)
        self.assertFalse(engine.ready())
        engine.close()

    def test_account_skill_and_mcp_snapshots_are_sanitized(self) -> None:
        account = self.engine.account()
        skills = self.engine.skills(self.workspace)
        mcp = self.engine.mcp_status("engine-thread-1")

        self.assertEqual("chatgpt", account["authentication"])
        self.assertEqual("pro", account["plan"])
        self.assertNotIn("private@example.invalid", str(account))
        self.assertNotIn("/workspace/private", str(skills))
        self.assertEqual("allowed_fixture", mcp[0]["name"])

    def test_account_remains_ready_when_optional_rate_limits_are_rejected(self) -> None:
        self.client.reject_rate_limits = True

        account = self.engine.account()

        self.assertEqual("chatgpt", account["authentication"])
        self.assertFalse(account["requiresAuthentication"])
        self.assertIsNone(account["plan"])
        self.assertIsNone(account["usedPercent"])
        self.assertIsNone(account["resetsAt"])

    def _wait_for_call(self, method: str) -> None:
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            if any(name == method for name, _params in self.client.calls):
                return
            time.sleep(0.005)
        self.fail(f"missing {method} call")

    def _wait_for_client_call(self, client: FakeClient, method: str) -> None:
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            if any(name == method for name, _params in client.calls):
                return
            time.sleep(0.005)
        self.fail(f"missing {method} call")

    def _wait_for_engine_health(
        self, engine: CodexEngine, expected: EngineHealth
    ) -> None:
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if engine.health() == expected.value:
                return
            time.sleep(0.005)
        self.fail(f"engine did not reach {expected.value}")


if __name__ == "__main__":
    unittest.main()
