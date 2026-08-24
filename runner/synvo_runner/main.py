"""Process entrypoint for the private Synvo Codex runner."""

from __future__ import annotations

import os
import signal
import threading

from .http_api import RunnerApplication, RunnerHttpServer
from .runtime import RunnerSettings, RuntimeFactory


def run() -> None:
    settings = RunnerSettings.from_environment(os.environ)
    engine = None
    if settings.enabled:
        assert settings.codex_home is not None
        settings.codex_home.mkdir(mode=0o700, parents=True, exist_ok=True)
        settings.codex_home.chmod(0o700)
        engine = RuntimeFactory(settings).create_engine()
        engine.start()
    application = RunnerApplication(enabled=settings.enabled, engine=engine)
    server = RunnerHttpServer((settings.host, settings.port), application)

    def stop(_signal: int, _frame: object) -> None:
        application.stopped.set()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        if engine is not None:
            engine.close()


if __name__ == "__main__":
    run()
