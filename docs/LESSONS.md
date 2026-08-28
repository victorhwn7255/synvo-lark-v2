# Synvo AI Assistant — Development Lessons

## Purpose

This document records confirmed mistakes, their root causes, the solutions that
worked, and the rules that should prevent recurrence. It gives maintainers and
AI coding agents durable context that may not be obvious from the current code.

This is not a bug tracker, change log, phase specification, or debugging dump.
Add an entry only when it captures a reusable engineering lesson. Do not include
credentials, tokens, sensitive enterprise content, unredacted prompts, personal
data, or large logs.

## Entry Template

```md
## YYYY-MM-DD — Short descriptive title

### Symptom
What users or tests observed.

### Root cause
The confirmed technical cause.

### Why it was missed
The assumption or test gap that allowed the issue.

### Resolution
The smallest change that resolved the cause.

### Preventive rule
A concrete rule future maintainers and agents should follow.

### Verification
Tests and live evidence proving the resolution.

### Relevant areas
Important files, modules, or specifications.
```

## 2026-08-18 — Streaming chunks are arbitrary text fragments

### Symptom

A long Nemotron response stopped after a small amount of text and H5 displayed
the generic safe failure state.

### Root cause

Nemotron emitted a valid whitespace-only Markdown chunk such as `\n\n` between
paragraphs. `AgentLifecycleEvent` validated each content delta with
`StringUtils.hasText()`, which rejects strings containing only whitespace, and
raised `IllegalArgumentException` inside the streaming callback.

### Why it was missed

Tests used chunks that each contained visible characters. They verified the
assembled response but did not model arbitrary provider chunk boundaries or a
standalone whitespace chunk.

### Resolution

Validate content deltas with `StringUtils.hasLength()`. Empty chunks remain
invalid, while spaces and line breaks are preserved as legitimate response
content.

### Preventive rule

Treat model-stream chunks as arbitrary transport fragments. Never assume one
chunk is a complete token, word, sentence, paragraph, or Markdown element.
Validate and render the assembled response, and preserve all non-empty chunks.

### Verification

- Agent Core unit coverage includes a standalone `\n\n` delta.
- PostgreSQL integration coverage persists and reloads the whitespace chunk.
- The complete backend suite passed with 163 tests.
- A long uninterrupted response completed successfully in the live Lark H5
  application.

### Relevant areas

- `backend/src/main/java/synvo/agent/AgentLifecycleEvent.java`
- `backend/src/test/java/synvo/agent/SynvoAgentCoreTests.java`
- `backend/src/test/java/synvo/SynvoApplicationTests.java`
- `docs/specs/phase-2-agentic-conversation.md`

## 2026-08-18 — Do not classify output-consumer failures as provider failures

### Symptom

Failures from the application callback used to persist or publish streamed
content appeared as `MODEL_PROVIDER_FAILURE`, making model transport and local
application failures indistinguishable.

### Root cause

`NemotronModelGateway` wrapped the complete reactive pipeline in one broad
`RuntimeException` handler. Exceptions thrown by `onDelta` therefore crossed
the model-gateway boundary and were incorrectly converted into provider
failures.

### Why it was missed

The provider stream and its consumer were treated as one failure domain. Tests
covered provider failure but did not inject a failure from the content consumer.

### Resolution

Mark exceptions originating from the output consumer, unwrap them at the model
gateway boundary, and allow the application coordinator to classify them as
local execution failures. Log only safe exception class chains, never messages,
prompts, response content, or credentials.

### Preventive rule

Adapters must preserve failure ownership. A provider gateway may translate
provider failures, but it must not relabel exceptions raised by application
callbacks, persistence, event publication, or policy enforcement.

### Verification

- Gateway tests distinguish provider transport failure from consumer failure.
- Live diagnostics identified the whitespace validation exception as
  `AGENT_EXECUTION_FAILED` instead of incorrectly retrying the model.

### Relevant areas

- `backend/src/main/java/synvo/agent/model/NemotronModelGateway.java`
- `backend/src/main/java/synvo/agent/ConversationRunCoordinator.java`
- `backend/src/test/java/synvo/agent/model/NemotronModelGatewayTests.java`

## 2026-08-18 — Recovery must use a different path from the failed mechanism

### Symptom

An interrupted Nemotron stream cleared the partial response and retried, but
the retry could fail in the same way because it opened another identical stream.

### Root cause

The initial recovery strategy repeated the failing transport rather than using
an independent recovery path.

### Why it was missed

The fake gateway test made the second stream succeed deterministically. It
proved state reset behavior but did not represent repeated failures of the same
external streaming mechanism.

### Resolution

Use one streaming attempt for responsive output. If the provider stream fails,
clear partial content and perform one bounded non-streaming generation in the
same assistant turn. If that independent fallback also fails, expose one safe
terminal error.

### Preventive rule

A retry is useful only when it addresses a transient condition. When repeated
attempts share the same likely failure mode, use a bounded alternative path or
fail clearly instead of creating a retry loop.

### Verification

- The exact long prompt completed through the synchronous provider probe.
- Unit and PostgreSQL integration tests verify partial-content reset and
  fallback replacement.
- Live Stop → Retry completed without duplicate user or assistant turns.

### Relevant areas

- `backend/src/main/java/synvo/agent/SynvoAgentCore.java`
- `backend/src/test/java/synvo/agent/SynvoAgentCoreTests.java`
- `backend/src/test/java/synvo/SynvoApplicationTests.java`
- `docs/specs/phase-2-agentic-conversation.md`

## 2026-08-18 — Do not declare a live-path fix before live-path verification

### Symptom

A standalone provider probe and automated tests passed, but the first deployed
H5 response still failed.

### Root cause

The standalone probe exercised the model gateway but bypassed the real
per-delta validation, PostgreSQL persistence, SSE publication, and H5 lifecycle.
The evidence supported the provider fallback but did not validate the complete
application path.

### Why it was missed

Successful component evidence was treated as proof of the complete vertical
slice. Live acceptance remained unverified when the fix was initially reported.

### Resolution

Use component probes to narrow a diagnosis, then run automated vertical-slice
coverage and the exact deployed user flow before declaring the incident
resolved.

### Preventive rule

Match verification scope to the claim. A component probe proves only that
component. For user-visible streaming behavior, completion requires the real
path from model output through persistence and SSE to the H5 interface.

### Verification

- Full backend suite: 163 tests passed.
- Frontend suite: 53 tests passed, with typecheck, lint, and production build.
- Docker backend, frontend, and PostgreSQL services were healthy.
- Victor confirmed both uninterrupted long generation and deliberate
  Stop → Retry in the deployed Lark H5 application.

### Relevant areas

- `AGENTS.md`
- `docs/specs/phase-2-agentic-conversation.md`
- Backend Agent Core, persistence, and SSE modules
- Frontend H5 conversation workspace

## 2026-08-21 — Bean-validation boolean constraints must be JavaBean getters

### Symptom

Invalid Phase 3 startup configuration accepted a relative workspace root and a
Lark-capable MCP server identity even though both fields had fail-closed
`@AssertTrue` rules.

### Root cause

The constrained methods were named `areWorkspacesValid()`,
`areMcpServersValid()`, and `areTimeoutsValid()`. Jakarta Bean Validation did
not discover them as boolean bean properties, so their constraints were never
evaluated during Spring Boot configuration binding.

### Why it was missed

Other constrained methods used the discoverable `is…` convention and failed
correctly. Focused implementation checks did not run every invalid startup
case together until the complete backend suite.

### Resolution

Rename constrained boolean methods to JavaBean getters:
`isWorkspacesValid()`, `isMcpServersValid()`, and `isTimeoutsValid()`.

### Preventive rule

Methods carrying property-level boolean constraints must use the JavaBean
`isPropertyName()` convention. Every security-sensitive configuration rule
also needs a negative startup test proving that an invalid value prevents the
application context from starting.

### Verification

- `ExternalIntegrationConfigurationTests`: 10 tests passed, including invalid
  workspace-root, pinned-runtime, and forbidden-Lark-MCP cases.
- Complete backend suite: 204 tests passed with PostgreSQL Testcontainers and
  V1-through-V5 migration coverage.

### Relevant areas

- `backend/src/main/java/synvo/configuration/CodexProperties.java`
- `backend/src/test/java/synvo/configuration/ExternalIntegrationConfigurationTests.java`

## 2026-08-21 — Persisted lifecycle state must outlive live runtime state

### Symptom

A completed Codex operation could no longer replay its safe activity after a
backend restart, a pending interaction remained persisted as `RUNNING`, and an
H5 activity stream could reconnect after receiving a terminal event.

### Root cause

Operation authorization and presentation depended partly on in-memory runtime
objects and active-only queries. The database therefore did not always express
the authoritative lifecycle, and the client treated transport closure rather
than the terminal domain event as the end of a stream.

### Why it was missed

Focused tests kept the originating facade and activity subscription alive.
They proved the normal live path but did not recreate the facade after a
terminal operation, inspect persisted interaction state, or verify that the
browser closed immediately on a terminal event.

### Resolution

Authorize owner-scoped activity replay from persisted operations in every
state, persist `WAITING_FOR_INTERACTION` while a decision is pending, resume
`RUNNING` only after the final pending interaction is resolved, and close the
H5 activity subscription as soon as a terminal event arrives.

### Preventive rule

Persist every externally visible lifecycle transition. Recovery and replay
must use owner-scoped persisted state rather than live runtime caches, and
clients must terminate subscriptions on domain terminal events instead of
waiting for a transport failure or component unmount.

### Verification

- Backend regression coverage recreates the facade before replaying completed
  activity, verifies durable waiting/resume transitions, and proves an expired
  decision never reaches the runner.
- Frontend regression coverage proves a terminal task event closes the stream,
  refreshes the persisted task projection, and releases active-operation
  controls without reconnecting.
- The complete backend suite passed with 215 tests; the frontend suite passed
  with 74 tests, plus typecheck, lint, and production build.

### Relevant areas

- `backend/src/main/java/synvo/workspaceagent/WorkspaceAgentFacade.java`
- `backend/src/main/java/synvo/persistence/JdbcWorkspaceAgentRepository.java`
- `backend/src/test/java/synvo/workspaceagent/WorkspaceAgentFacadeTests.java`
- `backend/src/test/java/synvo/persistence/WorkspaceAgentPersistenceTests.java`
- `frontend/src/codex/useCodexWorkspace.ts`
- `frontend/src/codex/CodexWorkspace.test.tsx`

## 2026-08-23 — Conversation submission must attach to its asynchronously created workspace operation

### Symptom

A live H5 response streamed model text while its Agent activity card remained
at zero milestones and looked stuck, even though the backend eventually stored
hundreds of normalized operation events.

### Root cause

The conversation endpoint returns before the Workspace Agent operation is
created. H5 attempted one task refresh after a fixed short delay and never
retried when that refresh arrived too early. While a conversation run was
active without an attached operation, the presentation intentionally hid the
previous operation's events, leaving the current card empty.

### Resolution

Separate the inexpensive authoritative task/operation synchronization from
auxiliary goal and inventory refresh. While the owning conversation run is
active and no active operation is attached, retry synchronization with a
bounded progressive delay. Stop immediately when the active operation appears
or the conversation run ends, then use the existing operation SSE stream.

### Preventive rule

Never model two asynchronously created application records as if a single
fixed delay establishes their relationship. Use an explicit identifier when
the contract provides one; otherwise perform bounded state synchronization and
test that the first observation can legitimately miss the dependent record.

### Verification

- The regression makes the first post-submission task refresh return no active
  operation and a later refresh return the owning operation.
- The test then requires live task-start and reasoning detail to appear in the
  conversation timeline with the normalized event count.

### Relevant areas

- `frontend/src/codex/CodexWorkspace.tsx`
- `frontend/src/codex/useCodexWorkspace.ts`
- `frontend/src/codex/CodexWorkspace.test.tsx`

## 2026-08-21 — Capability gates must validate the complete runtime inventory

### Symptom

The stable-only runner startup gate validated only the first page returned by
App Server model and feature discovery. A required model or a non-Stable
enabled feature on a later page could therefore be missed.

### Root cause

Capability discovery was treated as a small snapshot even though the protocol
defines cursor pagination. The policy also accepted feature records after
coercing or defaulting malformed maturity and enablement fields.

### Why it was missed

The fake App Server returned every model and feature in one page, so tests
proved the policy decision but not complete protocol inventory discovery.

### Resolution

Read model and feature inventories through a bounded cursor loop. Reject
malformed pages, repeated cursors, duplicate feature names, unknown enabled
maturity stages, missing required Stable features, and inventories that exceed
the supported page bound. Treat only the pinned runtime's exact `removed`
compatibility sentinels as inert records; pin the complete Removed inventory,
disable the records where supported, report them separately, and fail if an
unpinned Removed record appears.

### Preventive rule

A fail-closed capability decision must consume every bounded discovery page
and validate typed source records before applying policy. Generated schema
presence or a first-page sample is never evidence that the runtime capability
envelope is safe or complete.

### Verification

- Runner tests split both model and feature discovery across pages and require
  exact `gpt-5.6-sol` plus the complete Stable feature set.
- Capability tests reject enabled unknown/deprecated stages and a missing
  required Stable feature.
- An authenticated startup probe identified the exact pinned Removed-record
  set without exposing credentials, prompts, workspace paths, or tool payloads.
- The complete credential-free runner suite passed with 48 tests.

### Relevant areas

- `runner/synvo_runner/engine.py`
- `runner/synvo_runner/capabilities.py`
- `runner/tests/test_engine.py`
- `runner/tests/test_capabilities.py`
- `runner/protocol/CAPABILITIES.md`

## 2026-08-21 — Test the enabled branch of conditional production wiring

### Symptom

The credential-free disabled stack was healthy, but the first enabled Codex
stack could not start Spring Boot because `CodexRunnerClient` required a
Jackson 2 `ObjectMapper` bean that Boot 4 did not auto-configure.

### Root cause

The adapter imported `com.fasterxml.jackson.databind.ObjectMapper`. That type
was present only as a transitive dependency of the legacy Spring AI path,
while Spring Boot 4's web stack provides `tools.jackson.databind.ObjectMapper`.
The disabled conditional branch never instantiated the adapter, so ordinary
application-context tests did not expose the missing production bean.

### Why it was missed

Adapter unit tests constructed their own Jackson 2 mapper, and the existing
full application test ran with Codex disabled. Both passed without proving the
enabled conditional wiring used by Compose.

### Resolution

Use Boot 4's Jackson 3 mapper in the private runner adapter and add a full
enabled application-context test that asserts the real adapter and the
auto-configured mapper coexist.

### Preventive rule

Every security- or integration-sensitive `@ConditionalOnProperty` production
branch needs at least one context test with that branch enabled. Unit tests
that manually construct dependencies do not prove auto-configuration wiring.

### Verification

- Focused adapter tests passed with the Jackson 3 mapper.
- `CodexEnabledApplicationContextTests` started the real enabled application
  with PostgreSQL V1 through V5 and the production `CodexRunnerClient` bean.
- The rebuilt enabled Docker stack reached healthy runner, backend, frontend,
  and PostgreSQL states.

### Relevant areas

- `backend/src/main/java/synvo/integration/codex/CodexRunnerClient.java`
- `backend/src/main/java/synvo/configuration/WorkspaceAgentConfiguration.java`
- `backend/src/test/java/synvo/CodexEnabledApplicationContextTests.java`

## 2026-08-21 — Long-lived proxies must re-resolve replaceable service names

### Symptom

After the enabled backend was rebuilt and replaced, the frontend remained
healthy for static files but `/api/status` returned 502 until Nginx itself was
restarted.

### Root cause

Nginx resolved the Compose service name only when its worker started and kept
the old backend container address after Docker replaced that container.

### Why it was missed

The original smoke check started the complete stack together. It did not
replace only the backend while leaving the frontend proxy process alive.

### Resolution

Configure an Nginx upstream with Docker's internal DNS resolver, a bounded
validity period, shared upstream state, and the `resolve` flag.

### Preventive rule

Any long-lived proxy to a replaceable Compose service must dynamically
re-resolve the service name. Restart verification must recreate the upstream
without restarting the proxy and then prove the proxied endpoint recovers.

### Verification

- The rebuilt Nginx image accepted the resolver/upstream configuration.
- Backend-only replacement followed by a bounded DNS interval restored
  `/api/status` without restarting the frontend.

### Relevant areas

- `frontend/nginx.conf`
- `compose.yaml`

## 2026-08-21 — Stable feature disables need behavioral dependency tests

### Symptom

The enabled runner reported the required Stable shell features and completed
ordinary model turns, but tool-directed H5 requests produced only a textual
claim that the shell bridge was disabled. No command lifecycle or approval
interaction reached Synvo.

### Root cause

The launch policy classified `code_mode_host` as an out-of-scope product
surface and forced it off. In the pinned runtime it is also the private local
execution bridge used by Stable shell and file tools. Disabling it left
`shell_tool`, `unified_exec`, and `shell_snapshot` visible in inventory while
making them unusable at runtime.

### Why it was missed

Startup tests validated feature maturity and enablement records, while direct
App Server probes used default launch flags. Neither test executed a tool turn
through the exact production launch command after applying every explicit
feature override.

### Resolution

Require `code_mode_host` as internal runner infrastructure and stop disabling
it at launch. Keep remote Code Mode configuration and direct Code Mode APIs
unexposed, and continue disabling every unrelated out-of-scope Stable feature.

### Preventive rule

Feature maturity and inventory checks do not prove that enabled capabilities
remain usable after launch overrides. For each required workflow capability,
run one representative behavior through the exact production command and
verify its normalized lifecycle or approval boundary.

### Verification

- Isolated launch-group probes showed that only the out-of-scope Stable group
  suppressed command approvals; unsafe and retired-record overrides did not.
- `code_mode_host=false` alone reproduced zero command approvals, while every
  other out-of-scope Stable override together preserved the approval.
- The complete runner suite passed with 53 tests.
- A deployed disposable read-only turn emitted a bounded command approval,
  exposed only safe detail fields, accepted a decline, published command and
  interaction-resolution activity, reached a terminal state, and deleted its
  disposable task.

### Relevant areas

- `runner/synvo_runner/capabilities.py`
- `runner/tests/test_capabilities.py`
- `runner/protocol/CAPABILITIES.md`

## 2026-08-21 — Stream fragments, messages, and tool narration need separate state

### Symptom

H5 preserved line breaks within the final Codex result, but the final result's
first entry was joined directly to an earlier pre-tool narration message.

### Root cause

The workspace-agent facade correctly treated deltas as arbitrary text
fragments, but initially accumulated every App Server agent message into one
turn-wide buffer. Recognizing `MESSAGE_COMPLETED` fixed the missing separator,
but still made pre-tool narration part of the permanent answer. The facade
also kept one turn-wide “saw delta” flag, so an earlier streamed message could
suppress a later completion-only message.

### Why it was missed

Existing streaming tests covered fragmented text within one message and
completion-only text, but did not distinguish two completed messages with no
tool between them from pre-tool narration followed by a post-tool result.

### Resolution

Track delta state per App Server message. Preserve fragments inside that
message exactly and add a paragraph separator when another message follows
without tool work. When a completed message is followed by a stable tool-start
activity, treat it as transient narration: clear the turn through the existing
semantic content-reset lifecycle before streaming and persisting the post-tool
answer.

### Preventive rule

Transport fragments, protocol messages, tool phases, and application turns are
different lifecycle levels. Never infer their boundaries from text content.
Project presentation from explicit protocol events, and verify both the
replacement path around tool work and the preservation path for genuine
multi-message answers.

### Verification

- `WorkspaceAgentFacadeTests` proves arbitrary same-message fragments remain
  unchanged, pre-tool narration emits a reset before the final result, and two
  messages without intervening tool work remain separated and intact.
- `SynvoAgentCoreTests` proves the reset clears persisted/streamed assistant
  content before the final workspace-agent result.
- The complete backend test and package gates each passed with 219 tests.
- The enabled backend rebuilt and became healthy; the H5 proxy recovered after
  backend-only replacement.

### Relevant areas

- `backend/src/main/java/synvo/workspaceagent/WorkspaceAgentFacade.java`
- `backend/src/main/java/synvo/agent/SynvoAgentCore.java`
- `backend/src/test/java/synvo/workspaceagent/WorkspaceAgentFacadeTests.java`
- `backend/src/test/java/synvo/agent/SynvoAgentCoreTests.java`

## 2026-08-21 — Persisted engine references must be resumed before live reads

### Symptom

After the runner was restarted, H5 reported “Codex is unavailable” while its
status card still correctly reported that Codex was ready. Reloading reproduced
the error before a new task was created.

### Root cause

PostgreSQL correctly retained the Synvo task and opaque App Server thread
reference, but a restarted App Server reported that thread as not loaded. Turn
execution already resumed persisted threads before use; H5 task opening did
not. Its parallel inventory and goal reads therefore addressed an unloaded
thread and failed even though process health and authentication were valid.

### Why it was missed

Restart coverage verified health, authentication persistence, activity replay,
and turn reconstruction. Task-detail tests used a fake engine whose inventory
and goal reads succeeded without a preceding resume, so they did not model the
loaded-versus-persisted thread distinction.

### Resolution

Resume a persisted thread inside the workspace-agent facade before every
runner-backed inventory or goal read. Serialize that resume transition so H5's
parallel reads cannot issue competing resume requests for the same restarted
runtime.

### Preventive rule

An opaque persisted engine reference proves identity, not live readiness.
Every entry point that reads or acts through a restartable external runtime
must establish the runtime session behind the application-owned boundary
before issuing the operation. Parallel surface requests must not own or race
that transition.

### Verification

- The focused regression failed with `inventory, goal` before the fix and
  passed with `resume, inventory, resume, goal` after it.
- The complete backend test and package gates each passed with 220 tests.

### Relevant areas

- `backend/src/main/java/synvo/workspaceagent/WorkspaceAgentFacade.java`
- `backend/src/test/java/synvo/workspaceagent/WorkspaceAgentFacadeTests.java`

## 2026-08-21 — Boundary redaction must retain streaming state

### Symptom

A correct Codex result could include the runner's internal workspace prefix in
a source reference. Rendering the reference as a local link was also misleading
because H5 has no permissioned file-viewer route for that target.

### Root cause

The normalizer redacted each App Server text notification independently. App
Server may divide an arbitrary literal across two message deltas, so neither
fragment necessarily contains the complete protected value even though their
concatenation does.

### Why it was missed

Existing redaction tests used a protected value contained wholly in one event.
Markdown tests covered unsafe URL schemes but not a safe presentation contract
for a workspace-relative source with a verified line number.

### Resolution

Keep a minimal suffix that could begin the protected workspace literal at the
private runner boundary, combine it with the next delta, and flush it only at
the matching item or turn terminal event. Complete text records use the same
workspace-relative normalization. H5 validates the resulting relative
reference and presents it as a non-clickable label.

### Preventive rule

Any security or privacy transform applied to streaming text must be tested
across every possible chunk boundary relevant to the protected literal.
Per-fragment regular expressions are insufficient when the application later
concatenates those fragments. Perform the stateful transform before persistence
or publication, and keep presentation links inert until an authorized route
exists.

### Verification

- Runner regression coverage splits a fake workspace literal across adjacent
  message deltas and proves the combined public stream contains only a relative
  reference.
- Normalizer coverage proves complete message records remove the same literal.
- H5 coverage proves the relative source and verified line suffix are visible
  without creating a local hyperlink.
- All 55 runner tests and 85 frontend tests passed; frontend typecheck, lint,
  and production build also passed.

### Relevant areas

- `runner/synvo_runner/normalization.py`
- `runner/synvo_runner/engine.py`
- `runner/tests/test_normalization.py`
- `runner/tests/test_engine.py`
- `frontend/src/components/AssistantMarkdown.tsx`
- `frontend/src/components/AssistantMarkdown.test.tsx`

## 2026-08-22 — A reconnecting SDK transport needs a bounded replacement path

### Symptom

H5 and the Codex App Server remained healthy, but the native Lark status stayed
in `RECONNECTING` for hours after a network interruption. Reloading H5 could
not repair it.

### Root cause

The official Lark WebSocket repeatedly emitted `reconnecting` without later
emitting `reconnected` or `error`. Synvo represented those signals but had no
timeout for a reconnect that never terminated. The pinned SDK also disposes
the channel safety pipeline during disconnect, so reusing that channel object
is unsafe.

### Why it was missed

Lifecycle coverage tested successful reconnect and explicit error signals, but
not a transport that remained indefinitely between those outcomes.

### Resolution

Start one watchdog on the first reconnecting signal. If the channel remains in
that state for 30 seconds, the Lark adapter disconnects the abandoned vendor
channel, builds a replacement, restores the registered handlers, ignores late
events from the old channel, and connects the replacement. A replacement
failure becomes the existing observable `FAILED` state.

### Preventive rule

For long-lived vendor transports, `reconnecting` is a transitional state, not
a health guarantee. Bound it with deterministic application recovery. Keep
vendor object-reuse rules and stale-event filtering inside the integration
adapter, and keep timeout/state policy in the application lifecycle.

### Verification

- `LarkChannelLifecycleTests` covers successful stalled recovery, duplicate
  reconnect signals, cancellation after timely reconnect, failure, and
  shutdown.
- `OfficialLarkChannelClientRecoveryTests` proves recovery creates and connects
  a fresh vendor channel after disconnecting the abandoned one.
- All 23 Lark channel tests passed; the complete backend test gate passed with
  225 tests, and the backend package gate passed.
- The rebuilt backend became healthy and `/api/lark/connection` reported
  `connected` while the Codex runner and frontend remained running.

### Relevant areas

- `backend/src/main/java/synvo/lark/channel/LarkChannelClient.java`
- `backend/src/main/java/synvo/lark/channel/OfficialLarkChannelClient.java`
- `backend/src/main/java/synvo/lark/channel/LarkChannelLifecycle.java`
- `backend/src/test/java/synvo/lark/channel/LarkChannelLifecycleTests.java`
- `backend/src/test/java/synvo/lark/channel/OfficialLarkChannelClientRecoveryTests.java`

## 2026-08-22 — Durable task replay must not depend on replaceable runtime metadata

### Symptom

After a runner replacement, H5 could list a persisted task but opening it
reported that the task was unavailable. After the task was made accessible, a
fast completed follow-up turn showed only `Task started` in its activity
timeline even though the operation header correctly reported completion.

### Root cause

H5 loaded PostgreSQL-owned task detail together with runner-owned inventory and
goal state in one `Promise.all`. Failure of either auxiliary read discarded the
valid durable conversation. Separately, attaching to an operation already
known locally could skip an SSE replay after the operation became terminal, so
the browser retained an incomplete activity prefix.

### Why it was missed

Component fixtures made task detail, inventory, and goal either all succeed or
all fail. Activity tests covered complete replay streams but not a fast turn
whose terminal operation status arrived before the browser had received its
terminal activity event.

### Resolution

Load application-owned task detail first and refresh replaceable inventory and
goal state independently with settled outcomes. A missing auxiliary response
now yields empty auxiliary presentation without hiding task history. When the
same operation is already terminal, force an ordered replay while preserving
existing activity; until its terminal event arrives, project the persisted
operation status as the terminal milestone.

### Preventive rule

Durable application state and replaceable engine metadata must not share an
all-or-nothing surface read. Recovery should expose the durable record first,
then reconstruct or degrade auxiliary runtime features behind the owning
boundary. For streamed activity, terminal operation state is authoritative;
the client must be able to replay or safely project that terminal outcome even
when subscription timing loses the final event.

### Verification

- A component regression proves persisted history opens when inventory and
  goal reads reject.
- A timeline regression proves a terminal operation projects completion while
  replay catches up, and the workspace regression proves terminal attachment
  resubscribes.
- Sixteen focused frontend tests, typecheck, lint, and production build passed.
- After frontend replacement, the user reopened the pre-restart task, recovered
  its prior filename and numerical tolerance from bounded conversation context,
  and saw both `Task started` and `Completed` with 24 events summarized.

### Relevant areas

- `frontend/src/codex/useCodexWorkspace.ts`
- `frontend/src/codex/CodexActivityTimeline.tsx`
- `frontend/src/codex/CodexWorkspace.test.tsx`
- `frontend/src/codex/CodexActivityTimeline.test.tsx`

## 2026-08-23 — Replaceable current state cannot own terminal presentation

### Symptom

After a task satisfied its goal, H5 changed the goal panel to `Not set`,
removed the objective and progress, and continued showing the earlier save
confirmation. After terminal presentation was made durable, a second symptom
appeared: a provider-reformulated objective replaced the employee's saved
wording even though the employee had not edited it.

### Root cause

App Server removed the current goal after terminal completion. Synvo treated
an empty current-goal response as proof that no goal had ever existed, even
though completion is user-visible task history. The runner held no last
normalized goal record, PostgreSQL held no bounded goal projection, and the
panel cleared mutation feedback only when a different task was selected.
The facade also persisted the provider's whole goal record on every read. That
incorrectly gave provider lifecycle state ownership of the employee-authored
objective field.

### Why it was missed

Goal tests covered set, read, pause, resume, and clear while the provider still
returned a current record. They did not cover a successful terminal transition
where the replaceable provider resource disappeared before the next H5 read.

### Resolution

The runner retains the last bounded normalized goal record and translates
provider removal after an active goal into the existing `complete` state. The
owning task stores a bounded presentation snapshot containing only objective,
normalized status, aggregate usage/time, and update time. The facade prefers
current provider lifecycle state, refreshes its status and aggregate metrics
when available, and falls back to the snapshot for reload or
runner-replacement presentation. The objective is application-owned: only an
explicit employee goal mutation may change it, while provider reads may update
status, token usage, and active time. Explicit clear removes both states. H5
clears prior mutation feedback when a new operation begins.

### Preventive rule

Do not use a replaceable provider's current-resource response as the sole
source for user-visible terminal lifecycle. Persist the smallest safe
application-owned terminal projection behind the existing owning boundary,
assign field-level authority explicitly, and keep employee-authored intent
unchanged when merging provider progress. Keep execution authority with the
provider, and test current-record removal, provider wording changes, reload,
restart fallback, and explicit clear separately.

### Verification

- Runner tests cover terminal goal retention, provider removal, and explicit
  clear.
- Facade and PostgreSQL tests cover durable completed-goal fallback and V6
  migration from both empty and populated V4 schemas. Facade tests also prove
  provider wording cannot replace the saved employee objective during either
  goal mutation or subsequent lifecycle reads.
- H5 tests cover the completed objective, usage/time, restart action, and
  removal of stale save feedback when a turn begins.
- All 57 runner tests, 232 backend tests plus package, and 98 frontend tests
  plus typecheck, lint, and production build passed. The enabled stack applied
  V6 in place and all four containers became healthy.
- Authenticated H5 verification set and completed a fresh goal, then retained
  its completed status, objective, aggregate usage/time, and lifecycle actions
  after a full reload.
- The provider-rewording regressions passed all 18 focused facade tests and all
  232 backend tests plus package. The replacement backend and unchanged H5
  proxy both reported healthy; a final authenticated check must re-save the
  intended wording once because the earlier value was already overwritten.

### Relevant areas

- `runner/synvo_runner/engine.py`
- `backend/src/main/java/synvo/workspaceagent/WorkspaceAgentFacade.java`
- `backend/src/main/java/synvo/persistence/JdbcWorkspaceAgentRepository.java`
- `backend/src/main/resources/db/migration/V6__workspace_goal_snapshot.sql`
- `frontend/src/codex/CodexTaskPanel.tsx`

## 2026-08-23 — Superseded: apparent command-session approval did not survive production-topology verification

This lesson records the intermediate conclusion and why it was superseded. Its
command-session recommendation must not be used. The later lesson
“On-request approvals are boundary crossings, not routine confirmations” is
authoritative.

### Symptom

Document and numerical-data tasks required too many repeated approval clicks.
The pinned App Server advertised both an exact-command session decision and a
broader command-policy amendment, making either appear to be a possible way to
reduce the interruption rate.

### Root cause

Approval labels alone do not describe their effective scope. A pinned-runtime
probe showed that the exact-command session decision stayed within the same
loaded task session, while the command-policy amendment crossed tasks and
workspaces in the same App Server process. A separate read-only probe showed
that changing the approval policy to `never` could permit a workspace write.
Treating these choices as interchangeable would have widened authority rather
than merely improving the user experience.

### Resolution

The initial implementation kept Ask for Approval, rejected command-policy
amendments, and offered a purported exact-command session decision. Production
mount verification later proved that the probe had not exercised that path:
routine sandboxed work needed no interaction, and the saved command response
was the broader policy amendment. The session UI and policy were therefore
removed.

### Preventive rule

Do not translate a provider approval option from its label. Verify its runtime
scope using the production sandbox and mount topology, record the exact
decision returned, and keep the provider's advertised decision set as a
ceiling. A fake request or schema enum proves contract mapping only, not a safe
product grant.

### Verification

- The disposable probe is retained only as evidence of the mistaken topology
  and response mapping; it does not prove command-session approval.
- Production-topology verification and the authoritative later lesson record
  the corrected behavior and regression coverage.

### Relevant areas

- `runner/synvo_runner/interactions.py`
- `runner/synvo_runner/engine.py`
- `backend/src/main/java/synvo/workspaceagent/WorkspaceAgentPolicy.java`
- `frontend/src/codex/CodexInteractionDrawer.tsx`
- `frontend/src/codex/useCodexWorkspace.ts`
- `frontend/src/codex/CodexWorkspace.tsx`

## 2026-08-23 — Runner readiness must execute the inner sandbox

### Symptom

The enabled runner and H5 readiness banner both reported Codex as ready, but a
harmless `pwd` turn failed because the sandbox could not create its namespace.

### Root cause

The container's default Docker seccomp profile blocked the unprivileged user
namespace operation required by Bubblewrap. Startup proved the pinned CLI,
App Server protocol, model, capability inventory, authentication, and MCP
inventory, but never executed the local sandbox itself. The image also relied
on the CLI's bundled sandbox helper instead of pinning the distribution
Bubblewrap package as an explicit runtime prerequisite.

### Why it was missed

Credential-free tests replaced the Codex executable with a fixture, and prior
live checks exercised approval and model lifecycle without independently
asserting that the exact deployed container could create a Bubblewrap
namespace. A healthy App Server process was incorrectly treated as proof that
its permissioned execution environment was healthy.

### Resolution

Install the distribution Bubblewrap package in the runner image. In the
Codex-enabled Compose overlay, disable Docker's outer seccomp filter so the
non-root process can create the user namespace, while dropping every Linux
capability, enforcing `no-new-privileges`, retaining the existing bounded bind
mounts, and limiting the container to 256 processes. Before App Server is
exposed as ready, execute a silent `codex sandbox -- true` preflight; fail with
a generic capability error on nonzero exit, timeout, or launch failure without
including stderr.

### Preventive rule

Readiness for a permissioned execution runtime must behaviorally prove each
independent security layer it depends on. App Server readiness, capability
inventory, and authentication do not prove the inner sandbox. Container
changes that enable user namespaces must retain non-root execution, drop all
capabilities, prohibit privilege escalation, bound resources, expose no host
network or broad filesystem mount, and have a direct live sandbox regression.

### Verification

- Focused runtime/container contract tests passed, including fail-closed
  preflight behavior with no diagnostic leakage and exact hardening settings.
- All 61 runner tests passed.
- The rebuilt runner directly executed `codex sandbox -- pwd` successfully.
- A disposable authenticated App Server turn executed one sandboxed `pwd`,
  emitted command-started, command-completed, and terminal-completed events,
  and deleted its temporary task. The safe command required no approval under
  the pinned on-request policy.
- A fresh authenticated H5 retry completed the same single-command request,
  displayed only `.` and `workspace root` instead of the private absolute
  runner path, and presented a terminal completed activity timeline.
- Runtime inspection confirmed all capabilities dropped,
  `no-new-privileges:true`, the required seccomp exception, and a process limit
  of 256.

### Relevant areas

- `runner/Dockerfile`
- `runner/synvo_runner/runtime.py`
- `runner/tests/test_runtime.py`
- `compose.codex.yaml`

## 2026-08-23 — On-request approvals are boundary crossings, not routine confirmations

### Symptom

Finance Full Edit tasks ran `pwd`, CSV parsing, a temporary marker command, and
distinct directory-listing commands without presenting **Approve for this
session**. Earlier evidence had claimed that a repeated workspace-local command
would request one approval and then inherit an exact-command session decision.

### Root cause

Pinned Codex `workspaceWrite` with `approvalPolicy: on-request` intentionally
runs routine reads, edits, and commands inside the selected workspace without
approval. Approval is for work that may cross the sandbox, use the network, or
leave a trusted command set. The saved spike was mislabeled: it accepted a
proposed command-policy amendment for command requests and assigned
`acceptForSession` only to file approvals. It also used workspaces under
`/tmp`, unlike production mounts. The evidence therefore did not establish the
claimed native command-session behavior.

### Why it was missed

The broken Bubblewrap deployment previously forced otherwise routine commands
onto an approval/retry path, making approval appear to be a normal workspace
confirmation. Tests verified Synvo's narrowing and UI projection against fake
server requests but did not first prove that the production sandbox would emit
an eligible command request for an in-scope document/data workflow.

### Resolution

Treat successful sandboxed autonomy as the primary approval-fatigue reduction.
Do not manufacture elevated commands to exercise an approval control. Audit
the exact decision actually returned by every pinned-runtime probe, use the
production mount topology, and distinguish provider schema availability from
an in-scope product interaction. Victor approved the corrected behavior:
decline opaque command elevation privately, and retain one-time H5 decisions
only for independently classifiable file and allowlisted MCP interactions.

### Preventive rule

An approval acceptance test must begin with a real, policy-eligible interaction
from the production sandbox and mount topology. A fake server request proves
contract mapping only. A provider decision enum, a broader policy amendment,
or an approval caused by broken sandboxing cannot prove a safe product grant.
When stable protocol fields cannot reveal the requested elevation, deterministic
policy must fail closed rather than infer safety from the command string.

### Verification

- Count-only PostgreSQL inspection confirmed the latest Finance Full Edit
  operations completed with zero interaction records.
- A disposable production-runner probe executed first, exact-repeat, and
  distinct workspace-local commands; all completed with full command lifecycle
  and zero approval interactions.
- Authenticated H5 screenshots confirmed the same behavior for the Finance
  document/data workspace.
- Official OpenAI documentation confirms that `workspace-write` with
  `on-request` runs routine working-directory reads, edits, and commands
  automatically and asks when work needs to cross that boundary.
- Runner regressions prove every stable command-elevation request is declined
  without creating an application interaction, while file and allowlisted MCP
  interactions remain one-time only. Java policy independently removes all
  command and session decisions. H5 regressions prove no session control or
  acknowledgement is rendered.
- Complete verification passed all 59 runner tests; all 233 backend tests plus
  package; and all 100 frontend tests plus clean install, typecheck, lint, and
  production build. Both Compose contracts validated, the enabled stack
  rebuilt, all four containers became healthy, backend health returned `UP`,
  and the H5 proxy reported the backend `ready`.

### Relevant areas

- `tasks/phase-3-session-approval-probe.py`
- `runner/synvo_runner/engine.py`
- `runner/synvo_runner/interactions.py`
- `backend/src/main/java/synvo/workspaceagent/WorkspaceAgentPolicy.java`
- `docs/specs/phase-3-codex-in-lark.md`

## 2026-08-23 — Urgent approval loading must not wait for auxiliary task metadata

### Symptom

After a clean frontend install, the shared conversation handoff test sometimes
failed to open the H5 approval drawer before its timeout even though the
interaction itself was available.

### Root cause

Opening a task loaded replaceable skill inventory and goal metadata before it
loaded the requested interaction. A slow or unavailable auxiliary request could
therefore delay the security-critical approval UI.

### Resolution

Load the explicitly requested interaction immediately after the owning task,
then refresh inventory and goal state in the background. Task ownership remains
established before detail is fetched, while optional metadata can no longer
block the approval drawer.

### Preventive rule

An actionable interaction must depend only on the minimum authoritative state
needed to authorize and render it. Do not serialize urgent approval loading
behind replaceable inventory, usage, goal, or presentation metadata.

### Verification

- The H5 handoff regression now holds inventory unresolved and still requires
  the approval drawer to open.
- All 100 frontend tests pass after a clean install, followed by typecheck,
  lint, production build, replacement frontend health, and backend readiness.

### Relevant areas

- `frontend/src/codex/useCodexWorkspace.ts`
- `frontend/src/codex/CodexWorkspace.test.tsx`

## 2026-08-24 — Verify MCP integrations through the model-driven process boundary

### Symptom

The harmless MCP fixture worked through a direct App Server tool call, but its
model-driven H5 path first rejected the elicitation before it reached the user.
After the runner accepted the decision, the tool still failed because its
private marker directory was unavailable.

### Root cause

Pinned App Server `0.148.0` reduced the fixture's model-turn form to a bounded
approval-only schema with no fields, while the runner required at least one
form field. Separately, Codex launches registered MCP subprocesses with an
explicit environment; registering the server command without `--env` did not
forward the runner container's marker-directory variable.

### Resolution

Accept zero-field object schemas as one-time MCP confirmations while retaining
the existing typed-field validation for populated forms. Keep the fixture's
side effect approval-only, and register its dedicated non-secret directory with
an explicit MCP environment entry. Do not enable general environment
inheritance.

### Preventive rule

A direct MCP tool call proves the server protocol but not the complete product
boundary. Every approval-required MCP integration must also be exercised by a
model-driven turn through runner normalization, application policy, H5 human
resolution, and the MCP subprocess environment. Declare the minimum required
non-secret environment keys explicitly; never assume the runner process
environment is inherited.

### Verification

- A disposable full-runner probe reproduced the model-turn schema and, after
  the fix, exposed a pending `mcp_elicitation` instead of rejecting it.
- All 61 runner tests passed, including populated typed forms, approval-only
  forms, and the bounded fixture lifecycle.
- The rebuilt runner and the complete four-service stack were healthy.
- Authenticated H5 displayed the allowlisted MCP request, accepted the one-time
  decisions, completed one MCP tool call, and returned
  `SYNVO_MCP_WRITE_OK`.

### Relevant areas

- `runner/synvo_runner/interactions.py`
- `runner/fixtures/safe_mcp_server.py`
- `runner/tests/test_interactions.py`
- `runner/tests/test_safe_mcp_fixture.py`
- `README.md`

## 2026-08-24 — Java hygiene must be enforced by the build, not only the IDE

### Symptom

VS Code reported Java diagnostics for unused and redundant imports, an unused
local variable and private method, deprecated Jackson 3 APIs, an unchecked
generic test capture, and an unnecessary warning suppression even though the
Maven test and package lifecycles could still succeed.

### Root cause

The compiler was not configured to fail deprecation or unchecked warnings, and
`javac` does not provide the same unused-code analysis as the Java language
server. Jackson 2 compatibility methods remained callable but deprecated after
the Jackson 3 upgrade. Imports of types nested in implemented interfaces also
looked necessary at a glance, but were redundant because those member types are
inherited into the implementing class's scope.

### Why it escaped earlier verification

The relevant diagnostics were enforced only by the developer's IDE. A clean
Maven build therefore did not prove that the tracked Java sources were free of
the same warning classes. The first Docker rebuild also revealed that the
backend image copied `pom.xml` and `src/`, but not the new build-tool
configuration directory, so host and container builds no longer had identical
inputs.

### Resolution

- Removed the unused imports, local variable, private helper, and unnecessary
  suppression while preserving validation side effects.
- Replaced deprecated Jackson `isTextual()` and `textValue()` calls with the
  Jackson 3 `isString()` and `stringValue()` APIs.
- Replaced the raw generic argument capture in the controller test with an
  exact typed argument verification.
- Configured Java compilation to fail on deprecation and unchecked warnings.
- Added a narrow PMD gate for unnecessary imports, unused local variables, and
  unused private methods across production and test sources. The gate runs in
  the Maven `test` lifecycle and therefore also runs during `package`.
- Added the backend build-tool configuration directory to the Docker build
  stage before Maven packaging.

### Preventive rule

Treat the Maven build as the shared Java-diagnostic source of truth. Do not
silence deprecation or unchecked warnings broadly; update the API usage or
repair the generic boundary. Keep the unused-code PMD rules narrow so they
catch objective hygiene defects without turning style preferences into build
failures. Whenever Maven references a repository-local configuration file,
include that file in every build context—especially multi-stage Docker builds.

### Regression coverage and live verification

- Clean test compilation ran with `-Werror`, `-Xlint:deprecation`, and
  `-Xlint:unchecked` for production and test sources.
- PMD initially detected four redundant nested-type imports that ordinary
  compilation accepted, then passed after their removal.
- `./mvnw test` passed all 237 backend tests.
- `./mvnw package` passed all 237 backend tests and produced the application
  artifact.
- The backend Docker image rebuilt successfully with compiler and PMD gates
  active, and the complete four-service stack reached healthy state.
- Backend health returned `UP`; the frontend proxy returned backend status
  `ready`.
- `git diff --check` passed.

### Relevant areas

- `backend/pom.xml`
- `backend/config/pmd/unused-code.xml`
- `backend/Dockerfile`
- `backend/src/main/java/synvo/integration/codex/CodexRunnerClient.java`

## 2026-08-28 — Optional runtime metadata and mutable base tags must not gate readiness

### Symptom

H5 stayed on “Checking Codex” and reported Codex unavailable even though the
runner process, App Server initialization, model inventory, and ChatGPT login
were healthy. Rebuilding the runner then caused its sandbox startup check to
fail.

### Root cause

The runner treated `account/rateLimits/read` as mandatory account state. Pinned
App Server `0.148.0` returned protocol error `-32603` for that optional usage
request while `account/read` and `model/list` continued to succeed. The runner
converted the optional failure into `RUNNER_UNAVAILABLE`, so the backend hid
otherwise usable workspaces.

Separately, the mutable `python:3.13-slim` image tag moved from Debian Bookworm
to Trixie. Trixie's Bubblewrap `0.12` could not mount `/proc` under the runner's
restricted Compose capabilities, while Bookworm's Bubblewrap `0.8` remained
compatible.

### Resolution

- Preserve verified authentication when only rate-limit metadata is rejected;
  expose plan, usage, and reset time as unavailable instead of disabling Codex.
- Keep transport failures and the authoritative `account/read` request
  fail-closed.
- Replace a failed App Server client behind one single-flight recovery worker.
  Capability-check the replacement before publishing `ready`, terminalize any
  active operation exactly once, and never replay user work implicitly.
- Report `recovering`, `protocolIncompatible`, and `unavailable` separately so
  H5 can keep usable data visible and poll back to `ready` without a reload.
- Keep retry ownership inside the existing Lark lifecycle and continue bounded
  replacement attempts after a failed reconnect.
- Pin the runner's Python, Node, and Java bases by immutable digest, and pin the
  compatible Bubblewrap package version, so upstream tag movement cannot
  silently replace the sandbox implementation.

### Preventive rule

Classify runtime probes as authoritative or optional before composing a health
or readiness response. Optional usage and presentation metadata must degrade
independently. Test every optional failure branch.

Cached startup success is not live readiness. Probe a Stable App Server method
after a short freshness interval, serialize recovery, preserve exactly-one
terminal ownership, and do not replay an interrupted turn. Surface a safe,
precise recovery state rather than collapsing all failures into unavailable.

Container tags are not stable runtime inputs, even when they name a
distribution. Pin the image digest and sandbox package version, keep the
sandbox self-check as a startup gate, and rebuild the runner in routine
verification so upstream changes are discovered before a live environment is
replaced.

### Regression coverage and live verification

- Added a runner regression proving rejected rate-limit metadata returns a
  valid authenticated account with null usage fields.
- All 65 runner tests and 3 subtests passed, including stale readiness,
  single-flight replacement, no replay, exactly-one terminal, protocol
  incompatibility, and precise HTTP health-state coverage.
- All 240 backend tests and the full Maven package/PMD gate passed.
- All 107 frontend tests, type checking, linting, and the production build
  passed, including partial startup failure and no-reload recovery coverage.
- The digest-pinned runner image rebuilt with Python 3.13.15, Node 22.23.2,
  Codex 0.148.0, Bubblewrap 0.8.0, and Java 21.0.12.
- Compose configuration validated and all four services became healthy. The
  backend and H5 status routes returned ready, and the Lark WebSocket reported
  connected.
- The live sandbox self-check passed before and after recovery.
- Terminating the live App Server process group produced
  `ready -> recovering -> ready` without restarting the runner container.

### Relevant areas

- `runner/Dockerfile`
- `runner/synvo_runner/engine.py`
- `runner/synvo_runner/protocol.py`
- `runner/synvo_runner/runtime.py`
- `runner/tests/test_engine.py`
- `backend/src/main/java/synvo/lark/channel/LarkChannelLifecycle.java`
- `frontend/src/codex/useCodexWorkspace.ts`
