# Phase 2.5 — Architecture Hardening

Status: **Complete**
Last updated: 2026-08-19

## Purpose

Phase 2.5 improves the internal structure of the completed Phase 2 system before
Enterprise Knowledge Research adds retrieval, evidence, citations, and
permissioned Lark operations.

This is a behavior-preserving hardening phase. It does not add product
capabilities or redesign the user experience. Its purpose is to make the
existing conversation system easier to understand, safer to extend, and less
likely to diverge between Lark Chat and H5.

The phase establishes this implementation shape:

```text
Lark Chat adapter ─┐
                   ├──> shared conversation application boundary
H5 REST/SSE adapter┘              │
                                  ▼
                           Synvo Agent Core
                                  │
                    model gateway + conversation state
```

The existing Phase 2 behavior, REST/SSE contracts, Lark response behavior,
PostgreSQL schema, security boundaries, and visible H5 experience remain the
baseline.

## Confirmed Inputs

| Area | Input from the completed system and architecture review |
|---|---|
| Backend | H5 currently enters through `ConversationRunCoordinator`, while Lark Chat invokes `SynvoAgentCore` directly and owns a separate timeout path |
| Frontend | `Workspace.tsx` currently owns conversation networking, streaming state, navigation, deletion, settings, artifacts, rendering, and many UI helpers |
| Styling | One global stylesheet contains connection, workspace, conversation, workflow, settings, dialog, responsive, and animation rules |
| API models | Conversation controllers currently expose records owned by a concrete persistence repository |
| Boundary enforcement | Package boundaries are conventions; no automated dependency rule protects them |
| Product behavior | Phase 2 is complete and its accepted behavior must remain unchanged |

## Decisions

1. Phase 2.5 is a small architecture-hardening phase between Phase 2 and the
   first complete MVP workflow.
2. Lark Chat and H5 must use one application-owned conversation execution
   boundary.
3. Surface adapters remain responsible for presentation translation: Lark
   Cards for Chat and REST/SSE payloads for H5.
4. The Agent Core remains Synvo-owned and provider-independent.
5. Conversation query and command contracts are application-owned, not
   persistence-owned.
6. Frontend extraction follows current product responsibilities rather than
   creating a generic component library.
7. Existing CSS is split only along real interface responsibilities.
8. One focused automated architecture test enforces the essential dependency
   direction after the new boundaries exist.
9. No production dependency is added for this phase. If a small architecture
   testing library is necessary, it must be test-scoped and its purpose must be
   documented before adoption.
10. The complete Phase 2 regression suite and controlled live Lark smoke tests
    are required before closure.

## Objectives

1. Ensure H5 and Lark Chat share conversation submission, timeout,
   cancellation, replay, lifecycle, and terminal-state policy.
2. Keep channel-specific rendering outside the Agent Core and shared
   application boundary.
3. Reduce the public surface of Agent Core orchestration internals.
4. Prevent API and Lark modules from depending directly on concrete
   conversation persistence repositories.
5. Extract H5 conversation behavior into a focused `useConversation` module.
6. Separate workspace navigation, conversation presentation, settings, and
   workflow presentation into cohesive frontend modules.
7. Split styles so an engineer can locate the CSS for one capability without
   reading the entire application stylesheet.
8. Add a small executable dependency rule that prevents the most important
   architectural regressions.
9. Preserve every completed Phase 1 and Phase 2 behavior and security
   guarantee.

## Scope

### 2.5.1 Baseline and characterization

Before structural changes:

- Run the focused backend and frontend tests covering conversation execution,
  Lark direct messages, REST/SSE, retry, stop, deletion, and workspace
  behavior.
- Record the current public HTTP payload shapes and SSE lifecycle event names
  as compatibility contracts.
- Treat the completed Phase 2 specification and tests as authoritative
  behavior.
- Do not change a failing baseline test merely to make a refactor pass; first
  determine whether the implementation or the accepted contract is wrong.

### 2.5.2 Shared conversation application boundary

Introduce one application-owned boundary used by both H5 and Lark Chat.

The boundary owns or consistently delegates:

- Request acceptance and idempotent replay.
- Conversation and run identity.
- Timeout scheduling.
- Explicit stop and cancellation.
- Agent Core execution.
- Lifecycle publication to a caller-provided observer.
- Terminal cleanup.
- Safe unexpected-failure handling.

H5 REST/SSE and Lark Chat remain adapters:

- The H5 adapter translates shared lifecycle events to persisted SSE replay
  and browser-facing response records.
- The Lark adapter translates the same lifecycle events to one evolving Lark
  Card response.
- Neither adapter invokes `SynvoAgentCore` directly after the extraction.
- Neither adapter owns a second conversation timeout policy.
- Adapter-specific delivery failure remains in the adapter because it is not
  an Agent Core failure.

The extraction must preserve:

- One evolving response per accepted turn.
- Unanchored ordinary direct messages and anchored explicit replies.
- Duplicate-delivery suppression.
- H5 stop, retry, reconnect, and SSE replay behavior.
- One terminal completed or failed state per accepted run.
- Existing safe error messages and secret-safe logging.

Public orchestration details such as prepared-run state should become internal
where practical. Exact class names and package placement may follow the
smallest coherent implementation; the required boundary behavior is
authoritative.

### 2.5.3 Application-owned conversation query models

Introduce a focused application query contract for:

- Listing recent conversations.
- Loading an owned conversation and its visible turns.
- Looking up an owned run for stop and SSE authorization.
- Deleting an owned inactive conversation.

The REST controller depends on this contract and returns application/API-owned
records. It must not import nested records from a concrete JDBC repository.

Requirements:

- Preserve the current JSON field names, values, status names, and timestamps.
- Preserve owner scoping and safe not-found behavior.
- Preserve active-run deletion protection and cascade deletion.
- Keep SQL and JDBC mapping inside the persistence adapter.
- Do not create a universal repository, generic CRUD service, or independent
  domain layer for simple transport records.

Other concrete repository dependencies may be moved behind small owner-defined
ports when required to enforce an essential dependency rule. Broad persistence
rewrites are outside this phase.

### 2.5.4 Frontend conversation module

Extract conversation state and transport orchestration from `Workspace.tsx`
into a focused `useConversation` module.

It owns:

- Recent conversation loading and selection.
- Conversation history loading.
- Optimistic user and pending-assistant turns.
- REST submission and CSRF acquisition.
- SSE subscription and lifecycle reduction.
- Stop, retry, reconnect, and terminal cleanup.
- Conversation deletion state and backend calls.
- Workflow presentation accumulation received from real events.

It exposes a small view-oriented contract to workspace components. Components
must not manipulate raw `EventSource` subscriptions or reproduce lifecycle
state transitions.

Extract cohesive presentation components for at least:

- Sidebar and recent-conversation navigation.
- Conversation stream and composer.
- Conversation turns and assistant actions.
- Delete confirmation dialog.
- Settings view.
- Artifact and workflow presentation area where separation improves ownership.

`Workspace.tsx` remains the composition shell for layout and top-level view
selection. Do not split each icon, button, formatting helper, or trivial element
into an independent module.

The refactor must preserve the approved interface exactly, including:

- Current sidebar expansion, active selection, deletion, and animation.
- Settings navigation and readiness indicator.
- Composer growth and bottom-pinned conversation behavior.
- Markdown rendering, avatars, Singapore completion time, and response actions.
- Waiting, streaming, stop, retry, reconnect, and failure presentation.
- Artifact panel behavior and responsive layouts.
- Keyboard, focus, reduced-motion, light, and dark behavior.

### 2.5.5 Cohesive stylesheet ownership

Split the existing stylesheet along the responsibilities that exist after the
component extraction. The expected conceptual groups are:

```text
foundation and shared tokens
connection and authorization
workspace shell and navigation
conversation and composer
workflow and artifact presentation
```

The exact filenames may follow the final component structure.

Requirements:

- Preserve current visual output and responsive breakpoints.
- Preserve CSS custom properties and shared accessibility rules.
- Avoid duplicating tokens, animations, or media-query behavior.
- Avoid CSS Modules, CSS-in-JS, a new UI framework, or a generic design-system
  package.
- Avoid one stylesheet per tiny component.

### 2.5.6 Dependency-boundary test

Add one focused backend architecture test after the new application boundary
is in place.

It must protect at least these rules:

1. `synvo.agent` does not depend on `synvo.api` or Lark surface adapters.
2. H5 and Lark Chat adapters use the shared conversation application boundary
   rather than invoking Agent Core orchestration directly.
3. API controllers do not import concrete conversation persistence
   repositories or persistence-owned response records.
4. Provider-specific Spring AI types remain inside the model adapter and
   configuration.
5. Official Lark SDK types remain inside Lark adapters.

Rules must describe stable architectural intent, not freeze individual class
names or every legal implementation detail. A failing rule must identify the
forbidden dependency clearly.

Frontend boundaries remain protected initially by TypeScript imports, focused
tests, lint, and the extracted folder structure. Do not add a second frontend
dependency-analysis tool unless the refactor reveals a demonstrated need.

## Target Conceptual Structure

This is a responsibility map, not a requirement to create every shown file.

```text
backend/src/main/java/synvo/
├── agent/                       # shared conversation application contract
│   ├── model/                   # model port and provider adapter boundary
│   └── internal orchestration   # routing, lifecycle and run coordination
├── api/                         # H5 REST/SSE adapter
├── lark/
│   ├── auth/                    # authorization and encrypted token lifecycle
│   └── channel/                 # Lark Chat adapter
├── persistence/                 # JDBC implementations
└── configuration/               # composition and runtime configuration

frontend/src/
├── api/                         # validated backend transport
├── lark/                        # H5 JSAPI adapter
├── workspace/                   # application shell, sidebar and settings
├── conversation/                # conversation state and presentation
├── workflows/                   # workflow-ready presentation primitives
└── styles/                      # cohesive style entrypoints
```

The project remains one Spring Boot application and one React application. This
phase does not introduce Maven modules, separate packages, independently
deployed services, or additional runtime processes.

## Implementation Sequence

1. Run and record the focused Phase 2 baseline tests.
2. Introduce characterization tests where the shared execution contract is not
   already explicit.
3. Extract the shared backend conversation application boundary.
4. Route H5 REST/SSE through the shared boundary.
5. Route Lark direct messages through the same boundary and remove duplicate
   timeout orchestration.
6. Introduce application-owned conversation query models without changing the
   HTTP contract.
7. Add the focused backend dependency-boundary test.
8. Extract `useConversation` while preserving existing Workspace behavior.
9. Extract cohesive workspace, conversation, settings, dialog, and artifact
   components.
10. Split CSS alongside the component ownership changes.
11. Run focused tests after each coherent extraction.
12. Run complete backend, frontend, PostgreSQL, Docker, and controlled live
    Lark regression verification.

Only one structural boundary should move at a time. Avoid a repository-wide
rename or simultaneous backend and frontend rewrite.

## Deliverables

- [x] Recorded Phase 2 behavior baseline before refactoring.
- [x] One shared conversation application boundary used by H5 and Lark Chat.
- [x] No duplicate channel-specific timeout policy.
- [x] Narrowed Agent Core orchestration surface.
- [x] Application-owned conversation query contract and response records.
- [x] H5 controllers independent of concrete conversation repositories.
- [x] `useConversation` with focused lifecycle and transport ownership.
- [x] Cohesive workspace, conversation, settings, dialog, and workflow
      presentation components.
- [x] Styles split by real responsibility without visual redesign.
- [x] One focused backend dependency-boundary test.
- [x] Updated focused tests aligned with the new module ownership.
- [x] Complete Phase 2 regression and live Lark evidence, with the explicitly
      approved induced-timeout waiver recorded below.

## Test Plan

### Backend characterization and unit tests

- [x] Both surface adapters submit through the shared conversation boundary.
- [x] Lark and H5 share request acceptance, replay, timeout, cancellation, and
      terminal cleanup semantics.
- [x] Surface adapters receive the same ordered lifecycle contract.
- [x] Channel delivery failure remains distinct from Agent Core and model
      failure.
- [x] Duplicate request IDs do not create duplicate runs.
- [x] Explicit stop remains idempotent.
- [x] Provider stream recovery still resets partial content and uses one
      bounded fallback.
- [x] Application-owned query models map to the existing public field values.
- [x] Owner isolation, active-run deletion protection, and safe not-found
      behavior remain unchanged.
- [x] Dependency-boundary violations fail with understandable test output.

### Frontend unit and component tests

- [x] `useConversation` owns and tests loading, optimistic turns, ordered
      deltas, content reset, completion, failure, stop, reconnect, and retry.
- [x] A failed retry restores or replaces the correct visible turn without
      duplicating the user prompt.
- [x] Stream subscriptions close on terminal events, conversation changes, and
      unmount.
- [x] Workspace composition preserves sidebar, Settings, artifacts, deletion,
      and connection notices.
- [x] Conversation components preserve composer growth, scroll following,
      avatars, Markdown, response actions, and Singapore time.
- [x] Keyboard focus, dialog containment, reduced motion, and accessible labels
      remain covered.
- [x] Responsive and theme behavior remains unchanged after CSS extraction.

### PostgreSQL and API integration tests

- [x] Existing Flyway migrations apply cleanly without a Phase 2.5 schema
      migration unless a separately reviewed need is discovered.
- [x] REST conversation list, detail, submit, stop, delete, and error payloads
      remain wire-compatible.
- [x] SSE event names, ordering, replay cursor, and terminal behavior remain
      compatible.
- [x] Persisted conversations, turns, runs, events, and Lark chat bindings
      remain unchanged across backend restart.
- [x] Authorization, encrypted tokens, ownership, deduplication, and retention
      regression tests pass.

### Complete automated verification

- [x] `cd backend && ./mvnw test`
- [x] `cd backend && ./mvnw package`
- [x] `cd frontend && npm ci`
- [x] `cd frontend && npm test`
- [x] `cd frontend && npm run typecheck`
- [x] `cd frontend && npm run lint`
- [x] `cd frontend && npm run build`
- [x] `docker compose config --quiet`
- [x] Docker images build and the complete stack reaches healthy status.
- [x] Backend and proxied frontend health endpoints return ready/UP.
- [x] `git diff --check` passes.

### Controlled live Lark smoke test

- [x] One ordinary Lark direct message produces one unanchored evolving
      response.
- [x] One explicit Lark Reply preserves its anchor.
- [x] One H5 prompt shows waiting, streaming, and completed states.
- [x] H5 stop and retry complete without duplicate visible turns.
- [x] Lark and H5 use the same safe timeout and terminal-failure behavior
      (automated coverage passed; induced live timeout waived by Victor).
- [x] No token, credential, unredacted sensitive prompt, message body, or model
      response appears in logs.

## Acceptance Criteria

Phase 2.5 is complete when:

1. H5 and Lark Chat use one application-owned conversation execution boundary.
2. No surface adapter directly invokes Agent Core orchestration or owns a
   separate conversation timeout policy.
3. Existing Lark Card, H5 REST/SSE, retry, stop, replay, and terminal behavior
   remains compatible with the completed Phase 2 specification.
4. API controllers do not expose concrete conversation persistence records.
5. `Workspace.tsx` is a composition shell rather than the owner of raw
   conversation transport and lifecycle transitions.
6. Conversation state and streaming behavior are isolated behind a tested
   `useConversation` contract.
7. Workspace, conversation, settings, and workflow presentation code and
   styles are discoverable through cohesive modules without excessive
   fragmentation.
8. One automated architecture test prevents the essential backend boundary
   violations defined in this specification.
9. No new user-facing feature, visual redesign, database, service, deployment
   unit, or generic framework has been introduced.
10. Complete backend, PostgreSQL, frontend, Docker, and controlled live Lark
    regression verification passes, or an explicit user-approved waiver is
    recorded in the Completion Audit.

## Explicit Non-Goals

Phase 2.5 does not include:

- Enterprise Knowledge Research implementation.
- Configured-Drive search, retrieval, synthesis, evidence, or citations.
- Meeting-to-Execution implementation.
- A real Permissioned Lark Action or tool implementation.
- Lark Tasks, Calendar, Base, Docs, Drive, approval, or other writes.
- New conversation, branching, search, project, or memory product features.
- Changes to the approved H5 layout or visual design.
- Changes to public REST, SSE, or Lark response behavior.
- A PostgreSQL schema redesign.
- A generic repository, service layer, agent platform, workflow engine, tool
  framework, or component library.
- Microservices, Maven multi-module conversion, Java Platform Module System,
  Spring Modulith, a message broker, Redis, or another database.
- A frontend state-management library, routing framework, CSS-in-JS system, or
  replacement UI framework.
- Repository-wide package renaming or unrelated cleanup.

## Risks and Controls

| Risk | Control |
|---|---|
| Refactor changes accepted behavior | Characterization tests first; preserve public payloads and lifecycle contracts |
| H5 and Lark lifecycle divergence | One shared execution boundary and one contract test matrix |
| Frontend over-fragmentation | Extract only current cohesive responsibilities; keep trivial helpers grouped |
| Architecture test freezes implementation details | Enforce only stable dependency direction and SDK boundaries |
| Large simultaneous rewrite obscures regressions | Move one boundary at a time and run focused tests after each step |
| CSS extraction causes visual regressions | Move rules without redesign; verify responsive, theme, focus, and reduced-motion states |
| New abstractions become speculative | Add only contracts required by the two current surface adapters and existing persistence behavior |

## Completion Audit

Status: **Complete**

### Implementation evidence

- H5 REST/SSE and Lark Chat now enter through
  `ConversationRunCoordinator`; neither adapter invokes `SynvoAgentCore`
  directly or owns a separate response-timeout scheduler.
- `ConversationQueries` owns the conversation query contract and public
  response records. The JDBC adapter implements it, and API controllers no
  longer import persistence-owned DTOs.
- Agent Core prepared-run and streaming orchestration details were narrowed to
  package ownership where existing integration usage permitted.
- `useConversation` owns browser transport and lifecycle state. Workspace,
  sidebar, conversation, settings, delete dialog, artifact presentation, and
  grouped visual helpers are cohesive modules.
- The original stylesheet was split into foundation, connection, workspace,
  conversation, workflow, and workspace-control responsibilities in the same
  cascade order. The production CSS remained 47.38 kB (9.42 kB gzip), matching
  the recorded baseline.
- `ArchitectureBoundaryTests` enforces agent dependency direction, shared
  surface execution, controller independence from persistence records, Spring
  AI containment, and Lark SDK containment. Four focused architecture rules
  pass with actionable failure messages and no new dependency.

### Verification evidence

- Baseline before refactoring: 166 backend tests and 61 frontend tests passed;
  frontend typecheck, lint, and production build also passed.
- Final backend: 172 tests passed with zero failures or errors. The complete
  Maven package build passed.
- Final frontend: 8 test files and 61 tests passed; `npm ci`, typecheck, lint,
  and production build passed.
- PostgreSQL/Testcontainers integration applied all four existing Flyway
  migrations cleanly. Phase 2.5 adds no migration.
- The production Docker images rebuilt successfully. PostgreSQL, backend, and
  frontend all reached healthy status; direct backend health returned `UP`,
  the frontend returned HTTP 200, and its real `/api/status` backend proxy
  returned `ready`.
- Backend-restart persistence passed. Non-sensitive counts for conversations,
  turns, runs, run events, and Flyway history were identical before and after
  restarting the backend container.
- Source logging records only safe state, counts, and exception types. A
  redacted scan of 121 backend/frontend runtime log lines found zero credential
  patterns and zero prompt, message-body, or response-body patterns.
- `docker compose config --quiet` and `git diff --check` passed.

### Acceptance-criterion audit

| Criterion | Result | Evidence |
|---|---|---|
| 1. One H5/Lark execution boundary | Pass | Both adapters invoke `ConversationRunCoordinator` |
| 2. No direct core or duplicate timeout in adapters | Pass | Architecture rule plus coordinator and Lark handler tests |
| 3. Preserve Lark, REST/SSE and lifecycle behavior | Pass | Complete regression suites and controlled Lark/H5 smoke checks pass |
| 4. Controllers do not expose persistence records | Pass | `ConversationQueries` owns the public query models |
| 5. Workspace is a composition shell | Pass | Conversation transport and lifecycle moved to `useConversation`; cohesive workspace views extracted |
| 6. Tested conversation contract | Pass | Existing component characterization exercises the extracted hook across lifecycle, retry, stop, deletion and reconnect behavior |
| 7. Cohesive modules without excessive fragmentation | Pass | Workspace, conversation, workflow and six responsibility-based stylesheet modules are directly discoverable |
| 8. Executable architecture protection | Pass | Four `ArchitectureBoundaryTests` rules pass |
| 9. No scope expansion | Pass | No feature, schema, service, framework, deployment-unit or visual-design change introduced |
| 10. Complete verification | Pass with explicit waiver | All automated, PostgreSQL, Docker, persistence, log-safety and practical live smoke gates pass; Victor waived only the induced live-timeout drill |

### Remaining controlled evidence

Victor confirmed on 2026-08-19 that the ordinary Lark DM, explicit Lark Reply,
H5 waiting/streaming/completion, and H5 stop/retry checks all passed. A
post-interaction redacted scan of 244 backend/frontend runtime log lines found
zero credential patterns and zero prompt, message-body, or response-body
patterns.

Victor explicitly approved a waiver for the induced live timeout drill on
2026-08-19. The timeout, cancellation, safe terminal failure, shared lifecycle,
and cleanup paths remain covered by the passing coordinator, adapter, model
recovery, integration, and architecture tests. No other verification was
waived.
