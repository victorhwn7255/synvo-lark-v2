# Pinned App Server capability matrix

Runtime: `@openai/codex` / `codex-cli 0.148.0`  
Model: `gpt-5.6-sol`  
Generated stable schema SHA-256:
`e5a20eb7211c21540a2d4e0106479285e13778e9c53d5837cfc735a71316a51e`

This matrix is part of the private runner contract. Schema presence alone does
not enable a capability. Runtime feature stage/default discovery, the isolated
runner-owned Codex home, explicit feature disables, and Synvo policy all apply.

## Exposed through Synvo vocabulary

| Synvo capability | Stable App Server surface used privately |
|---|---|
| Account and subscription limits | `account/read`, `account/rateLimits/read` and sanitized updates |
| Task create/read/resume/fork | `thread/start`, `thread/read`, `thread/resume`, `thread/fork` |
| Task rename/archive lifecycle | `thread/name/set`, `thread/archive`, `thread/unarchive`, `thread/delete` |
| Turn, follow-up, steering, stop | `turn/start`, `turn/steer`, `turn/interrupt` |
| Goal lifecycle | `thread/goal/set`, `thread/goal/get`, `thread/goal/clear` |
| Review | `review/start` and normalized review activity |
| Skills | `skills/list` plus stable skill input on `turn/start` |
| MCP inventory and agent use | `mcpServerStatus/list`, turn activity, stable MCP elicitation |
| Dynamic decisions | one-time file approval and allowlisted `mcpServer/elicitation/request`; stable command-elevation requests are declined privately |
| Ordered activity/results | stable turn/item/thread notifications normalized by the runner |

`approvalsReviewer: auto_review` is present in the stable schema but is not an
exposed Synvo capability. The 2026-08-23 behavioral gate showed that the
reviewer approved an outside-root write and a read-only fake-secret copy before
emitting any client approval request. The stable runtime therefore cannot put
Auto-review behind Synvo's deterministic workspace ceiling. Ask for Approval
remains the only supported reviewer mode.

`thread/list` is used privately only during task resume to distinguish a
persisted App Server thread from a zero-turn thread lost across runner restart.
The query is bounded to the configured workspace and `appServer` source, and
the runner compares only the stored opaque reference. Synvo task listing still
comes only from owner-scoped PostgreSQL mappings, so threads from other Codex
clients cannot be imported or exposed. Pin/unpin is Synvo metadata and has no
App Server call.

Startup enumerates the isolated Codex home's complete MCP inventory. Any
server outside the deployment allowlist, any plugin-provided server, or any
tool without a safe risk classification fails runner startup. A tool is
exposable only when its MCP annotations mark it read-only or its metadata
declares Synvo's `elicitation-before-side-effect` boundary; the latter still
requires the stable elicitation round trip and Java/H5 authorization before
the side effect.

Runner readiness also executes a silent `codex sandbox -- true` preflight.
Model discovery, authentication, and a responsive App Server are insufficient
if the deployed container cannot create the Bubblewrap namespace used by local
shell and file tools. The enabled Compose boundary installs the distribution
Bubblewrap package, runs non-root with every Linux capability dropped,
enforces `no-new-privileges`, limits the process count, and permits the
unprivileged namespace syscalls by disabling Docker's outer seccomp filter.
The inner Bubblewrap sandbox, selected App Server sandbox policy, configured
workspace bind mounts, network denial, and Synvo authorization remain in
force. Sandbox-preflight diagnostics are never returned to the application.

## Consumed internally

- `initialize` / `initialized`, exact CLI version verification,
  `model/list`, and `experimentalFeature/list` form the startup gate.
- App Server request IDs, item IDs, thread IDs, turn IDs, schemas, error
  records, and JSONL messages never cross the integration module.
- `serverRequest/resolved`, startup status, model reroute, compaction, usage,
  and process/error events are consumed for lifecycle and safe presentation.

## Stable runtime features required or retained

The runner reaches `ready` only when every enabled active feature reports the
exact `stable` stage. An enabled Beta, Experimental, UnderDevelopment,
Deprecated, unknown, or unpinned Removed stage is a compatibility failure. The
pinned runtime also returns 33 known inert compatibility records with stage
`removed`; they are explicitly disabled at launch where the runtime honors the
override, never counted as enabled capabilities, never exposed, and reported
separately as retired runtime records. The stable workflow features used
directly by Phase 3 (`code_mode_host`, `shell_tool`, `unified_exec`,
`shell_snapshot`, `goals`, `multi_agent`, `tool_call_mcp_elicitation`, and
`skill_search`) are also required to be present and enabled; their absence
cannot be deferred until a user invokes the missing capability.

| Feature | 0.148.0 stage/default | Phase 3 use |
|---|---|---|
| `code_mode_host` | Stable / enabled | Internal local execution bridge required by shell/file tools; not a Synvo product surface |
| `shell_tool` | Stable / enabled | Agent shell operations |
| `unified_exec` | Stable / enabled | Bounded command execution |
| `shell_snapshot` | Stable / enabled | Stable local shell behavior |
| `goals` | Stable / enabled | Task goals |
| `multi_agent` | Stable / enabled | App-Server-managed nested activity |
| `tool_call_mcp_elicitation` | Stable / enabled | MCP interaction boundary |
| `skill_search` | Stable / enabled | Configured skill discovery/use |
| `remote_compaction_v2` | Stable / enabled | App Server-owned compaction |
| `enable_request_compression` | Stable / enabled | Internal transport optimization |

`multi_agent_v2`, `memories`, and `secret_auth_storage` are Stable but disabled
by default and are not enabled by Phase 3.

## Explicitly disabled even though Stable

The runner passes `features.<name>=false` for these out-of-scope features:

- apps, plugins, plugin sharing, recommended/remote plugins;
- browser use, external/full-CDP browser access, in-app browser;
- computer use, image generation, image viewing;
- hooks, in-app updates, and fast mode;
- skill dependency installation, workspace dependency loading;
- tool suggestion and unbounded connection retries.

These surfaces are not implied by the phrase “full stable workflow capability
set”; the approved specification explicitly excludes their product categories.
The local Code Mode host remains enabled solely as private App Server execution
infrastructure. Synvo does not expose remote Code Mode configuration or a
direct Code Mode API.

## Below-Stable and unavailable

The following records can appear in generated schemas but are not enabled,
exposed, or persisted:

- `default_mode_request_user_input` — UnderDevelopment, disabled;
- `request_permissions_tool` — UnderDevelopment, disabled;
- `network_proxy` — Beta, disabled;
- `standalone_web_search` — UnderDevelopment, disabled;
- web-search compatibility paths — Deprecated/removed, disabled;
- dynamic tools, experimental process control, experimental permission
  profiles, and Plan collaboration-mode control.

The pinned `0.148.0` inventory also contains 33 inert `removed` records. The
exact names are pinned in `CapabilityPolicy.PINNED_REMOVED_FEATURES`; they
include retired aliases for old patch, app, collaboration, search, transport,
TUI, Windows, and sandbox paths. They are allowlisted only as exact retired
sentinels for this pinned runtime. An additional Removed record or a maturity
change fails startup. Eight initially report enabled; explicit false overrides
disable three, while the remaining five stay inert Removed records and are
reported separately rather than treated as capabilities.

The stable runtime rejected an experimental method with JSON-RPC `-32600`,
rejected the granular permission-policy shape, emitted no generic input or
permission server request, and emitted no host-scoped network approval. Network
access therefore remains off and a network-enabling command request fails
closed.

## Intentionally irrelevant endpoints

Synvo does not expose direct `command/exec` or filesystem-manager UI,
configuration writes, marketplace/plugin administration, external-agent
imports, feedback/telemetry administration, account credit consumption,
remote control, realtime voice/audio, Windows setup, or diagnostic exports.

## Spike evidence

The redacted disposable spike verified exact-model execution without reroute,
stable-only initialization, thread/turn/goal/review lifecycle, streaming,
steering, interruption, skills, sandbox boundaries, network denial, and real
command/file server requests. Current Synvo policy declines command elevation
privately and exposes one-time decisions only for bounded file and MCP
interactions. An isolated harmless MCP fixture verified inventory, read-only
tool execution, and elicitation-backed decline and acceptance. The
normal user Codex home inherited unrelated MCP/plugin configuration; using a
dedicated runner-owned Codex home is therefore mandatory.

A later disposable Auto-review probe accepted the stable reviewer value and
kept network access blocked, but also approved an outside-root write and a
read-only fake-secret copy. Genuine MCP elicitation remained client-facing.
Because the test used only generated paths and a fake canary, it required no
real secret inspection. Those two filesystem effects fail the Phase 3 hard
gate and keep Safe Approve disabled.

A later production-topology verification invalidated the apparent
session-decision result. The disposable probe answered command requests with a
broader command-policy amendment and used `acceptForSession` only for file
requests. With the human reviewer and `approvalPolicy: on-request`, routine
work inside the selected sandbox runs without a prompt. Because stable command
requests do not expose enough requested-authority detail for deterministic
authorization, Synvo declines every command-elevation request privately and
never sends `acceptForSession`, a command-prefix amendment, or another command
grant. One-time H5 decisions remain available only for independently
classifiable file and allowlisted MCP interactions.
