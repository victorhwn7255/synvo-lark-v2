from __future__ import annotations

import unittest

from synvo_runner.normalization import EventNormalizer, ProtocolIncompatibility


class EventNormalizerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.normalizer = EventNormalizer(max_text_bytes=24)

    def test_preserves_arbitrary_non_empty_message_fragments(self) -> None:
        event = self.normalizer.normalize_notification(
            "item/agentMessage/delta",
            {
                "threadId": "engine-thread",
                "turnId": "engine-turn",
                "itemId": "item-1",
                "delta": " \n",
            },
        )

        self.assertEqual("message_delta", event.kind)
        self.assertEqual(" \n", event.payload["text"])
        self.assertNotIn("threadId", event.payload)

    def test_private_reasoning_text_is_not_exposed(self) -> None:
        self.assertIsNone(
            self.normalizer.normalize_notification(
                "item/reasoning/textDelta", {"delta": "private reasoning"}
            )
        )
        summary = self.normalizer.normalize_notification(
            "item/reasoning/summaryTextDelta", {"delta": "Safe summary"}
        )
        self.assertEqual("Safe summary", summary.payload["text"])

    def test_bounds_output_and_redacts_secret_like_values(self) -> None:
        event = self.normalizer.normalize_notification(
            "item/commandExecution/outputDelta",
            {
                "threadId": "engine-thread",
                "turnId": "engine-turn",
                "itemId": "item-1",
                "delta": "TOKEN=super-sensitive-value and more output",
            },
        )

        self.assertEqual("command_output", event.kind)
        self.assertNotIn("super-sensitive-value", event.payload["text"])
        self.assertTrue(event.payload["truncated"])

    def test_normalizes_complete_workspace_paths_to_relative_references(self) -> None:
        event = EventNormalizer(max_text_bytes=200).normalize_notification(
            "item/completed",
            {
                "item": {
                    "id": "item-message",
                    "type": "agentMessage",
                    "text": (
                        "Sources: [README.md](/workspace/private/README.md:271) "
                        "from /workspace/private."
                    ),
                }
            },
            workspace_root="/workspace/private",
        )

        self.assertEqual(
            "Sources: [README.md](./README.md:271) from workspace root.",
            event.payload["text"],
        )
        self.assertNotIn("/workspace/private", event.payload["text"])

    def test_normalizes_supported_nested_and_review_activity(self) -> None:
        nested = self.normalizer.normalize_notification(
            "item/started",
            {
                "threadId": "engine-thread",
                "turnId": "engine-turn",
                "item": {
                    "id": "item-2",
                    "type": "subAgentActivity",
                    "status": "running",
                },
            },
        )
        review = self.normalizer.normalize_notification(
            "item/completed",
            {
                "threadId": "engine-thread",
                "turnId": "engine-turn",
                "item": {"id": "item-3", "type": "enteredReviewMode"},
            },
        )

        self.assertEqual("nested_activity_started", nested.kind)
        self.assertEqual("review_entered", review.kind)

    def test_empty_agent_message_start_stays_inside_the_vendor_adapter(self) -> None:
        started = self.normalizer.normalize_notification(
            "item/started",
            {
                "threadId": "engine-thread",
                "turnId": "engine-turn",
                "item": {"id": "item-message", "type": "agentMessage"},
            },
        )
        completed = self.normalizer.normalize_notification(
            "item/completed",
            {
                "threadId": "engine-thread",
                "turnId": "engine-turn",
                "item": {
                    "id": "item-message",
                    "type": "agentMessage",
                    "text": "Done",
                },
            },
        )

        self.assertIsNone(started)
        self.assertEqual("message_completed", completed.kind)

    def test_unknown_optional_notification_is_ignored(self) -> None:
        self.assertIsNone(
            self.normalizer.normalize_notification("diagnostic/optional", {"new": True})
        )

    def test_normalizes_usage_limit_and_authentication_terminal_failures(self) -> None:
        usage_limited = self.normalizer.normalize_notification(
            "turn/completed",
            {
                "turn": {
                    "status": "failed",
                    "error": {"codexErrorInfo": "usageLimitExceeded"},
                }
            },
        )
        authentication_required = self.normalizer.normalize_notification(
            "turn/completed",
            {
                "turn": {
                    "status": "failed",
                    "error": {"codexErrorInfo": "unauthorized"},
                }
            },
        )

        self.assertEqual("usageLimited", usage_limited.payload["status"])
        self.assertEqual(
            "authenticationRequired", authentication_required.payload["status"]
        )

    def test_unknown_or_excluded_item_variant_fails_closed(self) -> None:
        with self.assertRaises(ProtocolIncompatibility):
            self.normalizer.normalize_notification(
                "item/started",
                {
                    "threadId": "engine-thread",
                    "turnId": "engine-turn",
                    "item": {"id": "item-4", "type": "futureTool"},
                },
            )
        with self.assertRaises(ProtocolIncompatibility):
            self.normalizer.normalize_notification(
                "item/started",
                {
                    "threadId": "engine-thread",
                    "turnId": "engine-turn",
                    "item": {"id": "item-5", "type": "webSearch"},
                },
            )


if __name__ == "__main__":
    unittest.main()
