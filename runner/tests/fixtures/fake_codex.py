#!/usr/bin/env python3
from __future__ import annotations

import os
import sys


if sys.argv[1:] == ["--version"]:
    print(f"codex-cli {os.environ.get('FAKE_CODEX_VERSION', '0.148.0')}")
    raise SystemExit(0)
if sys.argv[1:] == ["sandbox", "--", "true"]:
    if os.environ.get("FAKE_CODEX_SANDBOX_ERROR") == "true":
        print("sensitive sandbox diagnostic", file=sys.stderr)
        raise SystemExit(1)
    raise SystemExit(0)
raise SystemExit(2)
