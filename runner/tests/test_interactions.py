from __future__ import annotations

import threading
import time
import unittest

from synvo_runner.interactions import (
    InteractionConflict,
    InteractionRegistry,
    UnsupportedInteraction,
)
from synvo_runner.protocol import ServerRequest


class InteractionRegistryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = InteractionRegistry(default_timeout_seconds=1)

    def test_command_elevation_is_declined_without_creating_an_interaction(self) -> None:
        for index, params in enumerate((
            {
                "command": "python3 validate_report.py",
                "availableDecisions": ["accept", "decline"],
            },
            {
                "command": "python3 validate_report.py",
                "availableDecisions": [
                    "accept",
                    "acceptForSession",
                    "acceptWithExecpolicyAmendment",
                    "decline",
                    "cancel",
                ],
                "proposedExecpolicyAmendment": ["python3"],
            },
            {
                "command": "curl -H 'Authorization: Bearer secret-value' example.invalid",
                "availableDecisions": ["accept", "decline", "cancel"],
                "networkApprovalContext": {"host": "example.invalid"},
            },
        )):
            operation_id = f"operation-command-{index}"
            response = self.registry.hold(
                operation_id,
                "workspace-1",
                "/workspace/private",
                ServerRequest(
                    f"vendor-command-{index}",
                    "item/commandExecution/requestApproval",
                    params,
                ),
            )
            self.assertEqual({"decision": "decline"}, response)
            self.assertEqual([], self.registry.pending(operation_id))

    def test_session_choice_is_not_extended_to_file_changes(self) -> None:
        file_thread = threading.Thread(
            target=lambda: self.registry.hold(
                "operation-file-narrow",
                "workspace-1",
                "/workspace/private",
                ServerRequest(
                    "vendor-file-narrow",
                    "item/fileChange/requestApproval",
                    {
                        "grantRoot": "/workspace/private",
                        "availableDecisions": [
                            "accept",
                            "acceptForSession",
                            "decline",
                            "cancel",
                        ],
                    },
                ),
            )
        )
        file_thread.start()
        file_change = self.registry.wait_for_pending(
            "operation-file-narrow", timeout_seconds=1
        )
        self.assertEqual(
            ("accept", "decline", "cancel"), file_change.available_decisions
        )
        self.registry.decide(
            "operation-file-narrow", file_change.interaction_id, "decline"
        )
        file_thread.join(timeout=1)

    def test_conflicting_duplicate_decision_fails(self) -> None:
        request = ServerRequest(
            "vendor-2",
            "item/fileChange/requestApproval",
            {"reason": "Update one file", "grantRoot": "/workspace/private"},
        )
        thread = threading.Thread(
            target=lambda: self.registry.hold(
                "operation-2", "workspace-1", "/workspace/private", request
            )
        )
        thread.start()
        interaction = self.registry.wait_for_pending("operation-2", timeout_seconds=1)
        self.registry.decide("operation-2", interaction.interaction_id, "decline")
        with self.assertRaises(InteractionConflict):
            self.registry.decide("operation-2", interaction.interaction_id, "accept")
        thread.join(timeout=1)

    def test_discarded_operation_forgets_its_transient_decision_receipt(self) -> None:
        thread = threading.Thread(target=lambda: self.registry.hold(
            "old-operation", "workspace-1", "/workspace/private",
            ServerRequest("request", "item/fileChange/requestApproval", {"grantRoot": "/workspace/private"}),
        ))
        thread.start()
        interaction = self.registry.wait_for_pending("old-operation", timeout_seconds=1)
        self.registry.decide("old-operation", interaction.interaction_id, "decline")
        thread.join(timeout=1)
        self.assertFalse(thread.is_alive())
        self.registry.decide("old-operation", interaction.interaction_id, "decline")

        self.registry.discard_operation("old-operation")

        self.assertEqual([], self.registry.pending("old-operation"))
        with self.assertRaises(InteractionConflict):
            self.registry.decide("old-operation", interaction.interaction_id, "decline")

    def test_file_approval_rejects_outside_or_unbounded_path_sets(self) -> None:
        with self.assertRaises(UnsupportedInteraction):
            self.registry.hold(
                "operation-file-outside",
                "workspace-1",
                "/workspace/private",
                ServerRequest(
                    "vendor-file-outside",
                    "item/fileChange/requestApproval",
                    {"grantRoot": "/workspace/outside"},
                ),
            )
        with self.assertRaises(UnsupportedInteraction):
            self.registry.hold(
                "operation-file-large",
                "workspace-1",
                "/workspace/private",
                ServerRequest(
                    "vendor-file-large",
                    "item/fileChange/requestApproval",
                    {"paths": [f"file-{index}" for index in range(101)]},
                ),
            )

    def test_below_stable_server_requests_fail_closed(self) -> None:
        with self.assertRaises(UnsupportedInteraction):
            self.registry.hold(
                "operation-4",
                "workspace-1",
                "/workspace/private",
                ServerRequest(
                    "vendor-4", "item/tool/requestUserInput", {"questions": []}
                ),
            )
        with self.assertRaises(UnsupportedInteraction):
            self.registry.hold(
                "operation-4",
                "workspace-1",
                "/workspace/private",
                ServerRequest(
                    "vendor-5", "item/permissions/requestApproval", {}
                ),
            )

    def test_mcp_form_fields_are_bounded_and_values_are_typed(self) -> None:
        result: list[dict[str, object]] = []
        request = ServerRequest(
            "vendor-form",
            "mcpServer/elicitation/request",
            {
                "mode": "form",
                "message": "Continue?",
                "requestedSchema": {
                    "type": "object",
                    "properties": {
                        "confirm": {"type": "boolean", "title": "Confirm"},
                        "profile": {
                            "type": "string",
                            "title": "Profile",
                            "enum": ["safe", "strict"],
                        },
                    },
                    "required": ["confirm", "profile"],
                },
            },
        )
        thread = threading.Thread(
            target=lambda: result.append(
                self.registry.hold(
                    "operation-form", "workspace-1", "/workspace/private", request
                )
            )
        )
        thread.start()
        interaction = self.registry.wait_for_pending(
            "operation-form", timeout_seconds=1
        )

        self.assertEqual("boolean", interaction.detail["fields"][0]["type"])
        self.assertEqual(["safe", "strict"], interaction.detail["fields"][1]["options"])
        with self.assertRaises(InteractionConflict):
            self.registry.decide(
                "operation-form", interaction.interaction_id, "accept", {"confirm": "true"}
            )
        self.registry.decide(
            "operation-form",
            interaction.interaction_id,
            "accept",
            {"confirm": "true", "profile": "strict"},
        )
        thread.join(timeout=1)

        self.assertEqual(
            [{"action": "accept", "content": {"confirm": True, "profile": "strict"}}],
            result,
        )

    def test_mcp_approval_only_form_is_held_for_one_time_decision(self) -> None:
        result: list[dict[str, object]] = []
        request = ServerRequest(
            "vendor-approval-only",
            "mcpServer/elicitation/request",
            {
                "mode": "form",
                "message": "Continue?",
                "serverName": "synvo_safe_fixture",
                "requestedSchema": {"type": "object", "properties": {}},
            },
        )
        thread = threading.Thread(
            target=lambda: result.append(
                self.registry.hold(
                    "operation-approval-only",
                    "workspace-1",
                    "/workspace/private",
                    request,
                )
            )
        )
        thread.start()
        interaction = self.registry.wait_for_pending(
            "operation-approval-only", timeout_seconds=1
        )

        self.assertEqual([], interaction.detail["fields"])
        self.assertEqual("synvo_safe_fixture", interaction.detail["mcpServer"])
        self.registry.decide(
            "operation-approval-only", interaction.interaction_id, "accept"
        )
        thread.join(timeout=1)

        self.assertEqual([{"action": "accept", "content": {}}], result)

    def test_mcp_url_is_https_bounded_and_rejects_credential_shaped_queries(self) -> None:
        thread = threading.Thread(
            target=lambda: self.registry.hold(
                "operation-url",
                "workspace-1",
                "/workspace/private",
                ServerRequest(
                    "vendor-url",
                    "mcpServer/elicitation/request",
                    {
                        "mode": "url",
                        "message": "Complete the external request",
                        "url": "https://fixture.invalid/approve?state=safe",
                    },
                ),
            )
        )
        thread.start()
        interaction = self.registry.wait_for_pending(
            "operation-url", timeout_seconds=1
        )
        self.assertEqual(
            "https://fixture.invalid/approve?state=safe",
            interaction.detail["elicitationUrl"],
        )
        self.registry.cancel_operation("operation-url")
        thread.join(timeout=1)

        with self.assertRaises(UnsupportedInteraction):
            self.registry.hold(
                "operation-url-secret",
                "workspace-1",
                "/workspace/private",
                ServerRequest(
                    "vendor-url-secret",
                    "mcpServer/elicitation/request",
                    {
                        "mode": "url",
                        "url": "https://fixture.invalid/approve?access_token=private",
                    },
                ),
            )
    def test_timeout_and_cancel_fail_closed(self) -> None:
        short = InteractionRegistry(default_timeout_seconds=0.02)
        started = time.monotonic()
        response = short.hold(
            "operation-5",
            "workspace-1",
            "/workspace/private",
            ServerRequest(
                "vendor-6", "mcpServer/elicitation/request", {"message": "Continue?"}
            ),
        )

        self.assertEqual({"action": "cancel"}, response)
        self.assertLess(time.monotonic() - started, 0.5)


if __name__ == "__main__":
    unittest.main()
