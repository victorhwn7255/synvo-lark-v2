# Frontend UI Revamp and UX Improvements

Status: **In Progress**

Created: 2026-09-05

Approval: Victor approved the full specification and implementation on 2026-09-05.

Implementation started: 2026-09-05, after approval.

## Purpose and objectives

Apply the approved charcoal/blue visual language to the supported Synvo H5
application, then make starting, steering, reading, and managing document/data
tasks easier. Preserve the application-owned lifecycle and permission model.

This is a frontend improvement phase over the completed Phase 3 foundation.
It does not reopen Phase 3 closure or implement a Keystone workflow phase.

## Governing references

- [AGENTS.md](../../AGENTS.md): development process and authorization rules.
- [Project overview](../project-overview.md) and
  [Phase 3 specification](phase-3-codex-in-lark.md): product scope and contracts.
- [Design system](../../frontend/design/DESIGN.md): visual tokens, components,
  accessibility requirements, and migration checks.
- [Approved theme prototype](../../frontend/design/prototype/index.html) and
  [prototype notes](../../frontend/design/prototype/README.md): local visual
  reference and prior verification, using sample data.
- [Development lessons](../LESSONS.md): request ownership, replay, optional
  metadata degradation, and matching verification to the claimed result.

The design system governs appearance; this specification governs the selected
behavior changes and their acceptance. Temporary execution notes belong in
`tasks/` and must not become a competing requirements document.

## Decisions and scope

**Already approved:** charcoal/grey surfaces, small blue accents, Avenir Next
with the documented fallbacks, and the existing desktop shell as the visual
baseline. The current prototype is not a live backend or permission contract.

**Approved through this specification:** the three checkpoints
below, including their explicit layout and behavior changes. Checkpoint 1
preserves the current geometry. Checkpoints 2–3 are bounded amendments to the
design system's initial layout-preservation rule; update its affected sections
in the same implementation change. Do not silently apply those amendments
while the specification is Draft.

| Checkpoint | Deliverable | Boundary |
|---|---|---|
| 1. Theme and accessibility | Production charcoal palette, Avenir stack, neutral selected rows, blue primary actions, accessible text/control/focus contrast | Preserve current structure, spacing, font sizes, and responsive rules; check all supported app surfaces |
| 2. Everyday task controls | Steering in the conversation composer; clearer Task details; accurate Settings | Keep desktop sidebar/main/panel arrangement and navigation order; change the placement and hierarchy of existing controls |
| 3. Starting, mobile, and results | Instruction-first task setup, phone navigation/details, readable typography and tables, clearer file references | Change only the identified task-setup and mobile compositions; no new artifact-access capability |

### Checkpoint 2 behavior

- Idle composer sends a normal message. During an eligible active operation,
  it sends **Update instructions** through the existing steering command;
  **Stop** remains nearby. A pending decision continues to use the existing
  decision surface and its contract-provided **Cancel task** action; the
  composer does not become an alternative approval or submission path behind
  that modal.
- Keep one steering input and the existing owner-scoped acknowledgement/history.
  If the operation finishes or selection changes before submission completes,
  retain unsent input for its owning task and show the outcome accurately.
  Never silently turn a rejected steering request into a new message.
- Task details leads with status, workspace/access, a compact goal summary,
  and relevant actions. Group management controls under a **Task actions**
  disclosure; keep goal editing accessible. Preserve all existing capabilities
  and confirmation flows rather than deleting controls to simplify the view.
- Settings presents authenticated H5 connection state, Codex readiness,
  available account/usage data, and configured workspace boundaries. Move
  global account information here and collapse technical diagnostics. Missing
  optional metadata reads as unavailable, never zero or disconnected by inference.
  Remove misleading active-knowledge-source copy; retain the Quotation placeholder.

### Checkpoint 3 behavior

- Task setup leads with **What would you like to work on?**, a request field,
  visible configured workspace/access controls, optional title, and one
  **Start task** action. A nonblank request and eligible workspace are required;
  read-only remains the default. Create the task before submitting its first
  message through the existing APIs. Do not submit before creation is confirmed.
- If creation fails, keep the draft. If creation succeeds but the first message
  fails, keep the created task and recover the message there. Disable duplicate
  clicks. After an ambiguous creation response, refresh the task list and offer
  recovery; never automatically repeat creation or promise cross-request atomicity.
  Message retry continues to use the existing request/failed-turn identities.
- At widths up to 760px, replace the persistent collapsed rail with a header
  navigation toggle and overlay drawer. Close navigation after selection.
  Task details becomes a full-screen sheet with a visible Close action.
  Preserve desktop behavior above that breakpoint and keep the composer
  reachable with the phone keyboard open. Preserve task/interaction deep links.
- Use `.875rem` conversation text and standard form-control text
  (14px at the default root size), retaining Avenir, heading hierarchy,
  and compact metadata roles. Allow wrapping and control growth; do not enlarge
  all metadata or change root font size. Review these values in the prototype
  before implementation; a material sizing change is a specification amendment.
- Rename the displayed **Full Edit** label to **Edit workspace files** while
  retaining the `WORKSPACE_WRITE` API value. Supporting copy explains that
  permitted edits/commands stay inside the selected workspace and that external
  access remains blocked. Permission capabilities and approval labels do not change.
- Render Markdown tables with semantic headers/cells and local horizontal
  scrolling when needed. Preserve raw-HTML/image restrictions, safe URL handling,
  and streaming behavior. Improve workspace-relative reference legibility but
  keep references inert. No Preview or Download buttons are added in this phase.

## Implementation approach and build order

1. After approval, mark the specification **Approved**, then **In Progress**
   when work starts. Capture baseline screens and existing regression results.
2. Design the acceptance cases below before changing behavior. Reuse existing
   coverage for appearance-only work; add focused behavior tests for new flows.
3. Implement checkpoint 1 using the design system's migration sequence and
   verify its visual baseline before changing layout or behavior.
4. Prototype checkpoint 2's affected controls in `frontend/design/prototype`,
   implement them using the existing application hooks/APIs, and run focused
   acceptance checks before continuing.
5. Prototype checkpoint 3's task setup, typography, mobile sheets, and tables;
   implement the specified behavior and verify recovery and responsive cases.
6. Run complete affected verification, align `DESIGN.md`, and populate the
   Completion Audit before marking the specification **Complete**.

An approved specification authorizes its defined checkpoints without another
approval at every routine step. Material scope, contract, layout, or design
deviations require an explicit amendment and user approval before that work.

Keep React, TypeScript, Vite, Tailwind, and the current CSS/component approach.
Keep HTTP/SSE contracts and Java policy unchanged. Reuse task creation,
submission, steering, cancellation, and status APIs; do not duplicate their
lifecycle ownership. Apply the [design checklist](../PRINCIPLES.md) before any
new interface/module boundary and record the comparison in task notes.
If table support needs a focused parser dependency, document its purpose,
security behavior, and architectural impact before adding it. No new UI framework.

## Acceptance criteria and test plan

Each criterion needs evidence in the Completion Audit. Design automated cases
before implementation; pair them with visual/live checks where appropriate.

| ID | Acceptance criterion | Verification |
|---|---|---|
| A1 | Supported surfaces use the approved tokens/font stack under OS light and dark preferences; checkpoint 1 preserves baseline geometry | Computed styles, before/after screenshots, design-system surface matrix |
| A2 | Text contrast meets the design-system threshold; essential boundaries and focus are visible; zoom, reflow, keyboard, reduced motion, and font fallback remain usable | Contrast measurements and manual accessibility checks, including 200% enlargement and 320px reflow |
| A3 | Start task creates once per confirmed user submission and sends to that task; read-only default, workspace binding, and optional title work | Component/hook tests for success, double click, creation failure, ambiguous outcome, and first-message failure/retry; live H5 start |
| A4 | Steering targets the owning eligible operation; Stop remains available; terminal/selection races preserve input and do not send a new message implicitly | Delayed-response, reversed-selection, pending-decision, duplicate-submit and terminal-race tests; live update/stop |
| A5 | Simplified Task details retains goals, management, diagnostics, review and current activity; moved controls remain keyboard reachable | Component tests and desktop panel/phone sheet checks |
| A6 | Settings uses real current-product data; unavailable optional usage never implies zero; legacy knowledge copy no longer implies enabled retrieval | Ready/recovering/unauthenticated and missing-metadata cases; Quotation remains disabled |
| A7 | Phone navigation consumes no permanent sidebar gutter when closed; navigation/details close predictably and restore focus; deep links still select the owning task | 320/390/760px checks plus above-breakpoint desktop checks; actual Lark keyboard, safe-area, and sheet navigation |
| A8 | Table content renders safely with readable headings and local overflow; partial streams, code, links and inert workspace references retain their guarantees | Markdown renderer/security regressions, partial/malformed table fixtures, wide/long-result screenshots |
| A9 | Revised text sizes and access label remain readable without clipping or changing execution policy | Avenir/fallback and long/mixed-language strings; verify `WORKSPACE_WRITE` mapping and unchanged one-time decisions |
| A10 | Existing replay, retry, cancellation, request ownership, permissions, and exactly-one terminal handling remain intact | Existing frontend regressions plus authenticated H5 resume/reconnect/stop and a safe bounded interaction |

Run frontend checks after implementation:

```sh
cd frontend
npm ci
npm test
npm run typecheck
npm run lint
npm run build
```

Check 320, 390, 760, 980, 1200, and 1440px widths and both sides of changed
breakpoints. Use synthetic/redacted fixtures. Run the relevant Compose health
and vertical-slice checks from `AGENTS.md` and the affected authenticated
desktop/mobile Lark H5 flows before release. Do not disrupt another running
stack to perform a smoke check. Backend/runner tests become required if an
approved amendment changes those areas; they are not changed by this plan.

Tests of frontend development are engineering verification, not a new
software-development capability in the Synvo product. Document any unavailable
environment and obtain an explicit waiver before treating a required live
check as waived. Prototype screenshots alone do not prove live behavior.

## Explicit non-goals

- Artifact preview/download endpoints, arbitrary filesystem browsing, or
  clickable host/workspace paths without an authorized access contract.
- Keystone workflow implementation, business-resource reads/writes, knowledge
  ingestion, or promotion of deferred native Lark Chat.
- Backend/database/runner contract changes, broader grants, command elevation,
  network enablement, or changes to one-time decision policy.
- A new desktop shell, workflow-specific themes, a theme switch, a component
  framework, generic workflow engine, or unrelated refactoring.
- Persisting request drafts, task details, steering, or sensitive content in
  browser storage; copying prototype fixtures into production.

## Approved sizing amendment — 2026-09-06

The user explicitly requested smaller chat text, slightly larger sidebar
labels, and an Active/Archived background aligned with the other sidebar
rows. This authorizes the following bounded appearance amendment:

- User/assistant prose, result tables, desktop composer and initial request:
  `.875rem` (14px), with `1.7` conversation line height. Conversation headings
  use `1.25rem`, `1.125rem`, and `1rem` for h1, h2, and h3–h4 respectively.
  At widths up to 760px, retain `1rem` editable composer/request text to avoid
  introducing smaller mobile input text; conversation prose remains 14px.
- Sidebar task/workflow/Settings labels and New task: `.8125rem` (13px).
  Section headings: `.6875rem` (11px). Filter and empty-state labels:
  `.75rem` (12px). Coming-soon metadata: `.625rem` (10px).
- Remove the filter's extra inline margin. Its full outer width and `.7rem`
  radius align with task rows; its desktop minimum height matches `2.35rem`.
  Keep two equal segments and existing phone targets of at least 44px.
- Preserve the approved palette, Avenir stack, root size, layout, and behavior.
  Update DESIGN.md and rebuild the prototype from the production components.

Acceptance checks, defined before implementation: measure computed font sizes
and filter/row left and right edges; inspect desktop and phone screenshots;
exercise Active/Archived and sidebar collapse/drawer controls; check 320px
reflow, fallback fonts and 200% text enlargement; run the frontend suite,
typecheck, lint, production/prototype builds, and frontend-only stack health.
Record results below. The existing actual-mobile release gate remains open.

## Approved focus appearance correction — 2026-09-06

The user's double-border screenshots authorize a focused appearance correction.
Editable controls and buttons must show one continuous focus edge rather than
a grey border, gap, and second blue outline. Keep the solid 3px focus indicator
and overlap the existing 1px boundary with a `-1px` outline offset. Links,
standalone disclosures and native radio/checkbox controls retain separated
focus indicators; the resize handle retains its existing inset geometry.
The composer and bordered tool chip each own their descendant's visible
focus indicator so that inner and outer focus rings cannot stack. Remove the
inline-rename glow that duplicates its boundary. Preserve field dimensions,
normal-state essential borders, focus behavior, palette and typography.

Acceptance before implementation: inspect focused request/title inputs from
the screenshots, workspace and composer selects, goal/edit and elicitation
fields, inline rename, buttons, composer and tool chips. Verify pointer and
keyboard focus, blur restoration, no geometry changes, 320/390px reflow and
forced-colors indicators. Run existing frontend checks and rebuild the
prototype and local frontend. Record results in the Completion Audit.

## Approved new-task setup refinement — 2026-09-06

The user requests less space above setup, larger Synvo/Codex logos, removal of
the introductory sentence, Task title before Your request, and visible model
and effort defaults. This amendment supersedes the earlier field order.

- Keep the horizontal layout and form width; place setup near the top of its
  available region with bounded padding and a scrollable form on short screens.
  Increase both existing logo assets from 3.15rem to 4rem without altering them.
- Show optional Task title, required Your request, Workspace, Access mode,
  then Start task. Remove the specified introductory sentence.
- The readiness banner displays the model returned by the existing status API
  and the actual default effort sent with the initial request. GPT-5.6 Sol is
  already the only permitted model. Prefer High from the advertised effort
  choices; when unavailable, explicitly show the supported fallback. Do not
  invent availability or enable creation when no model/effort can be confirmed.
- Keep effort choices in the existing workspace owner, isolated by task for
  the current H5 session. New tasks use the default, independent of the prior
  task's override. Capture that default at creation so delayed conversation
  loading cannot change the first request's settings. Users can change effort
  in the composer after creation. Model switching remains outside Phase 3.
- Preserve read-only defaults, request/retry ownership, one-time permissions,
  existing backend configuration and API contracts. No model catalog, new
  persistence or backend interface is required.

Acceptance before implementation: test banner/initial-request agreement,
High default despite an earlier task override, per-task adjustment and actual
follow-up payload, delayed first-message handoff, capability fallback/missing
metadata, and title-before-request keyboard order. Inspect 1440px and phone
layouts, short-screen scrolling, 200% text and wrapping of the status banner.
Run the frontend suite, typecheck/lint and production/prototype builds; refresh
only frontend and confirm local stack health. Existing mobile release gate
remains unchanged.

## Approved compact setup on laptop windows — 2026-09-06

The user requests smaller setup logos, text and form boxes on laptop-sized
windows, with the existing large-desktop sizes preserved. Apply a scoped CSS
variant when viewport width exceeds 760px and either height is at most 900px
or width is at most 1200px. Use available viewport dimensions rather than
device detection. Phone controls retain their existing input sizes and targets.

Compact setup uses a 33rem maximum width, 3rem square logos, 1.75rem heading,
13px form text, 36px single-line fields, 80px minimum request textarea and
tighter spacing. Keep Start task at least 40px high, preserve rem scaling,
focus boundaries, native textarea resizing and scrolling for exceptional
content, short windows or enlarged text. Leave the desktop baseline unchanged
when both width exceeds 1200px and height exceeds 900px. This changes only
new-task presentation; chat/sidebar sizing, field order, defaults and behavior
remain unchanged.

The user's follow-up requests a little more space above the compact body.
Increase its top margin from .5rem to 1.5rem (16px lower at the default root
size), and subtract that same margin from its maximum height to preserve
short-window scrolling. Verify the full form still fits at 1280×720 and
1366×768, while desktop positioning remains unchanged.

Acceptance before implementation: compare the large-screen baseline at
1440×1000 and 1920×1080; verify compact setup fits representative laptop
windows at 1440×900, 1366×768 and 1280×720; inspect both sides of width/height
breakpoints and confirm the phone layout retains 16px request text and 44px
Start targets. Check short-window scrolling, 200% text/fallback fonts and
focus visibility. Run frontend checks, production/prototype builds and local
frontend-only refresh/health verification. Record evidence below.

## Approved compact chat composer — 2026-09-06

The user requests a smaller chat input area and smaller conversation text.
This supersedes the earlier conversation typography sizes while preserving
the new-task setup, sidebar, colors, model/effort defaults and task behavior.

- Use 13px (.8125rem) user/assistant prose and result tables at 1.7 line height;
  headings use 18/16/14px for h1/h2/h3–h4. Desktop composer and its action/select
  labels use 13px, with 1.6 input line height.
- Start the textarea at one row, with a 32px desktop minimum and a 120px
  auto-growth cap before internal scrolling. Reduce frame/control spacing
  and desktop action height to 32px, targeting roughly 125px idle composer
  height instead of 170px. Keep width and control order unchanged.
- Retain 16px editable phone text, a 44px textarea minimum and 44px action
  targets. Preserve single-frame focus, Enter/Shift+Enter/IME handling,
  multiline drafts, steering/Stop, and scroll/replay ownership.

Acceptance defined before implementation: update existing textarea growth/
cap/shrink coverage, inspect idle and multiline geometry at 1366×768,
1280×720, 1440×1000 and phone widths, and confirm local overflow, focus,
enlarged text, Send/Update/Stop reachability and chat/table typography.
Run frontend tests, typecheck, lint, production/prototype builds and
frontend-only local refresh/health checks. Record verification below.

## Approved activity, focus and dropdown refinement — 2026-09-06

The user's screenshots authorize smaller activity typography, thinner blue
focus edges and balanced right padding for composer dropdown arrows.

- Use 12px (.75rem) activity phase, milestone titles, category chips, expanded
  details and receipts; use 10px elapsed metadata. Retain already-small
  overline/count text and existing semantic states, controls and behavior.
- Reduce shared and compound focus outlines from 3px to 2px while preserving
  the -1px overlap on bordered controls, one frame per compound control and
  system Highlight in forced colors. Keep focus visible without layout shifts.
- Reuse the existing inline-SVG select pattern for Reasoning and Skill,
  preserving native select semantics, labels and keyboard interaction. Reserve
  2rem on the right for a .85rem chevron inset .65rem from the edge; match
  .65rem left text padding. Do not use CSS image URLs or add dependencies.

Acceptance before implementation: inspect collapsed/expanded agent activity,
composer/select/field/tool-chip focus and forced colors; measure arrow insets
and text clearance; exercise both selects, disabled state, phone wrapping and
200% text. Run frontend checks and production/prototype builds, refresh only
frontend, verify served assets and local health, and record evidence below.

## Completion Audit

Implementation and available verification finished on **2026-09-05**. All
three checkpoints are implemented. Status remains **In Progress** because
actual mobile Lark keyboard/safe-area verification is still required; no
waiver has been requested or granted. Do not label the release fully
production-ready until that gate passes or a permitted explicit waiver is
recorded below.

### Acceptance evidence

| Gate | Evidence recorded 2026-09-05 | Result |
|---|---|---|
| Approval | Victor approved the full specification and implementation. No product/contract scope amendment was needed. | Passed |
| A1 — theme and baseline | Production owns charcoal surfaces, semantic blue actions, neutral selected rows, and the Avenir stack. OS light/dark both computed `#171819` for the page. Checkpoint 1 setup/shell measurements matched before UX changes; see baseline measurements below. | Passed |
| A2 — accessibility | Computed text contrast passed on setup, conversation/tables, Settings, expanded details/forms, connection, boot/error, and decision/error fixtures. Visible text minimum was 4.70:1 on primary actions. Solid 3px focus, forced-color system focus/borders, keyboard navigation, 200% text enlargement, reduced motion, Arial fallback, and 320px reflow were checked. Required actual-phone evidence is tracked under A7. | Passed in available browser/desktop environments |
| A3 — task creation | Tests cover read-only default, selected workspace/edit mapping, optional title, blank/duplicate submission, deferred creation/conversation loading, failed/ambiguous creation, confirmed-task load recovery, and first-message retry. Authenticated desktop H5 created one synthetic task and completed its initial request. | Passed |
| A4 — steering and Stop | Tests cover terminal, selection, round-trip selection and pending-decision races during CSRF acquisition, delayed acknowledgement, duplicate clicks, owning-operation history, and retained drafts. Live H5 accepted an update; Stop produced a terminal stopped state. A terminal draft required explicit transfer before becoming a normal message. Old delivery feedback clears for new work. | Passed |
| A5 — Task details | Existing goal/edit/pause/resume/clear, management, review, activity, inventory and history tests pass with the new disclosures. Desktop resize verified by keyboard (480 to 504px), pointer (480 to 540px), and reset (480px). Phone details fills the viewport and closes with focus restoration. | Passed |
| A6 — Settings | Existing loading/recovery/authentication regressions and missing-account test pass. H5 identity, Codex readiness, unavailable usage, configured workspace/access, and collapsed technical details are shown; Quotation remains disabled. | Passed |
| A7 — responsive/host | Browser checks at 320, 390, 760, 761, 980, 981, 1200 and 1440px passed without document overflow. Closed phone navigation has no sidebar gutter; navigation/details contain and restore focus. Existing task/interaction deep-link tests pass. A 390×500 viewport kept the composer reachable, but this is only a keyboard-size simulation. | **Pending actual mobile Lark keyboard, safe-area and sheet check** |
| A8 — results | Safe semantic table rendering, escaped pipes, alignment, partial/malformed tables, code/link/raw-HTML restrictions, and inert workspace references pass regressions. A five-column mixed-language table overflowed locally (842px content inside 230px at 320px viewport) without widening the document. | Passed |
| A9 — typography/access | Initial revamp audit: conversation/request text was 16px and standard controls were 14px at default root size. The 2026-09-06 sizing amendment below supersedes those conversation/request sizes. Avenir/Arial fallback, long/mixed-language content and 200% text enlargement were checked. `WORKSPACE_WRITE` remains the API value behind “Edit workspace files”; live scoped MCP decisions retained their exact one-time actions. | Passed |
| A10 — lifecycle/security | All existing frontend request ownership, retry, replay, terminal, cancellation and permission regressions pass. Authenticated desktop H5 reload during execution restored the selected task and active state; Stop succeeded after replay. The allowlisted harmless MCP fixture completed through its bounded decisions. | Passed |
| Automated checks/build | Clean `npm ci`, 131 tests in 15 files, TypeScript, lint, production build, prototype build and prototype TypeScript check all passed. | Passed |
| Stack | Compose configuration and full application image build passed. Final frontend image rebuilt/refreshed alone; backend, runner, frontend and PostgreSQL stayed healthy. Health and frontend-proxy status endpoints passed. | Passed |
| Documentation/scope | `DESIGN.md` v1.1 and prototype notes match the implementation; approved token values and local references checked. No backend/runner/API changes, new framework, artifact access or production fixture imports. | Passed |
| Explicit user-approved waivers | None. | None |

### Commands and build evidence

Run from `frontend/` against the final source and lockfile:

- `npm ci --cache /tmp/synvo-npm-cache`: 217 packages installed; audit reported
  zero vulnerabilities. The isolated cache does not alter dependency resolution.
- `npm test`: **131 passed in 15 files**, 8.82 seconds.
- `npm run typecheck`: passed.
- `npm run lint`: passed without warnings.
- `npm run build`: passed; production JavaScript 441.70 kB (131.59 kB gzip),
  CSS 107.31 kB (18.07 kB gzip).
- `node design/prototype/build.mjs`: passed; self-contained preview 658 KiB.
- `./node_modules/.bin/tsc -p design/prototype/tsconfig.json --pretty false`:
  passed.
- `git diff --check`: passed. No Git mutation was performed.

Table grammar adds only `micromark-extension-gfm-table@2.1.1` and
`mdast-util-gfm-table@2.0.0` as direct dependencies. Their purpose is correct
escaped-pipe/alignment/streaming parsing inside the existing Markdown owner.
The output allowlist, raw-HTML/image restrictions and safe URL transform
remain in force. No new UI or application framework was added.

Run from the repository root using `compose.yaml` plus `compose.codex.yaml`:

- `docker compose -f compose.yaml -f compose.codex.yaml config --quiet`: passed.
- `docker compose -f compose.yaml -f compose.codex.yaml build`: passed for the
  application images. The final source was subsequently rebuilt with `build frontend`.
- `docker compose -f compose.yaml -f compose.codex.yaml up --detach --wait --no-deps frontend`:
  passed; only frontend was recreated. The already-running backend, runner and
  database were preserved; stack shutdown was intentionally not performed.
- `curl -fsS http://127.0.0.1:8080/actuator/health`: `status: UP`.
- `curl -fsS http://127.0.0.1:5173/api/status`: `status: ready`.
- Compose status: backend, codex-runner, frontend and postgres all healthy.

Backend and runner code were unchanged, so their unit/integration suites were
not rerun for this frontend phase. This is the verification boundary defined
above, not a waiver of affected checks.

### Visual and host evidence

Browser: Chromium **152.0.7977.76** on macOS. Synthetic fixtures used the
production components/styles, with all network connections disabled by the
prototype CSP. The final prototype console had zero errors or warnings.
The unauthenticated plain-browser production entry emitted the expected Lark
SDK missing-desktop-bridge message outside Lark; this was not treated as an
authenticated H5 check. Actual desktop H5 was checked separately in Lark.

Checkpoint 1 at 1440×1000 preserved the theme-only baseline: expanded sidebar
252×1000; setup x=550, y=209.8125, width=592, height=640.375; form x=550,
y=484.9766, width=592, height=333.2109. These measurements precede the approved
checkpoint 3 typography/task-setup amendments and are not targets for reverting them.

Computed visible-text scans (disabled controls excluded): setup 27 elements,
minimum 4.70:1; conversation/table 32, 6.06:1; Settings 43, 5.45:1; expanded
details/forms 72, 5.45:1; production connection 18, 6.06:1; decision/error 17,
4.70:1; boot error 3, 4.70:1; boot loading 2, 8.73:1. Scans composited colors
and opacity; essential control/focus pairs were also checked separately.
These measurements are focused acceptance evidence, not a claim of a complete
external accessibility certification.

Local synthetic screenshots are in `output/playwright/`, including:
`checkpoint-1-theme.png`, `checkpoint-2-preview.png`,
`checkpoint-3-phone-preview.png`, `revamp-final-desktop.png`,
`revamp-start-phone.png`, `revamp-details-phone.png`,
`revamp-table-phone.png`, `revamp-table-desktop.png`, `revamp-text-200.png`,
`revamp-fallback-320.png`, `revamp-decision-phone.png`,
`revamp-connection-phone.png`, `revamp-error-phone.png`,
`revamp-loading-phone.png`, and `revamp-forced-colors.png`.
They are local execution artifacts; this specification preserves the evidence
summary independently of those ignored files.

Authenticated desktop Lark used the isolated **UI revamp verification —
synthetic data** task. Checks covered initial request/results, steering delivery,
terminal draft recovery, Stop, reload/replay during execution, and the existing
`synvo_safe_fixture.write_fixture_marker` bounded MCP fixture. Both scoped
prompts retained **Approve once / Decline / Cancel task** and the final result
was `SYNVO_MCP_WRITE_OK`. A transient reconnect notice recovered to the completed
result. The final frontend refresh again restored this task and its result.
No credentials or unrelated enterprise task content are recorded here. The
synthetic task and harmless fixture marker remain available for inspection.

### Sizing amendment completion audit — 2026-09-06

The user-requested appearance amendment is implemented and verified:

- Computed chat text: 16px → 14px; desktop composer and initial request: 14px;
  result table: 14px. Phone composer/request text remains 16px. Heading sizes
  follow the amended hierarchy. Root size, Avenir stack and colors are retained.
- Sidebar navigation: 12.16px → 13px; Active/Archived: 10.24px → 12px.
  Supporting sidebar roles match DESIGN.md v1.3.
- At a 1440px viewport, the filter's previous 215.81px outer width becomes
  230.20px, exactly matching the task row and its left/right edges. Both have
  approximately 37.59px desktop height. Measurements after the sidebar
  transition settled also passed at 761, 980, 981 and 1200px.
- Phone drawer alignment passed at 320, 390 and 760px; each filter segment is
  44px high. All checked widths had no document overflow. Active/Archived,
  desktop collapse/expand and phone open/close interactions passed.
- Avenir desktop/phone screenshots were inspected. Arial at 320px and 200%
  text enlargement at 390px retained wrapping and local table overflow;
  filter labels fit and neither view widened the document. The enlarged
  composer leaves a shorter scrollable conversation region. These browser
  checks do not replace the actual-mobile gate below.
- Visible enabled text contrast: 43 elements, minimum 5.45:1, no failures.
- `npm ci --cache /tmp/synvo-npm-cache`: passed, 217 packages, zero reported
  vulnerabilities. `npm test`: **169 passed in 18 files**, 8.41 seconds.
  `npm run typecheck`, `npm run lint`, `npm run build`, prototype TypeScript
  (`tsc -p design/prototype/tsconfig.json --noEmit`) and
  `node design/prototype/build.mjs` passed. Prototype rebuilt to 671 KiB.
- Compose configuration, `build frontend`, and
  `up --detach --wait --no-deps frontend` passed. Only frontend was recreated.
  Backend health returned `UP`; the frontend status proxy returned `ready`.
  Backend, runner, frontend and PostgreSQL all remained healthy. The served
  `index-CT5DXtL3.css` was checked for the new font sizes and filter margin.
- `git diff --check` passed. No Git mutation, production dependency, backend,
  runner, API or interaction-state change was made for this amendment.

Local visual evidence: `output/playwright/sizing-desktop.png`,
`sizing-phone-sidebar.png`, `sizing-phone-chat.png`,
`sizing-text-200-sidebar.png`, and `sizing-text-200-chat.png`.
DESIGN.md v1.3 and the rebuilt prototype reflect this amendment. The earlier
revamp evidence above remains a historical record of its initial delivery.

### Focus correction verification — 2026-09-06

The screenshots' double border was reproduced: the normal 1px input boundary
remained visible inside a separated 3px blue outline. Foundation and local
rules supplied different positive offsets. Shared control focus now overlaps
the existing border; redundant local rules and the inline-rename glow were
removed. Composer and tool-chip frames each own one visible focus indicator.

- Inspected request/title, workspace select, goal textarea, task-title/access
  fields, inline rename, composer/selects, primary action and tool chips in
  the production-component prototype. Focused ordinary controls computed a
  solid 3px blue outline with `-1px` offset. Request geometry was identical
  before/after focus; blur restored its normal border.
- Composer textarea and chip summary each have no inner outline while their
  frame supplies the visible 3px indicator. Moving keyboard focus to a composer
  select clears the frame indicator and focuses that select. Inline rename
  is visibly focused without an extra shadow. Native radio focus stays visible.
- Added the local `decision-form` fixture to inspect the real elicitation
  input/select and keyboard navigation. It uses synthetic component props
  and performs no backend request, file write or tool operation.
- Phone-width request fields at 320px and 390px have an unclipped focus edge
  and no document overflow. Chromium forced-colors checks retained visible
  system-color focus for fields, buttons, composer and tool chips. Focus
  screenshots were inspected, including the two originally reported fields.
- `npm ci --cache /tmp/synvo-npm-cache` passed (217 packages, zero reported
  vulnerabilities). `npm test`: **169 tests passed in 18 files**, 8.11 seconds.
  Typecheck, lint, production build, prototype typecheck and prototype build
  passed. The final standalone prototype is 671 KiB. No behavior tests were
  added solely to mirror CSS declarations.
- DESIGN.md v1.4 and prototype notes specify the shared focus treatment.
  No production dependency, request behavior, backend or runner code changed.
- Compose configuration, frontend image build and frontend-only refresh passed.
  H5 serves `index-DrdGMlS4.css`, verified to contain the shared overlapping
  focus rules and omit the old local input focus override. Backend health is
  `UP`, the frontend status proxy is `ready`, and all four services are healthy.
  `git diff --check` passed; no Git mutations were performed.

Local visual evidence in `output/playwright/`: `focus-request.png`,
`focus-title.png`, `focus-composer.png`, `focus-tool-chips.png`,
`focus-decision-form.png`, `focus-primary.png`, `focus-request-320.png`,
`focus-request-390.png`, `focus-forced-composer.png` and
`focus-forced-decision.png`.

### New-task setup verification — 2026-09-06

The user-requested setup refinement is implemented:

- At 1440×1000, setup's top moved from 129.05px to 76px; the logo row begins
  at 100px, compared with 161.05px previously. Both logos are 64×64px at the
  default root size (previously about 50×50px). Their aspect ratio remains
  square when constrained at 200% text enlargement.
- The introductory sentence is absent. Form and keyboard order is optional
  title, required request, workspace, access mode and Start task. Existing
  read-only/permission behavior and the recent focus/typography fixes remain.
- The readiness banner shows the actual status model as GPT-5.6 Sol and the
  High preference used for initial messages. The backend already required
  `gpt-5.6-sol` and defaulted reasoning to `high`; the frontend's former
  Medium preference was the source of the mismatch. No backend/config change
  or model-switching capability was needed.
- Regression cases confirmed banner/payload agreement, delayed first-message
  submission with High, per-task effort isolation, an adjusted Medium follow-up
  payload, a new High task after Medium was chosen elsewhere, explicit
  advertised fallback when High is absent, and disabled creation with missing
  model or effort metadata. Effort choices remain in the H5 session; no new
  durable preference store or API contract was introduced.
- In the browser prototype, choosing Medium in an existing task, creating a
  new task, stopping its synthetic response and changing effort afterward
  worked. The new task started at High and could then select Medium.
- Desktop/phone and short-window checks passed at 320×900, 390×844, 760×700,
  761×700, 1440×650 and 1440×1000. Setup scrolls, the Start action can be
  brought into view, and the page does not overflow horizontally. At 390px
  with Arial and 200% text, the banner wraps, logos retain their shape, and
  Start remains reachable. These are browser checks, not actual-phone evidence.
- Enabled visible text contrast: 26 elements checked, minimum 5.45:1, no
  failures. Desktop, phone and enlarged-text screenshots were inspected in
  `output/playwright/setup-refined-desktop.png`, `setup-refined-phone.png`,
  and `setup-refined-text-200.png`.
- `npm ci --cache /tmp/synvo-npm-cache` passed with 217 packages and zero
  reported vulnerabilities. `npm test`: **174 passed in 18 files**, 8.63 seconds.
  Typecheck, lint, production build, prototype typecheck and prototype build
  passed. The rebuilt standalone prototype is 671 KiB. Final logo aspect-ratio
  refinement was additionally checked in the browser and both builds.
- DESIGN.md v1.5 and the local prototype document the current setup and
  defaults. The existing mobile release gate remains open.
- Compose configuration, frontend image build and frontend-only refresh passed.
  Served assets `index-5PILOPtC.js` / `index-Bx7yt75y.css` include the new setup
  and omit the removed sentence. Backend health returned `UP`; the H5 status
  proxy returned `ready`; all four services remained healthy. `git diff --check`
  passed. No Git mutations or backend/runner changes were made.

### Compact laptop setup verification — 2026-09-06

The requested responsive sizing correction is implemented in setup-only CSS:

- At 1440×900, 1366×768 and 1280×720, setup is 528px wide and approximately
  632px tall, with 48px square logos, a 28px heading, 13px form text and 12px
  form padding. The request field is approximately 80px tall. Start task is
  visible without scrolling at all three sizes. Previously, setup scrolled
  at 1366×768 and 1280×720.
- At 1440×1000 and 1920×1080, measured desktop geometry exactly matches the
  previous baseline: 592px setup width, approximately 768px height, 76px top,
  64px logos, 35.2px heading, 14px field text, 16px form padding and a 112px
  request field. The compact rule does not match these larger windows.
- Breakpoint checks passed at widths 760/761px and 1200/1201px, and heights
  900/901px. Phone checks at 320×900 and 390×844 preserve 16px request text
  and a 44px Start action. Short windows at 1024×600 and 1280×500 retain
  scrolling with Start reachable. None of these checks produced horizontal
  document or setup overflow.
- With Arial and 200% root text at 1280×720, form text scales to 26px,
  logos remain square at 96px, and Start remains reachable through scrolling
  without horizontal overflow. Request focus retains the continuous 3px
  outline with -1px offset. Visible enabled text contrast checked 26 elements,
  with a minimum of 5.45:1 and no failures.
- Desktop and laptop screenshots were visually inspected in
  `output/playwright/compact-setup-1920.png` and `compact-setup-1366.png`;
  enlarged-text evidence is `compact-setup-200percent.png`. These are browser
  checks, not actual-mobile Lark verification.
- `npm ci --cache /tmp/synvo-npm-cache` passed (217 packages, zero reported
  vulnerabilities). `npm test`: **174 tests passed in 18 files**, 8.12 seconds.
  Typecheck, lint, production build, prototype typecheck and prototype build
  passed. The standalone prototype is 672 KiB. DESIGN.md v1.6 documents the
  compact sizing rule and preserved phone/desktop behavior.
- Compose configuration, frontend image build and frontend-only refresh passed.
  H5 serves `index-DGytwucu.js` / `index-D_625BH_.css`; the served stylesheet
  contains the compact setup rules. Backend health is `UP`, the frontend
  status proxy is `ready`, and all four services are healthy.
- `git diff --check` passed. No Git mutations, production dependencies,
  backend/runner changes or task-behavior changes were made for this correction.

### Compact top-spacing follow-up verification — 2026-09-06

In response to the next screenshot, compact setup now uses a 1.5rem top
margin instead of .5rem. Its maximum height reserves the same 1.5rem.
At 1366×768 and 1280×720 the body begins at 84px instead of 68px, while
its width, height and contents retain their compact sizes. The complete
form still fits without scrolling; Start ends at approximately 691px.
Desktop geometry at 1440×1000 is unchanged. Short-window scrolling at
1280×500 and phone scrolling at 390×844 keep Start reachable, without
horizontal overflow. The laptop screenshot was inspected at
`output/playwright/compact-setup-spacing-1366.png`.

All 174 frontend tests passed in 18 files (7.03 seconds). Production build
(including TypeScript), lint and the 672 KiB prototype build passed.
DESIGN.md reflects the adjusted margin. Compose configuration, frontend
image build and frontend-only refresh passed; the served
`index-BHSMB2KF.css` contains the new margin and matching maximum height.
Frontend is healthy, backend health is `UP`, and the H5 status proxy is
`ready`. `git diff --check` passed. No task behavior or desktop/phone sizing
was changed by this follow-up.

### Compact chat composer verification — 2026-09-06

- Idle desktop composer height reduced from 170.34px to 127.15px, recovering
  approximately 43px of conversation space. Verified at 1366×768, 1280×720,
  1440×1000 and 761×900. User/assistant text and result tables compute 13px.
- The existing auto-growth regression test now verifies growth to 92px,
  a 120px cap with internal scrolling, and shrinkage to 32px. In-browser
  20-line drafts grew to 120px and scrolled without losing text; clearing
  restored 32px desktop / 44px phone height. Shift+Enter added a newline.
- At 320×900, 390×844 and 760×900, phone input text remains 16px and named
  composer actions are at least 44px high. All measured composers and their
  actions fit inside the viewport without horizontal document overflow.
  Controls wrap at narrow widths. Table overflow stays local to its region.
- The synthetic running-task preview retains enabled, visible Update
  instructions and Stop actions. The textarea retains its single 3px frame
  focus indicator. At 1280×720 with Arial and 200% root text, conversation
  and input text compute 26px; Send remains visible, approximately 319px of
  chat viewport remains, and there is no horizontal document/composer overflow.
- Inspected desktop and phone screenshots at `output/playwright/compact-chat-1366.png`
  and `compact-chat-390.png`; enlarged-text evidence is `compact-chat-enlarged.png`.
  Enabled visible text contrast checked 43 elements, minimum 5.45:1, no failures.
- All **174 tests passed in 18 files** (8.18 seconds). Typecheck, lint,
  production build, prototype typecheck and prototype build passed. The
  final phone action-size refinement was checked in the browser and rebuilt.
  DESIGN.md v1.7 and the 673 KiB prototype document the compact composer.
- Compose configuration, frontend image build and frontend-only refresh passed.
  Served `index-DMvhApPo.js` / `index-BfhZ0iAo.css` include the updated sizing.
  Backend health is `UP`, H5 proxy status is `ready`, and all four services
  remain healthy. `git diff --check` passed. No Git mutations, dependencies,
  backend/runner contracts or task-submission behavior were changed.

### Activity, focus and dropdown verification — 2026-09-06

- Activity phase and milestone headings compute 12px; expanded detail/chip/
  receipt text uses the same scale, with 10px elapsed metadata. Collapsed
  and expanded activity remains readable and operable.
- Shared field/select focus and composer/tool-chip frame focus compute 2px,
  with -1px overlap and no duplicate inner outline. Keyboard focus checks
  used keyboard modality before focusing disclosure controls. Forced colors
  retains system Highlight focus; composer chevrons use system ButtonText.
- Both composer dropdown SVGs are vertically centered with 10.4px right
  inset, matching 10.4px left text padding. Their 32px right text reservation
  keeps text clear of the arrow. Verified at 1366×768, 390×844 and 320×900
  without document/composer overflow. Native selects retain accessible labels;
  browser selectOption changes persisted for Medium and a sample skill.
  Disabled runtime controls disable selection and dim their decorative arrows.
  Automated OS-popup arrow-key selection was inconclusive; it is not counted
  as a passed selection check. Native select event handling is unchanged.
- At 1280×720 with Arial and 200% root text, activity text scales to 24px,
  arrow inset to 20.8px, and the composer stays visible without horizontal
  overflow. Desktop, phone, collapsed focus and forced-colors screenshots
  were captured under `output/playwright/activity-refined-*`; desktop and
  focused/high-contrast views were visually inspected.
- All **174 tests passed in 18 files**, 6.85 seconds. Typecheck, lint,
  production build, prototype typecheck and the 673 KiB prototype build
  passed. Final block-alignment and high-contrast SVG refinements were checked
  in the browser and rebuilt. DESIGN.md v1.8 and prototype notes are current.
- Compose configuration, final frontend build and frontend-only refresh
  passed. H5 serves verified assets `index-CWcyc63H.js` / `index-D5Eh8FOE.css`.
  Backend health is `UP`, H5 proxy status is `ready`, and all four services
  remain healthy. `git diff --check` passed. No Git mutations, dependencies,
  backend changes or task/approval behavior changes were made.

### Remaining release gate

A7 requires a real phone running mobile Lark. Verify navigation open/close,
Task details Close/Back, safe-area insets, and a multiline composer draft with
the software keyboard open and dismissed. Confirm Send/Stop remain reachable,
scrolling stays usable, focus returns predictably, and no content is clipped.
Record device/OS, Lark version if available, results, and date here. This
session had desktop Lark and browser simulation, but no actual phone access.
The user was asked for this check; no result or waiver has been received.

Only after that evidence passes (or a permitted explicit waiver is approved)
may this specification move to **Complete**. A verification waiver cannot
silently authorize a product-scope or permission change.
