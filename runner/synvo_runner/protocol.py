"""Bounded JSONL client that hides the App Server process and wire protocol."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import threading
import time
from collections import deque
from concurrent.futures import Future, TimeoutError as FutureTimeout
from dataclasses import dataclass
from typing import Any, Callable, Iterable, Mapping


class RunnerError(RuntimeError):
    """Base normalized runner failure safe to expose to its Java adapter."""


class RunnerUnavailable(RunnerError):
    """The App Server process or transport is unavailable."""


class RunnerProtocolError(RunnerError):
    """The pinned App Server violated the expected stable protocol."""


@dataclass(frozen=True)
class ServerRequest:
    request_id: int | str
    method: str
    params: Mapping[str, Any]


NotificationHandler = Callable[[str, Mapping[str, Any]], None]
ServerRequestHandler = Callable[[ServerRequest], Mapping[str, Any]]
FailureHandler = Callable[[RunnerError], None]


class AppServerClient:
    """Own one local App Server process and correlate all JSONL traffic."""

    def __init__(
        self,
        *,
        command: Iterable[str],
        environment: Mapping[str, str] | None = None,
        request_timeout_seconds: float = 30,
        shutdown_timeout_seconds: float = 5,
        max_pending_requests: int = 128,
        max_server_requests: int = 16,
    ) -> None:
        self._command = tuple(command)
        if not self._command:
            raise ValueError("App Server command is required")
        self._environment = dict(environment or {})
        self._request_timeout = request_timeout_seconds
        self._shutdown_timeout = shutdown_timeout_seconds
        self._max_pending_requests = max_pending_requests
        self._server_request_slots = threading.BoundedSemaphore(max_server_requests)
        self._process: subprocess.Popen[str] | None = None
        self._pending: dict[int, Future[dict[str, Any]]] = {}
        self._pending_lock = threading.Lock()
        self._write_lock = threading.Lock()
        self._next_id = 1
        self._closing = threading.Event()
        self._notification_handler: NotificationHandler = lambda _method, _params: None
        self._server_request_handler: ServerRequestHandler | None = None
        self._failure_handler: FailureHandler = lambda _error: None
        self._failure_reported = threading.Event()
        self._reader_thread: threading.Thread | None = None
        self._stderr_thread: threading.Thread | None = None
        self._stderr_line_count = 0
        self._initialized = False

    @property
    def initialized(self) -> bool:
        return self._initialized

    @property
    def stderr_line_count(self) -> int:
        return self._stderr_line_count

    def __enter__(self) -> "AppServerClient":
        self.start()
        return self

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        self.close()

    def start(self) -> None:
        if self._process is not None:
            return
        environment = {**os.environ, **self._environment}
        self._process = subprocess.Popen(
            self._command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            bufsize=1,
            start_new_session=True,
            env=environment,
        )
        self._reader_thread = threading.Thread(
            target=self._read_stdout, name="app-server-stdout", daemon=True
        )
        self._stderr_thread = threading.Thread(
            target=self._drain_stderr, name="app-server-stderr", daemon=True
        )
        self._reader_thread.start()
        self._stderr_thread.start()
        try:
            self.request(
                "initialize",
                {
                    "clientInfo": {
                        "name": "synvo_codex_runner",
                        "title": "Synvo Codex Runner",
                        "version": "0.1.0",
                    }
                },
            )
            self._initialized = True
            self.notify("initialized", {})
        except Exception:
            self.close()
            raise

    def on_notification(self, handler: NotificationHandler) -> None:
        self._notification_handler = handler

    def on_server_request(self, handler: ServerRequestHandler) -> None:
        self._server_request_handler = handler

    def on_failure(self, handler: FailureHandler) -> None:
        self._failure_handler = handler

    def request(
        self,
        method: str,
        params: Mapping[str, Any] | None,
        *,
        timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        process = self._require_process()
        if process.poll() is not None:
            raise RunnerUnavailable("App Server exited")
        with self._pending_lock:
            if len(self._pending) >= self._max_pending_requests:
                raise RunnerUnavailable("App Server request capacity reached")
            request_id = self._next_id
            self._next_id += 1
            future: Future[dict[str, Any]] = Future()
            self._pending[request_id] = future
        try:
            self._write({"id": request_id, "method": method, "params": params or {}})
            try:
                message = future.result(
                    timeout=self._request_timeout
                    if timeout_seconds is None
                    else timeout_seconds
                )
            except FutureTimeout as error:
                raise RunnerUnavailable("App Server request timed out") from error
        finally:
            with self._pending_lock:
                self._pending.pop(request_id, None)

        error_record = message.get("error")
        if isinstance(error_record, dict):
            code = error_record.get("code")
            raise RunnerProtocolError(f"App Server rejected request code={code}")
        result = message.get("result")
        if result is None:
            return {}
        if not isinstance(result, dict):
            raise RunnerProtocolError("App Server returned an invalid result")
        return result

    def notify(self, method: str, params: Mapping[str, Any] | None) -> None:
        self._write({"method": method, "params": params or {}})

    def close(self) -> None:
        process = self._process
        if process is None:
            return
        self._closing.set()
        if process.stdin is not None:
            try:
                process.stdin.close()
            except OSError:
                pass
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            except PermissionError:
                process.terminate()
            try:
                process.wait(timeout=self._shutdown_timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except PermissionError:
                    process.kill()
                process.wait(timeout=self._shutdown_timeout)
        self._fail_pending(RunnerUnavailable("App Server stopped"))
        for thread in (self._reader_thread, self._stderr_thread):
            if thread is not None and thread is not threading.current_thread():
                thread.join(timeout=self._shutdown_timeout)
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                stream.close()
        self._process = None
        self._initialized = False

    def _require_process(self) -> subprocess.Popen[str]:
        if self._process is None:
            raise RunnerUnavailable("App Server is not running")
        return self._process

    def _write(self, message: Mapping[str, Any]) -> None:
        process = self._require_process()
        if process.stdin is None or process.poll() is not None:
            raise RunnerUnavailable("App Server input is unavailable")
        encoded = json.dumps(message, separators=(",", ":"), ensure_ascii=True)
        try:
            with self._write_lock:
                process.stdin.write(encoded + "\n")
                process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise RunnerUnavailable("App Server input failed") from error

    def _read_stdout(self) -> None:
        process = self._require_process()
        assert process.stdout is not None
        try:
            for line in process.stdout:
                try:
                    message = json.loads(line)
                except json.JSONDecodeError as error:
                    failure = RunnerProtocolError("App Server emitted malformed JSON")
                    self._fail_pending(failure)
                    self._report_failure(failure)
                    self._terminate_after_protocol_failure()
                    return
                if not isinstance(message, dict):
                    self._fail_pending(
                        RunnerProtocolError("App Server emitted a non-object message")
                    )
                    self._report_failure(
                        RunnerProtocolError("App Server emitted a non-object message")
                    )
                    self._terminate_after_protocol_failure()
                    return
                self._route(message)
        finally:
            if not self._closing.is_set():
                failure = RunnerUnavailable("App Server exited unexpectedly")
                self._fail_pending(failure)
                self._report_failure(failure)

    def _route(self, message: dict[str, Any]) -> None:
        request_id = message.get("id")
        if request_id is not None and ("result" in message or "error" in message):
            with self._pending_lock:
                future = self._pending.get(request_id)
            if future is not None and not future.done():
                future.set_result(message)
            return
        method = message.get("method")
        if not isinstance(method, str):
            self._fail_pending(RunnerProtocolError("App Server message has no method"))
            return
        params = message.get("params")
        safe_params = params if isinstance(params, dict) else {}
        if request_id is not None:
            self._dispatch_server_request(request_id, method, safe_params)
            return
        try:
            self._notification_handler(method, safe_params)
        except Exception:
            return

    def _dispatch_server_request(
        self, request_id: int | str, method: str, params: Mapping[str, Any]
    ) -> None:
        if not self._server_request_slots.acquire(blocking=False):
            self._write(
                {
                    "id": request_id,
                    "error": {"code": -32000, "message": "interaction capacity reached"},
                }
            )
            return

        def answer() -> None:
            try:
                handler = self._server_request_handler
                if handler is None:
                    response: Mapping[str, Any] = self._fail_closed_response(method)
                else:
                    response = handler(ServerRequest(request_id, method, params))
                self._write({"id": request_id, "result": dict(response)})
            except Exception:
                try:
                    self._write(
                        {
                            "id": request_id,
                            "error": {"code": -32000, "message": "interaction failed"},
                        }
                    )
                except RunnerError:
                    pass
            finally:
                self._server_request_slots.release()

        threading.Thread(target=answer, name="app-server-request", daemon=True).start()

    @staticmethod
    def _fail_closed_response(method: str) -> Mapping[str, Any]:
        if method in {
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
        }:
            return {"decision": "decline"}
        if method == "mcpServer/elicitation/request":
            return {"action": "cancel"}
        raise RunnerProtocolError("unsupported App Server request")

    def _drain_stderr(self) -> None:
        process = self._require_process()
        assert process.stderr is not None
        for _line in process.stderr:
            self._stderr_line_count += 1

    def _fail_pending(self, error: RunnerError) -> None:
        with self._pending_lock:
            futures = tuple(self._pending.values())
        for future in futures:
            if not future.done():
                future.set_exception(error)

    def _terminate_after_protocol_failure(self) -> None:
        process = self._process
        if process is not None and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            except PermissionError:
                process.terminate()

    def _report_failure(self, error: RunnerError) -> None:
        if self._failure_reported.is_set():
            return
        self._failure_reported.set()
        try:
            self._failure_handler(error)
        except Exception:
            return


class EventBuffer:
    """Bounded replay/wait buffer for normalized, already-redacted activity."""

    def __init__(self, *, max_events: int) -> None:
        if max_events < 1:
            raise ValueError("max_events must be positive")
        self._events: deque[dict[str, Any]] = deque(maxlen=max_events)
        self._condition = threading.Condition()
        self._terminal = False
        self._dropped_count = 0

    @property
    def dropped_count(self) -> int:
        with self._condition:
            return self._dropped_count

    def publish(self, event: Mapping[str, Any], *, terminal: bool = False) -> None:
        with self._condition:
            if self._terminal:
                return
            if len(self._events) == self._events.maxlen:
                self._dropped_count += 1
            self._events.append(dict(event))
            if terminal:
                self._terminal = True
            self._condition.notify_all()

    def snapshot(self, *, after_sequence: int) -> list[dict[str, Any]]:
        with self._condition:
            return [
                dict(event)
                for event in self._events
                if isinstance(event.get("sequence"), int)
                and event["sequence"] > after_sequence
            ]

    def wait(
        self,
        after_sequence: int,
        stopped: threading.Event,
        *,
        timeout_seconds: float,
    ) -> list[dict[str, Any]]:
        deadline = time.monotonic() + timeout_seconds
        with self._condition:
            while True:
                events = self.snapshot(after_sequence=after_sequence)
                if events or stopped.is_set() or self._terminal:
                    return events
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return []
                self._condition.wait(timeout=remaining)

    def wake(self) -> None:
        with self._condition:
            self._condition.notify_all()
