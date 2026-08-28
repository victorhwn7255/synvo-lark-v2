"""Small private HTTP contract consumed only by Synvo's Java adapter."""

from __future__ import annotations

import json
import re
import threading
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Mapping
from urllib.parse import parse_qs, unquote, urlsplit

from .engine import EngineBusy, EngineFailure, RunMode, Workspace
from .interactions import InteractionConflict
from .protocol import RunnerError


@dataclass(frozen=True)
class RunnerResponse:
    status: int
    payload: Mapping[str, Any] | None = None


class RunnerApplication:
    """Route a deliberately compressed Synvo engine contract."""

    MAX_BODY_BYTES = 64 * 1024

    def __init__(self, *, enabled: bool, engine: Any | None) -> None:
        if enabled and engine is None:
            raise ValueError("enabled runner requires an engine")
        self.enabled = enabled
        self.engine = engine
        self.stopped = threading.Event()

    def dispatch(
        self,
        method: str,
        target: str,
        body: Mapping[str, Any],
    ) -> RunnerResponse:
        parsed = urlsplit(target)
        path = parsed.path
        if method == "GET" and path == "/health":
            state = "disabled"
            if self.enabled:
                state = (
                    self.engine.health()
                    if self.engine is not None
                    else "unavailable"
                )
            return RunnerResponse(
                HTTPStatus.OK,
                {"state": state},
            )
        if not self.enabled or self.engine is None:
            return self._error(HTTPStatus.SERVICE_UNAVAILABLE, "RUNNER_DISABLED")
        try:
            if method == "GET" and path == "/v1/capabilities":
                return RunnerResponse(HTTPStatus.OK, self.engine.capabilities())
            if method == "GET" and path == "/v1/account":
                return RunnerResponse(HTTPStatus.OK, self.engine.account())
            if method == "POST" and path == "/v1/tasks":
                self._exact_fields(body, {"workspaceId", "workspacePath", "mode"})
                workspace = self._workspace(body)
                task = self.engine.create_task(workspace, self._mode(body))
                return RunnerResponse(HTTPStatus.CREATED, task.as_public_dict())

            task_match = re.fullmatch(r"/v1/tasks/([^/]+)/([^/]+)", path)
            if task_match:
                engine_ref = unquote(task_match.group(1))
                action = task_match.group(2)
                if method == "POST" and action == "fork":
                    self._exact_fields(body, {"workspaceId", "workspacePath"})
                    task = self.engine.fork_task(engine_ref, self._workspace(body))
                    return RunnerResponse(HTTPStatus.CREATED, task.as_public_dict())
                if method == "POST" and action == "resume":
                    self._exact_fields(body, {"workspaceId", "workspacePath"})
                    task = self.engine.resume_task(engine_ref, self._workspace(body))
                    return RunnerResponse(HTTPStatus.OK, task.as_public_dict())
                if method == "POST" and action == "turns":
                    self._required_fields(
                        body,
                        {
                            "workspaceId",
                            "workspacePath",
                            "mode",
                            "text",
                            "effort",
                        },
                        {"skillName"},
                    )
                    text = self._required_string(body, "text", max_length=100_000)
                    effort = self._required_string(body, "effort", max_length=32)
                    skill_name = body.get("skillName")
                    if skill_name is not None and (
                        not isinstance(skill_name, str)
                        or not skill_name.strip()
                        or len(skill_name) > 200
                    ):
                        raise ValueError("skillName is invalid")
                    operation = self.engine.start_turn(
                        engine_ref,
                        self._workspace(body),
                        self._mode(body),
                        text,
                        effort,
                        skill_name=skill_name,
                    )
                    return RunnerResponse(
                        HTTPStatus.ACCEPTED,
                        {"operationId": operation.operation_id},
                    )
                if method == "POST" and action == "reviews":
                    self._exact_fields(
                        body, {"workspaceId", "workspacePath", "target"}
                    )
                    target = body.get("target")
                    if not isinstance(target, dict):
                        raise ValueError("review target must be an object")
                    operation = self.engine.start_review(
                        engine_ref, self._workspace(body), target
                    )
                    return RunnerResponse(
                        HTTPStatus.ACCEPTED,
                        {"operationId": operation.operation_id},
                    )
                if method == "POST" and action == "rename":
                    self._exact_fields(body, {"name"})
                    self.engine.rename_task(
                        engine_ref, self._required_string(body, "name", max_length=200)
                    )
                    return RunnerResponse(HTTPStatus.NO_CONTENT)
                if method == "POST" and action in {"archive", "unarchive"}:
                    self._exact_fields(body, set())
                    getattr(self.engine, f"{action}_task")(engine_ref)
                    return RunnerResponse(HTTPStatus.NO_CONTENT)
                if method == "POST" and action == "inventory":
                    self._exact_fields(body, {"workspaceId", "workspacePath"})
                    workspace = self._workspace(body)
                    return RunnerResponse(
                        HTTPStatus.OK,
                        {
                            "skills": self.engine.skills(workspace),
                            "mcpServers": self.engine.mcp_status(engine_ref),
                        },
                    )
                if action == "goal":
                    if method == "POST":
                        self._required_fields(body, {"objective"}, {"status"})
                        status = body.get("status")
                        if status not in {None, "active", "paused"}:
                            raise ValueError("status is invalid")
                        self.engine.set_goal(
                            engine_ref,
                            self._required_string(
                                body, "objective", max_length=10_000
                            ),
                            status,
                        )
                        return RunnerResponse(HTTPStatus.NO_CONTENT)
                    if method == "GET":
                        goal = self.engine.goal(engine_ref)
                        return RunnerResponse(
                            HTTPStatus.OK, goal if goal is not None else {"goal": None}
                        )
                    if method == "DELETE":
                        self._exact_fields(body, set())
                        self.engine.clear_goal(engine_ref)
                        return RunnerResponse(HTTPStatus.NO_CONTENT)

            operation_match = re.fullmatch(r"/v1/operations/([^/]+)/([^/]+)", path)
            if operation_match:
                operation_id = unquote(operation_match.group(1))
                action = operation_match.group(2)
                if method == "GET" and action == "events":
                    after = self._query_integer(parsed.query, "after", -1)
                    operation = self.engine.operation(operation_id)
                    events = operation.wait_events(
                        after,
                        self.stopped,
                        timeout_seconds=20,
                    )
                    return RunnerResponse(
                        HTTPStatus.OK,
                        {"events": events, "terminal": operation.terminal},
                    )
                if method == "GET" and action == "interactions":
                    return RunnerResponse(
                        HTTPStatus.OK,
                        {
                            "interactions": self.engine.pending_interactions(
                                operation_id
                            )
                        },
                    )
                if method == "POST" and action == "decisions":
                    allowed = {"interactionId", "decision", "content"}
                    self._fields_subset(body, allowed)
                    interaction_id = self._required_string(
                        body, "interactionId", max_length=128
                    )
                    decision = self._required_string(
                        body, "decision", max_length=32
                    )
                    content = body.get("content")
                    if content is not None and not isinstance(content, dict):
                        raise ValueError("content must be an object")
                    self.engine.decide(
                        operation_id, interaction_id, decision, content
                    )
                    return RunnerResponse(HTTPStatus.NO_CONTENT)
                if method == "POST" and action == "stop":
                    self._exact_fields(body, set())
                    self.engine.stop(operation_id)
                    return RunnerResponse(HTTPStatus.NO_CONTENT)
                if method == "POST" and action == "steer":
                    self._exact_fields(body, {"text"})
                    self.engine.steer(
                        operation_id,
                        self._required_string(body, "text", max_length=100_000),
                    )
                    return RunnerResponse(HTTPStatus.NO_CONTENT)

            task_delete = re.fullmatch(r"/v1/tasks/([^/]+)", path)
            if method == "DELETE" and task_delete:
                self._exact_fields(body, set())
                self.engine.delete_task(unquote(task_delete.group(1)))
                return RunnerResponse(HTTPStatus.NO_CONTENT)
            return self._error(HTTPStatus.NOT_FOUND, "NOT_FOUND")
        except EngineBusy:
            return self._error(HTTPStatus.CONFLICT, "ENGINE_BUSY")
        except (KeyError, LookupError):
            return self._error(HTTPStatus.NOT_FOUND, "NOT_FOUND")
        except InteractionConflict:
            return self._error(HTTPStatus.CONFLICT, "INTERACTION_CONFLICT")
        except (ValueError, EngineFailure):
            return self._error(HTTPStatus.BAD_REQUEST, "INVALID_REQUEST")
        except RunnerError:
            return self._error(HTTPStatus.SERVICE_UNAVAILABLE, "RUNNER_UNAVAILABLE")

    @staticmethod
    def _error(status: int, code: str) -> RunnerResponse:
        return RunnerResponse(status, {"error": code})

    @staticmethod
    def _fields_subset(body: Mapping[str, Any], allowed: set[str]) -> None:
        if not set(body).issubset(allowed):
            raise ValueError("unknown request field")

    @classmethod
    def _exact_fields(cls, body: Mapping[str, Any], expected: set[str]) -> None:
        cls._fields_subset(body, expected)
        missing = expected - set(body)
        if missing:
            raise ValueError("missing request field")

    @classmethod
    def _required_fields(
        cls,
        body: Mapping[str, Any],
        required: set[str],
        optional: set[str],
    ) -> None:
        cls._fields_subset(body, required | optional)
        if required - set(body):
            raise ValueError("missing request field")

    @staticmethod
    def _required_string(
        body: Mapping[str, Any], name: str, *, max_length: int
    ) -> str:
        value = body.get(name)
        if not isinstance(value, str) or not value.strip() or len(value) > max_length:
            raise ValueError(f"{name} is invalid")
        return value

    @classmethod
    def _workspace(cls, body: Mapping[str, Any]) -> Workspace:
        return Workspace(
            cls._required_string(body, "workspaceId", max_length=100),
            cls._required_string(body, "workspacePath", max_length=4_096),
        )

    @classmethod
    def _mode(cls, body: Mapping[str, Any]) -> RunMode:
        try:
            return RunMode(cls._required_string(body, "mode", max_length=32))
        except ValueError as error:
            raise ValueError("mode is invalid") from error

    @staticmethod
    def _query_integer(query: str, name: str, default: int) -> int:
        values = parse_qs(query).get(name)
        if not values:
            return default
        try:
            return int(values[0])
        except ValueError as error:
            raise ValueError("query value is invalid") from error


class RunnerRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def application(self) -> RunnerApplication:
        return self.server.application  # type: ignore[attr-defined,no-any-return]

    def do_GET(self) -> None:
        self._handle("GET")

    def do_POST(self) -> None:
        self._handle("POST")

    def do_DELETE(self) -> None:
        self._handle("DELETE")

    def _handle(self, method: str) -> None:
        try:
            body = self._read_body()
        except BodyTooLarge:
            self._write(RunnerResponse(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "BODY_TOO_LARGE"}))
            return
        except ValueError:
            self._write(RunnerResponse(HTTPStatus.BAD_REQUEST, {"error": "INVALID_JSON"}))
            return
        self._write(self.application.dispatch(method, self.path, body))

    def _read_body(self) -> Mapping[str, Any]:
        raw_length = self.headers.get("Content-Length", "0")
        try:
            length = int(raw_length)
        except ValueError as error:
            raise ValueError("invalid content length") from error
        if length < 0 or length > self.application.MAX_BODY_BYTES:
            raise BodyTooLarge
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("request body must be an object")
        return value

    def _write(self, response: RunnerResponse) -> None:
        encoded = (
            json.dumps(response.payload, separators=(",", ":")).encode("utf-8")
            if response.payload is not None
            else b""
        )
        self.send_response(response.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        if encoded:
            self.wfile.write(encoded)

    def log_message(self, _format: str, *_args: object) -> None:
        return


class RunnerHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], application: RunnerApplication) -> None:
        self.application = application
        super().__init__(address, RunnerRequestHandler)

    def shutdown(self) -> None:
        self.application.stopped.set()
        super().shutdown()


class BodyTooLarge(RuntimeError):
    pass
