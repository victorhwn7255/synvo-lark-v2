from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any


class SafeMcpFixtureTest(unittest.TestCase):
    def test_read_and_elicitation_guarded_write_are_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            process = subprocess.Popen(
                [sys.executable, "fixtures/safe_mcp_server.py"],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                env={**os.environ, "SYNVO_MCP_FIXTURE_ROOT": directory},
            )
            try:
                self._send(process, 1, "tools/list", {})
                tools = self._read(process)["result"]["tools"]
                self.assertTrue(tools[0]["annotations"]["readOnlyHint"])
                self.assertEqual(
                    "elicitation-before-side-effect",
                    tools[1]["_meta"]["synvo/approvalBoundary"],
                )

                self._send(process, 2, "tools/call", {"name": "read_fixture"})
                self.assertIn("SYNVO_MCP_READ_OK", str(self._read(process)))

                self._send(
                    process, 3, "tools/call", {"name": "write_fixture_marker"}
                )
                elicitation = self._read(process)
                self.assertEqual("elicitation/create", elicitation["method"])
                marker = root / ".synvo-mcp-fixture-approved"
                self.assertFalse(marker.exists())
                self._respond(process, elicitation["id"], "decline", {})
                self.assertIn("DECLINED", str(self._read(process)))
                self.assertFalse(marker.exists())

                self._send(
                    process, 4, "tools/call", {"name": "write_fixture_marker"}
                )
                elicitation = self._read(process)
                self._respond(
                    process, elicitation["id"], "accept", {"confirm": True}
                )
                self.assertIn("SYNVO_MCP_WRITE_OK", str(self._read(process)))
                self.assertEqual("fixture-ok\n", marker.read_text(encoding="utf-8"))
            finally:
                if process.stdin is not None:
                    process.stdin.close()
                process.terminate()
                process.wait(timeout=2)
                if process.stdout is not None:
                    process.stdout.close()

    @staticmethod
    def _send(
        process: subprocess.Popen[str],
        request_id: int,
        method: str,
        params: dict[str, Any],
    ) -> None:
        assert process.stdin is not None
        process.stdin.write(
            json.dumps(
                {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
            )
            + "\n"
        )
        process.stdin.flush()

    @staticmethod
    def _respond(
        process: subprocess.Popen[str],
        request_id: int,
        action: str,
        content: dict[str, Any],
    ) -> None:
        assert process.stdin is not None
        process.stdin.write(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {"action": action, "content": content},
                }
            )
            + "\n"
        )
        process.stdin.flush()

    @staticmethod
    def _read(process: subprocess.Popen[str]) -> dict[str, Any]:
        assert process.stdout is not None
        line = process.stdout.readline()
        if not line:
            raise AssertionError("fixture exited before responding")
        value = json.loads(line)
        if not isinstance(value, dict):
            raise AssertionError("fixture response was not an object")
        return value


if __name__ == "__main__":
    unittest.main()
