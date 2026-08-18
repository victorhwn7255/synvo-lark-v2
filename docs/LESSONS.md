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
