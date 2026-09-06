# Frontend Agent Interaction Clarity and Motion

Status: **In Progress**

Created: 2026-09-05

Approval: Victor approved this specification and full implementation on 2026-09-05.

Implementation started: 2026-09-05, after approval.

## Purpose and objectives

Make it immediately clear whether Synvo is starting, analyzing, working,
writing, waiting for a decision, or finished. Improve the six reviewed
patterns: loading, thinking, streaming text, approvals, tool chips, and
execution rows. Preserve the approved charcoal/blue theme, Avenir stack,
desktop shell, phone navigation, and conversation controls.

This phase improves presentation over the existing frontend revamp. It adds
no agent capability or workplace workflow. Natural-language conversation,
application-owned lifecycle, and deterministic permissions remain authoritative.

## Governing references and boundaries

- [AGENTS.md](../../AGENTS.md): SDD approval, scope, verification and Git rules.
- [Project overview](../project-overview.md) and
  [Phase 3 specification](phase-3-codex-in-lark.md): product and permission contracts.
- [Current frontend revamp](frontend-ui-ux-revamp.md): implemented baseline and
  its outstanding actual-mobile Lark acceptance gate.
- [DESIGN.md](../../frontend/design/DESIGN.md): tokens, typography, accessibility,
  motion, and component ownership.
- [Lessons](../LESSONS.md): arbitrary stream fragments, asynchronous operation
  attachment, content reset, replay, terminal authority, and live verification.
- [Principles](../PRINCIPLES.md): information hiding and the design checklist.
- [Beautiful UI](https://www.beautifului.dev/): visual inspiration for the six
  reviewed patterns; its sample data, question wizard, timings and code are
  not application requirements or permission contracts.

No backend, runner, database, REST/SSE or vendor-protocol changes. Use existing
React, TypeScript, Markdown rendering, CSS and tests; add no production package.
Preserve the current working-directory changes. Perform no Git mutations.

## Decisions and scope

### 1. One readable activity summary

Keep one activity block attached to the owning assistant turn. Replace its
generic running headline with a deterministic presentation derived from the
owning conversation phase, operation status, normalized events and interaction.
Phases may alternate; they are not a fixed checklist or completion percentage.

| Condition | Presentation |
|---|---|
| Authoritative terminal outcome | Completed, Stopped, or the existing specific failure label; overrides stale running/reconnecting state |
| Unresolved bounded interaction | Action required; existing drawer owns decisions |
| Stop requested, without terminal confirmation | Stopping…; do not claim stopped yet |
| Transport interrupted | Reconnecting…; preserve received content and known activity |
| Current response deltas arriving | Writing response…; remove the streaming cue when work resets, pauses or ends |
| Current normalized workspace/file/tool activity | Working in the workspace, Updating workspace files, or Using a connected tool, as supported by the category |
| Thinking/planning evidence | Analyzing your request…; show only supplied safe summaries |
| Accepted request awaiting operation/activity | Starting…; preserve the existing asynchronous attachment/retry behavior |
| Active operation without a more specific signal | Working…; never invent a tool, thinking trace, or percentage |

Use a 14px (`.875rem`) summary/control role, 13px (`.8125rem`) activity details,
and the existing compact metadata roles. Conversation prose uses 14px under
the user-approved [2026-09-06 sizing amendment](frontend-ui-ux-revamp.md#approved-sizing-amendment--2026-09-06).
This is a bounded amendment to DESIGN.md's activity typography. Keep current
shell widths and navigation geometry. Details may wrap and grow vertically.

Use one subtle animated indicator for the current activity state. Retain the
existing separate boot/conversation-loading states. Move normalized-event
counts and technical explanation into the existing Task details diagnostics.
Keep required privacy framing beside sensitive expanded detail; do not expose
private reasoning or move transient technical payloads into the answer.

Show a quiet whole-second **Elapsed** counter while a valid owning operation
start time is available. It is wall-clock time including waits, not compute
time, progress, cost, or a remaining-time estimate. Hide it before attachment
or for invalid/future timestamps. Stop ticking on terminal outcome; show a
final duration only when a valid terminal timestamp is available. Historical
duration uses authoritative terminal operation metadata, never page-load time.
Do not reuse a stale running timestamp merely because terminal SSE projected
a completed status onto the local operation record.
Timers clean up on selection, unmount and completion; do not announce each tick.

### 2. Compact tool chips and execution rows

Extend the existing activity projection; do not create another lifecycle owner.
Expose compact, keyboard-accessible chips for observed workspace commands,
MCP tools, and file operations. A chip shows a category icon, readable label,
observed count, and honest category state. Expand into bounded activity detail
on demand. Group chips within the existing activity block and wrap on phones.

The current activity contract has sequence/type/label/text and operation-level
terminal status, but no reliable per-tool invocation IDs or per-tool outcomes.
Therefore these are **category summaries**, not independent tool-call records.
Start/completion counts may describe observed activity; do not fabricate exact
pairing, tool duration, tool success, or file names. Completion events mean
activity finished, not necessarily that its result succeeded. An unmatched
start after failure/stop is unconfirmed/interrupted, never a green success.
Unknown event types remain available in safe diagnostics without invented chips.

Execution rows represent observed milestones within one task: analysis,
workspace activity, file work, decisions, and terminal outcome. Retain compact
aggregation for long/repetitive runs. Show labels and icons alongside color;
multiple unfinished categories must not be marked completed solely because
another category becomes current. Preserve the existing selected sidebar rows;
do not add large capsule tiles or simulated business-workflow steps there.

Order and deduplicate using the existing operation sequence/ownership rules.
Historical replay reconstructs the same result without duplicate chips or a
repeated animated entrance for every past event. Terminal task status remains
separate from the outcome of any individual command/tool/file operation.

### 3. Streaming presentation

Continue appending arbitrary received fragments immediately through
`useConversation`; preserve whitespace and the semantic `content_reset` path.
Add an unobtrusive visual cursor while the owning answer is actively streaming.
Use a short appearance transition for the live cue/newly introduced response
block and completion actions. Never fade or remount the entire growing answer
on every delta. Per-word reconstruction and artificial typewriter queues are
outside scope because they can delay or corrupt Markdown and mixed-language text.

The cursor is decorative, excluded from copied text and accessibility output,
and absent on saved/replayed completed responses, tool phases, pending decisions,
reconnection, stop, and failure. Stop must remain immediate and authoritative.
Preserve code blocks, tables, safe links, inert file references, text selection,
and the rule that automatic scrolling stops when the user reads earlier content.

Use concise status announcements for phase changes. Avoid duplicate live
announcements from both the activity block and response, and do not repeatedly
announce the full answer or elapsed timer. Preserve accessible final content.

### 4. Approval continuity

Retain the single authoritative interaction drawer, its contract-provided
**Approve once / Decline / Cancel task** choices, required input validation,
expiry behavior, focus containment/return, and submission lock. No Skip button,
generic questionnaire, command approval, session grant, or parallel inline
decision control is introduced.

After the existing decision API confirms an interaction with status `DECIDED`
and a returned decision, show a compact
receipt in its owning conversation: **Approved once**, **Declined**, or
**Cancellation requested**, based on the returned decision. A click or an
interaction disappearing is not proof of acknowledgement. Awaiting refresh is
not proof that execution resumed; subsequent activity/terminal state determines
that presentation. A cancellation receipt must not prematurely claim stopped.

Receipt identity is task + operation + interaction. Record only that identity,
the confirmed decision and minimal display metadata in session memory. Never
copy the interaction reason, command, paths, form values or MCP payloads into
the receipt. No browser storage or backend persistence is added. On reload,
show a generic decision-resolved milestone when only that event is available;
do not invent the prior choice. Clear task receipts on deletion/unmount, retain
only the latest 20 per task, and keep older events in the existing bounded history.

Delayed responses for an earlier interaction/selection must not dismiss a new
decision, move focus to the wrong task, or announce a receipt in another task.
Failed or ambiguous submission retains recovery and never shows a success
receipt. Existing idempotent retry and decision validation remain in force.

Preserve the drawer's existing 160ms backdrop/210ms entrance. Add a restrained
receipt appearance within the design system's motion ranges. Animation never
delays backend decision delivery, focus restoration or the next interaction.

### 5. Accurate prototype and motion rules

Correct the local prototype's lowercase activity names to the normalized
contract vocabulary. Terminal fixtures use the real terminal-event shape,
including stopped/failed outcomes. Do not loosen production parsing to accept
invalid fixtures. The corrected prototype must show received milestones rather
than an empty connecting state after known events.

Provide repeatable synthetic fixtures for startup, analysis, tool activity,
streaming, decision pending/confirmed/error/expired, reconnect, and terminal
outcomes. Exercise the actual production components with local APIs; no real
model, files, credentials, network operations or enterprise data in fixtures.
Rebuild the standalone HTML and update its README.

Motion uses existing CSS conventions: 140–160ms opacity/color transitions and
180–260ms movement; avoid large bounces, blur effects on readable text or
permanent decorative loops. Reduced motion removes animation while retaining
all status information. Do not add a motion library or theme switch.

## Deliverables and build order

1. Approve this specification, then mark it Approved / In Progress. Record
   the design-it-twice checklist in temporary task notes before interface edits.
2. Correct fixtures and design acceptance cases before production behavior
   changes. Capture the current six-state baseline and establish focused tests.
3. Implement the unified phase summary, elapsed display, grouped tool chips
   and execution rows inside the existing activity owner.
4. Add streaming cues and approval receipts using existing state hooks. Verify
   reset, terminal, interaction replacement and selection races as they change.
5. Review the prototype at desktop/phone widths and align DESIGN.md with the
   delivered typography, motion, chips, rows, receipts and accessibility rules.
6. Run complete affected verification and fill the Completion Audit. Close
   only with required evidence or an explicitly approved verification waiver.

## Acceptance criteria and test plan

| ID | Required behavior | Verification designed before implementation |
|---|---|---|
| I1 | Clear, truthful phase labels; terminal authority; asynchronous attachment preserved | Delayed attachment, alternating analysis/tool/text, missing/unknown events, stop, waiting, reconnect and stale-running replay tests |
| I2 | Elapsed time is optional, honest and isolated | Fake-clock cases for attachment, invalid/future start, task switch, terminal, reload and unmount; no per-second announcements |
| I3 | Chips/rows summarize real activity without invented outcomes | Duplicate/reordered sequences, overlapping category starts, completion without observed start, unmatched start at terminal, long runs and unknown event cases; no incorrect green success |
| I4 | Streaming cues preserve content and lifecycle | Whitespace-only/split fragments, CJK/emoji, partial table/code/link syntax, content reset, replay, selection/copy, tool/decision pause, cancellation and failure tests |
| I5 | Receipts follow confirmed, owning decisions only | Approve/decline/cancel, invalid/expired/required-field states, duplicate click, rejected or ambiguous request, replay and delayed-response/new-interaction/task-switch tests; failed submission shows no receipt |
| I6 | Prototype accurately exercises the presentation contract | Synthetic fixture/production projection regression for event names and terminal shape; browser run shows actual analysis/tool milestones and terminal state; no network connections |
| I7 | Theme, readability and accessibility hold | 320/390/760/761/980/981/1200/1440px, 200% text, Avenir/fallback, 4.5:1 text/3:1 essential control contrast, keyboard/disclosure/focus, reduced motion and forced colors |
| I8 | Existing lifecycle and controls remain intact | Full frontend suite, typecheck, lint and build; request ownership, steering drafts, one-time permissions, retry, tables and navigation regressions |
| I9 | Real H5 presents the changed flow correctly | Authenticated desktop synthetic start/stream/steer/stop/reload and the existing harmless allowlisted MCP decision fixture; actual mobile Lark keyboard/safe-area/decision/chip checks |

Run `npm ci`, `npm test`, `npm run typecheck`, `npm run lint`, and
`npm run build` from `frontend/`. Build/typecheck the standalone prototype using
its documented commands. Use focused behavior tests throughout; do not add
tests that merely repeat CSS values. Check production imports exclude fixtures.

Use the relevant Compose configuration/image/health/proxy checks from AGENTS.md.
Refresh only affected local services and preserve the existing backend, runner
and database. Do not shut down another running stack. Backend/runner test suites
become required only if a separately approved amendment changes those areas.
Live checks use a dedicated synthetic task and the existing safe MCP fixture;
they never broaden permission scope or inspect unrelated private tasks.

The preceding revamp's actual-mobile gate remains open. A final-build phone
check can supply evidence to both specifications when every affected behavior
is verified; it cannot be inferred from browser viewport simulation. Obtain
and record any explicit waiver before declaring a required check waived.

## Explicit non-goals

- New desktop layout, sidebar hierarchy, theme, fonts, UI framework or dependency.
- New APIs, richer tool telemetry, per-invocation progress/outcomes, inferred
  filenames, arbitrary file previews, downloads or business-resource access.
- A new planner, generic workflow engine, multi-agent orchestrator, user-input
  protocol, coding workflow or Keystone feature.
- Private chain-of-thought, fabricated reasoning, progress percentages or ETA.
- Permission changes, broader approvals, persistent grants, skipped decisions,
  sensitive receipt history, browser persistence or silent retry of side effects.
- Treating the reference website or a prototype animation as evidence of live
  production behavior or automatically closing the preceding release gate.

## Completion Audit

Implementation and automated verification completed on 2026-09-06. Status
remains **In Progress** until the required actual-mobile Lark check is supplied
or explicitly waived. No waiver has been given.

| Gate | Evidence | Result |
|---|---|---|
| Specification approval | Victor's “approve and proceed,” 2026-09-05 | Passed |
| I1–I6 behavior and prototype | Phase/terminal/category projection, timer cleanup, stream/copy/reset, bounded receipts, decision races, current pending-link recovery; corrected normalized fixture and `DECIDED` acknowledgement contract | Passed automated checks |
| I7 visual/accessibility | Chromium desktop and simulated phone widths, keyboard disclosures/modal containment, 200% text/Arial fallback, reduced motion, forced colors, computed contrast | Passed browser checks; actual phone pending |
| I8 complete automated verification | 169 tests in 18 files, typecheck, lint, production build; prototype typecheck/build | Passed |
| I9 authenticated desktop and stack | Start/result, delivered steering, active reload/replay and Stop verified; all four Compose services healthy; bounded MCP completed with two confirmed receipts and fixed marker result | Desktop passed; phone pending |
| I9 actual mobile Lark | No actual mobile device evidence or waiver supplied | Pending |
| DESIGN.md alignment and scope audit | Design system v1.2, prototype README, confirmed lessons; no backend/runner/API/production dependency changes | Passed |
| Explicit user-approved waivers | None | None |

### Automated and build evidence

Commands run successfully from `frontend/`:

- `npm ci --cache /tmp/synvo-npm-cache`: 217 packages installed; audit reported
  zero vulnerabilities.
- `npm test`: **169 tests / 18 files passed**. This local total includes five
  ignored prototype contract tests; application source contributes 164 tests
  in 17 files. Prototype fixtures are not imported by production source.
- `npm run typecheck`, `npm run lint`, `npm run build`: passed.
- `npx tsc --project design/prototype/tsconfig.json --noEmit` and
  `node design/prototype/build.mjs`: passed; standalone HTML rebuilt.
- `git diff --check`: passed; no Git mutations performed.

The new checks cover duplicate/reordered activity, overlapping and unmatched
categories, terminal priority over stale phases, authoritative elapsed metadata,
invalid/future timestamps, cleanup, arbitrary Markdown content and copy, cursor
pause/reset/replay, acknowledged choices, rejection/ambiguity, CSRF/selection/
replacement races, current decision recheck, receipt bounds/deletion/unmount,
form defaults and stale deep links. The live test exposed the older mock's
incorrect `RESOLVED` status; fixtures and production now use the producer's
`DECIDED` response. Unknown/expired acknowledgement statuses remain rejected.

### Browser evidence

Chromium 152.0.7977.76, local standalone prototype, synthetic data only:

- Final category-chip layout at 320, 390, 760, 761, 980, 981, 1200 and 1440px:
  no document horizontal overflow or clipped activity/chip containers.
- Starting, thinking, tools, streaming, reconnect, completion, stop and specific
  failure fixtures show their expected phase. The cursor appears only in the
  streaming fixture; pre-attachment elapsed time is absent.
- Approve once / Decline / Cancel task produce the corresponding confirmed
  receipt. Failed request retains the dialog with no receipt; retry recovers.
  Expired fixture disables decisions. Keyboard opens chips and contains modal
  focus. Existing automated tests cover return focus and replacement fields.
- At 390px with 200% root text and Arial fallback, activity wraps without page
  overflow. Reduced motion plus forced colors retains the cursor and status
  information with zero running animations.
- Computed visible text contrast: expanded tool view minimum 5.45:1, receipt
  view 6.06:1, approval drawer 4.70:1; no sampled failures below 4.5:1. Labels,
  category icons and focus indicators remain visible beside neutral borders.
- Representative fixture flows produced no external requests or page errors.

Local screenshots in `output/playwright/`: `agent-tools-desktop.png`,
`agent-receipt-phone.png`, `agent-tools-phone-200.png`,
`agent-streaming-forced-colors.png`. These are execution artifacts, not release
requirements or evidence of actual phone keyboard behavior.

### Live stack and remaining gate

Used `compose.yaml` plus `compose.codex.yaml`. Configuration validation and
frontend image build passed. Refreshed only frontend using
`up --detach --wait --no-deps frontend`; backend, runner and database remained
running. Health endpoint returned UP and frontend `/api/status` returned ready.
Backend/runner tests were not rerun because those implementations and contracts
were unchanged, as this specification explicitly permits.

Authenticated desktop Lark used only **UI revamp verification — synthetic data**.
A synthetic revenue task completed and displayed authoritative elapsed time.
A longer synthetic run accepted a composer steering update, reconstructed its
activity after reload, and reached Stopped with a final duration after Stop.
The bounded `synvo_safe_fixture.write_fixture_marker` flow completed with its
two existing one-time decisions and two Approved once receipts in the owning
conversation. The tool returned `SYNVO_MCP_WRITE_OK`. The exact fixed marker
scope was checked in the fixture source and Compose configuration; no permission
scope, backend policy, credentials or unrelated task data was changed.
A confirmed receipt now suppresses a stale action-required phase only for the
matching interaction; a new pending interaction keeps priority. Reload recovers
the current pending decision instead of reopening an already decided deep link.
The final frontend refresh restored the completed result without inventing
session-only receipt choices after reload.

Actual mobile Lark remains required: navigation, composer with software keyboard,
safe-area/viewport behavior, wrapping chips, approval focus/choices/receipt,
streaming and Stop. Browser width simulation cannot supply that evidence. This
same final-device check can satisfy the preceding revamp's pending A7 gate.
