from __future__ import annotations

import sys
import threading
import time
import unittest
from pathlib import Path

from synvo_runner.protocol import (
    AppServerClient,
    EventBuffer,
    RunnerProtocolError,
    RunnerUnavailable,
)


FIXTURE = Path(__file__).parent / "fixtures" / "fake_app_server.py"


class AppServerClientTest(unittest.TestCase):
    def client(self, scenario: str) -> AppServerClient:
        return AppServerClient(
            command=[sys.executable, str(FIXTURE), scenario],
            request_timeout_seconds=1,
            shutdown_timeout_seconds=1,
        )

    def test_initialization_omits_experimental_opt_in(self) -> None:
        with self.client("normal") as client:
            self.assertTrue(client.initialized)

    def test_correlates_interleaved_response_notification_and_server_request(self) -> None:
        notifications: list[str] = []
        interactions: list[str] = []
        with self.client("interleave") as client:
            client.on_notification(lambda method, _params: notifications.append(method))

            def decide(request) -> dict[str, str]:
                interactions.append(request.method)
                return {"decision": "decline"}

            client.on_server_request(decide)
            result = client.request("test/interleave", {})

        self.assertEqual({"correlated": True}, result)
        self.assertEqual(["turn/started"], notifications)
        self.assertEqual(["item/commandExecution/requestApproval"], interactions)

    def test_malformed_json_and_early_exit_are_normalized(self) -> None:
        with self.client("malformed") as client:
            with self.assertRaises(RunnerProtocolError):
                client.request("test/malformed", {})
        with self.client("exit") as client:
            with self.assertRaises(RunnerUnavailable):
                client.request("test/exit", {})

    def test_timeout_and_close_are_bounded(self) -> None:
        started = time.monotonic()
        with self.client("hang") as client:
            with self.assertRaises(RunnerUnavailable):
                client.request("test/hang", {})
        self.assertLess(time.monotonic() - started, 3)


class EventBufferTest(unittest.TestCase):
    def test_backpressure_is_bounded_and_terminal_event_is_retained(self) -> None:
        buffer = EventBuffer(max_events=3)
        for index in range(10):
            buffer.publish({"kind": "progress", "sequence": index})
        buffer.publish({"kind": "completed", "sequence": 10}, terminal=True)

        events = buffer.snapshot(after_sequence=-1)

        self.assertLessEqual(len(events), 3)
        self.assertEqual("completed", events[-1]["kind"])
        self.assertGreater(buffer.dropped_count, 0)

    def test_wait_can_be_interrupted_without_orphaning_a_thread(self) -> None:
        buffer = EventBuffer(max_events=3)
        stopped = threading.Event()
        result: list[list[dict[str, object]]] = []

        thread = threading.Thread(
            target=lambda: result.append(buffer.wait(-1, stopped, timeout_seconds=5))
        )
        thread.start()
        stopped.set()
        buffer.wake()
        thread.join(timeout=1)

        self.assertFalse(thread.is_alive())
        self.assertEqual([[]], result)


if __name__ == "__main__":
    unittest.main()
