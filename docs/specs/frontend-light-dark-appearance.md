# Frontend Light and Dark Appearance

Status: **In Progress**

Created: 2026-09-06

Approval: Victor approved this specification and Light-mode implementation
on 2026-09-06. Status advanced through Approved before implementation started
on 2026-09-06.

## Purpose and objectives

Add a coherent Light appearance alongside the approved Dark design and make
Synvo H5 follow Lark's own appearance setting on supported clients. Preserve
the current layout, compact typography, responsive behavior and task lifecycle.
Future approved workflows must inherit both appearances from shared tokens.

The intended result is that changing Lark from Dark to Light changes the H5
interface to Light without reloading, losing a draft, interrupting a streamed
answer, or affecting a pending decision. A browser/OS preference alone does
not establish that Lark synchronization works.

## Governing references

- [AGENTS.md](../../AGENTS.md): approval lifecycle, scope, verification and Git rules.
- [Project overview](../project-overview.md) and
  [Phase 3 specification](phase-3-codex-in-lark.md): supported H5 product and boundaries.
- [DESIGN.md section 14](../../frontend/design/DESIGN.md#14-light-and-dark-appearance):
  approved paired appearance rules, complete Light palette and elevation.
- [Frontend revamp](frontend-ui-ux-revamp.md) and
  [agent interaction clarity](frontend-agent-interaction-clarity.md): current
  behavior, sizing amendments, motion and remaining live acceptance gates.
- [Development lessons](../LESSONS.md): match verification to the actual deployed flow.
- [Software design principles](../PRINCIPLES.md): information hiding and design checklist.
- [Beautiful UI](https://www.beautifului.dev/): visual inspiration only; its
  samples, workflows and SDK assumptions are not Synvo requirements.

The earlier revamp explicitly excluded a theme switch. This approved phase adds
a bounded amendment for two appearances and automatic source resolution.
It preserves that baseline's Dark appearance and geometry.
It adds no workflow or backend capability and does not reopen Phase 3 closure.

## Decisions

| Concern | Decision |
|---|---|
| Visual direction | Light: off-white/white/grey surfaces, charcoal text, restrained blue controls; Dark: existing approved values |
| Geometry and typography | Keep current shell, logo sizes, component sizes, font roles, laptop scaling and phone behavior |
| Token ownership | One foundation defines paired semantic values; existing components consume them |
| Default in Lark | Follow a verified Lark H5 appearance source, including changes while open and after resume |
| Plain browser | Follow browser `prefers-color-scheme` and change events |
| Missing source | Dark before a usable source; retain last valid in-memory appearance during a transient failure |
| Capability uncertainty | Verify the exact current H5 integration before promising host matching; no guessed SDK methods |
| User preferences | No product theme selector, stored override or account preference in this phase |
| Scope and dependencies | Frontend only; current React/TypeScript/Vite/CSS approach; no new production dependency |

DESIGN.md owns color values and component recipes; this specification owns
source behavior, scope and acceptance. Do not duplicate a second token table
here. Temporary investigation and design-comparison notes belong in `tasks/`.

## Source compatibility and rollout evidence

Verified on 2026-09-06 against the official rendered H5 documentation:

- [getSystemInfo](https://open.feishu.cn/document/client-docs/gadget/-web-app-api/device/system-information/getsysteminfo)
  explicitly excludes `theme` output for web apps.
- [onThemeChange](https://open.feishu.cn/document/client-docs/gadget/-web-app-api/interface/darkmode/onthemechange)
  and [offThemeChange](https://open.feishu.cn/document/client-docs/gadget/-web-app-api/interface/darkmode/offthemechange)
  mark web apps unsupported on Android, iOS and PC. Gadget support and Base
  extension methods are not H5 contracts. No undocumented SDK methods are used.
- The production H5 SDK remains **1.5.44**. The authorization adapter is unchanged.

A bounded probe inside the actual authenticated H5 page on **macOS Lark
7.75.20** (Chromium framework **143.0.7499.203**) reported `LIGHT` while Lark's
Light choice was selected and macOS remained Dark. Selecting Lark Dark later
reported `DARK` and incremented the change counter in the same document.
Thus this client's WebView exposes Lark's own appearance through standard
[`prefers-color-scheme`](https://www.w3.org/TR/mediaqueries-5/#prefers-color-scheme).
It is the chosen source for this implementation. The temporary served probe
was removed after discovery; it did not inspect task data or store values.

Plain browsers use the same synchronous media API for their browser/OS
preference. One root owner handles initial read, live changes, resume and
cleanup; no delayed SDK discovery, asynchronous bridge request, new SDK,
permission scope, polling loop or host-to-component payload is needed.
Unsupported or unavailable sources retain a valid value or Dark; they are
not claimed to satisfy host matching. Mobile/Windows compatibility and the
remaining native matrix below require separate actual-client evidence.

The probe must distinguish Lark's selection from OS preference:

| Lark setting | OS preference | Required resolved H5 appearance |
|---|---|---|
| Light | Light | Light |
| Light | Dark | Light |
| Dark | Light | Dark |
| Dark | Dark | Dark |
| Follow system, where available | Change Light ↔ Dark | Follow the appearance Lark actually resolves |

Test cold opening, switching while the H5 app is visible, returning from Lark
Settings/background, and reopening. Identify the desktop and phone client
versions supported for this rollout and record evidence for each. Do not
infer a Windows or mobile result from a macOS desktop result.

A media-query source inside Lark may satisfy this requirement if those live
cases prove that the WebView exposes Lark's setting. If no suitable source
exists, document the limitation and propose a specific scope amendment, such
as browser/OS following or a manual selector. Such a fallback needs explicit
user approval; it must not be shipped or reported as automatic Lark matching.
Local visual prototype work can still proceed while source discovery is open.

## Scope and deliverables

1. **Paired foundations:** all appearance-bearing tokens in the current
   foundation, retained Tailwind aliases, shadows, backdrops, scrollbars,
   native controls and document theme metadata. Keep the approved Dark values.
2. **Prototype:** a local Light/Dark preview under `frontend/design/prototype`,
   using current components and synthetic in-memory data. Preview both task
   setup and conversation, sidebar, details, settings, dialogs and the six
   agent interaction patterns. Keep the original historical baseline intact.
3. **Appearance resolution:** one frontend owner, verified host adaptation,
   browser fallback for ordinary browser use, cleanup and safe startup/resume
   behavior. No task-specific appearance state.
4. **Component coverage:** loading, connection, error and recovery screens as
   well as authenticated content. Check all states in DESIGN.md section 11.2,
   including mixed-language text, long results and bounded decisions.
5. **Acceptance evidence:** source compatibility, before/after screenshots,
   computed/composited contrast, lifecycle regression tests, and real Lark
   verification. Match evidence to the frontend assets actually served.
6. **Adopted documentation:** integrate the approved values and behavior into
   the main design system when implementation is ready; retain historical
   audits and explicitly record remaining gates in this specification.

## Implementation boundaries and invariants

Extend the existing foundation and document bootstrap. One application-level
appearance owner normalizes supported inputs to `light` or `dark` and applies
the result on the document root. Its only product side effects are appearance,
native `color-scheme` and theme metadata. Source failures are contained in
this owner. No SDK call or vendor payload reaches components.

Compare component-local subscriptions with one root owner in task notes using
the PRINCIPLES.md checklist before implementation. Prefer the root owner: it
contains precedence, initial-read races, resume and listener disposal once.
This is a focused frontend concern, not a generic theme runtime or provider
framework. The verified media source needs no new H5 adapter interface.

- On the verified Lark client, the WebView media value already reflects the
  host choice independently of OS preference. Do not infer host availability
  from `requestAccess`, subscribe to a second OS source, or add bridge fallback
  calls. Ordinary browsers use the same source with browser/OS semantics.
- Ignore invalid source values. Retain the last valid appearance on temporary
  source failure; default to Dark when none is known. Missing appearance data
  must not delay or break authorization, task creation, or conversation use.
  This runtime degradation does not satisfy the host-matching release gate.
- A stale initial read cannot replace a newer change event. Rapid changes
  settle on the latest valid source state. Register once per owner, clean up
  on disposal, and re-read on resume only where the verified source needs it.
- Components, including portals/overlays, inherit one root appearance. Theme
  changes never remount the workspace, change component keys, navigate,
  reauthenticate, reconnect SSE, change request identity or submit an action.
- Preserve composer and task-setup drafts, cursor/selection, scroll position,
  selected task, expanded details, modal focus and in-flight decision state.
  Keep real streaming, activity grouping and terminal authority unchanged.
- Apply a known initial value as early as feasible within the existing CSP.
  Check body, boot state, overlays, native controls and `theme-color` together.
  Do not wait on an asynchronous bridge behind a blank screen or promise
  control over native chrome before the page loads. Record any visible late
  correction and resolve disruptive flashes before acceptance.
- Theme changes are immediate color updates without global fades or replayed
  entrance animations. Reduced-motion and forced-colors behavior still wins.
- No local/session storage or backend preference. Do not persist SDK payloads,
  task data, credentials or sensitive content for theme debugging. Capture only
  appearance enums and client/version information with synthetic test content.
- Preserve existing security, permissions, scope, transport, URL handling,
  content restrictions and no-store boundaries. Do not alter Java, runner,
  database, HTTP/SSE or Lark authorization contracts.

## Build order

1. **Approve the specification.** Completed by Victor on 2026-09-06; status
   advanced through Approved to In Progress before implementation.
2. **Define acceptance fixtures and capture baseline.** Reuse current
   synthetic scenarios and existing lifecycle tests. Record the current Dark
   desktop, compact laptop and phone geometry before introducing Light.
3. **Verify source compatibility and build the visual preview.** These can
   proceed independently after approval. Present the preview and record the
   source strategy. Resolve any material visual or source-scope deviation
   through an explicit amendment before dependent production implementation.
4. **Implement shared tokens and appearance ownership.** Use the verified
   source, preserve Dark, correct affected hard-coded/composited colors, and
   cover races and disposal with focused behavioral tests.
5. **Verify every affected surface and live flow.** Run the matrix below and
   DESIGN.md section 11.2, fix failures, and confirm served build identity.
6. **Adopt and close.** Update the design system's main sections, record all
   evidence and any explicitly approved waiver in the Completion Audit, and
   mark Complete only when required work is finished.

Approval of this specification authorizes these defined steps without
repeated approval for routine implementation choices. A host integration
requiring new permissions or an SDK upgrade, a manual preference, or inability
to deliver host matching is a scope decision requiring an explicit amendment,
not a routine choice.

## Acceptance criteria and test plan

Design behavioral cases before implementation. Do not add tests that merely
assert every CSS literal. Use calculated contrast and rendered checks for
appearance, and behavioral tests for source resolution and state preservation.

| ID | Acceptance criterion | Required verification |
|---|---|---|
| L1 | Light implements all approved semantic roles and retained aliases; Dark preserves current values and geometry | Token inventory; computed styles and matching screenshots of both themes; audit raw colors, opacity and color-mix consumers |
| L2 | All supported surfaces and six interaction patterns remain coherent in both themes | DESIGN.md 11.2 matrix; loading, thinking, streaming, pending/submitting/resolved/expired decisions, tool chips, task/execution rows; screenshots with synthetic content |
| L3 | Text, essential boundaries, meaningful icons and focus meet section 8 thresholds in actual rendered states | Contrast measurements including neutral/semantic fills, hover, focus and disabled treatment; 2px continuous focus; no double border; check primary-button focus separately |
| L4 | H5 follows Lark's own appearance on every supported rollout client, including opposite OS preference | Actual Lark matrix above with client/SDK versions; initial open, live switch, resume and reopen; source identified and documented |
| L5 | Plain-browser preference changes work, failures degrade predictably, stale notifications cannot win | Source tests for Light/Dark/unknown, absent API, current-state notifications, rapid changes, duplicate setup/disposal and resume; asynchronous/delayed bridge cases are inapplicable to the verified synchronous source |
| L6 | Theme changes do not lose state, restart streams or duplicate user actions | Integration/component tests switching during unsent input, selection, streaming, active update, pending/submitting decision and reconnect; assert draft/focus/scroll preservation and unchanged API/SSE ownership |
| L7 | Startup, native controls, overlays and metadata match the resolved theme without a disruptive flash | Cold-load/reload checks with available/unavailable media source; input/select popup, dialog/drawer and native WebView review; appearance failure never blocks auth |
| L8 | Current responsive sizing and accessibility remain usable | 320/390/760/980/1200/1440px widths; 1280×720 and 1366×768 compact laptops; 1440×1000 desktop; both sides of affected breakpoints; 200% text, Avenir/fallback, keyboard, screen reader, reduced motion and forced colors |
| L9 | Real H5 interaction survives a theme change | Authenticated desktop and actual phone Lark: task creation, stream, follow-up/steering, stop, safe bounded decision and return from background; mobile keyboard, safe area, scroll and focused composer in both themes |
| L10 | Build, existing regressions and adopted documentation agree with the deployed result | Frontend checks below; relevant healthy-stack smoke checks; served asset identity; DESIGN.md updated; no prototype fixtures or new dependencies in production |

Use only synthetic or redacted prompts/data/screenshots. A local sample or
mocked host callback proves presentation, not Lark integration. Test a bounded
decision through the existing harmless fixture and policy; do not force a real
command-elevation request or change permissions to make a visual state appear.

After application implementation, run from `frontend/`:

```sh
npm ci
npm test
npm run typecheck
npm run lint
npm run build
```

Rebuild and inspect the local prototype through its existing documented build
path. Run relevant Compose/health and vertical-slice checks from AGENTS.md for
the deployed result being claimed, without disrupting another running stack.
No backend/runner changes are planned; their suites become relevant if a
separately approved amendment changes those areas.

Record unavailable environments explicitly. A required check is not waived
by lack of access, passing unit tests or approval of this specification. Any
waiver must be explicit and recorded; failure to support Lark matching requires
a scope amendment and accurate revised product wording, not a passing L4 label.
Existing frontend phases' pending mobile/live gates remain open unless their
own required evidence is recorded.

## Explicit non-goals

- Redesigning the shell, field order, navigation, typography, component sizes,
  chat/composer layout, model/effort defaults or interaction vocabulary.
- A manual theme picker, per-task/workflow themes, persisted appearance,
  scheduling, additional palettes, or a theme framework.
- Importing Beautiful UI code/components, speculative animations or workflows.
- SDK upgrades, new permissions, backend/runner/database/API changes,
  Lark business-resource reads/writes, new artifacts access or broader grants.
- Inverting supplied logos/user images, disabling zoom/forced colors, or
  introducing large brand gradients and blue-filled workspace backgrounds.
- Replacing the approved historical prototype, adding a production dependency,
  performing Git mutations, or claiming unrelated pending phases complete.

## Completion Audit

Phase status remains **In Progress** until the actual-client release gates
below have evidence. The implementation is approved and present; remaining
verification is not waived by approval or by passing desktop browser checks.

| Evidence | Current result |
|---|---|
| Approval | Victor approved specification and implementation on 2026-09-06 |
| Scope / design | DESIGN.md v1.9 adopts paired semantic colors and elevation; Dark values and component geometry preserved; Light filled-action focus gets a 3px gap around its 2px outline |
| Source contract | Official H5 SDK exclusions checked; macOS Lark 7.75.20 exposes host appearance through media queries, including Light while OS Dark and a live Dark change event; no SDK change |
| Source behavior | Initial read before React, early HTML paint selection, current-value notifications, legacy listeners, replacement/disposal and resume; unknown/error retains last valid value or Dark; no storage or task/API state |
| Base contrast | Draft calculation covered 123 opaque pairs and all 12 published ratios; rendered checks recorded below supplement this, rather than certify all compositions from token values |
| Visual / responsive | 2026-09-06: 32 settled scenario/theme combinations rendered with correct appearance, no page overflow and fully visible dialogs. Task setup geometry matched exactly in Light/Dark across 11 viewports (22 renders), covering 320, 390, 760/761, 980/981, 1200/1201, 1280×720, 1366×768 and 1440×1000. Phone navigation, Settings and details sheets reviewed; 320px with 200% text and Arial fallback retained reflow and scrolling draft input |
| Rendered contrast / focus | 340 visible enabled text checks across setup, connection, tools, approval, failure and decision-form states in both themes passed 4.5:1; minimum 4.70:1. Calculations resolved CSS color-mix and alpha backgrounds and excluded disabled/hidden content. Light approval and Settings destructive-action focus measured 2px with a 3px contrasting gap; forced colors resolved to Highlight; reduced-motion drawer animation/transition measured 0.00001s. These are sampled rendered checks, not a claim that every possible state was measured |
| State preservation | New integration test switches during an active stream, unsent steering, pending decision and submitting decision; verifies focus/selection, retained draft, continued content and no duplicate submit/steer/decision or stream subscription. Browser live change retained textarea DOM identity, selection 2–8, draft and URL, and 126.6484375px composer height. An immediate-switch regression check found inherited sidebar color fades; the owner now finishes palette transitions only. Recheck showed the exact target sidebar color immediately, zero pending palette transitions, retained input/focus, and continuing layout/activity animations |
| Screenshot evidence | Local ignored `output/playwright/appearance-*.png`; reviewed Light setup (1366), tools, settled approval drawer, conversation and phone sheets. Before/after baseline and paired setup captures retained there |
| Frontend checks | 2026-09-06: npm ci; npm test — **186 tests in 19 files passed**; npm run typecheck; npm run lint; npm run build all passed. Prototype rebuild (677 KiB) and its TypeScript check passed. No new dependency; no backend or runner changes, so their suites were not rerun |
| Documentation checks | DESIGN.md v1.9 and prototype README updated; phase spec made visible to version control through the existing .gitignore allowlist. 18 local links and all 48 implemented Light token values match DESIGN.md; git diff --check passed |
| Deployment | 2026-09-06: Compose config validated; built and replaced frontend only, container healthy. Backend /actuator/health UP; frontend /api/status ready. Served HTML contains the early appearance bootstrap and no diagnostic probe; no-store retained. Desktop browser loaded served Light assets and switched to Dark with matching native scheme/metadata |
| Actual deployed Lark | macOS Lark 7.75.20: reloaded updated app in Dark; selected Light with OS still Dark, then returned to Dark. Selected task, visible scroll position, composer focus on return from Settings and synthetic unsent draft survived without reload. Synthetic draft cleared without sending; original Dark preference restored. Native H5 SDK/theme verification is separate from plain-browser SDK warning about an unavailable PC bridge |
| Actual-client gates | Actual phone Lark, Windows if supported, full opposite-OS/follow-system matrix, native screen-reader check, and authenticated stream/steer/stop/bounded-decision transitions on each rollout client remain open |
| Waivers | None |

Historical drafting checks (2026-09-06) validated the token inventory, 19 links,
123 opaque contrasts and unchanged application hashes. Those checks applied to
the documentation-only draft; they are not a claim that application code stayed
unchanged during this approved implementation.

Deployed image manifest list:
`sha256:7af3daf524eddeb45182eaf5821a67bf7f04feeecdda90653214a864e92b0050`.
Served assets and SHA-256:

- `index-CJLJBDIR.js`: `279a23d30688843aef64e2a28bfd88e58aac340f24120b0eb6addb7018bb17f7`
- `index-YmQ9zF-m.css`: `e66d65ca320b29d7b0a07c1eab61cc19006efb9a77b815367e799ff0a3c35498`

Local macOS Vite build filenames differ from the container build; the hashes
above identify the assets actually served and inspected in the deployed flow.
Full stack shutdown/rebuild was not run: only frontend changed, and the existing
healthy backend, PostgreSQL and runner were preserved.

L1/L2/L3/L5/L6/L7/L8/L10 have implementation and bounded automated/browser
coverage as described above. Remaining native cold-load, screen-reader,
full interaction and mobile matrix evidence is still required; L4/L9 and
whole-phase closure are **not** claimed complete. No unavailable check has
been waived. No credentials, enterprise payloads or real decision grants were
used in appearance debugging.

## Approved refinement — quieter scrollbars (2026-09-06)

Victor requested a much thinner, partly transparent sidebar scrollbar that
appears when hovering the sidebar, with matching chat scrollbar shape/color.
This is an approved CSS refinement within the existing appearance phase.

- Use the shared rounded 3px scrollbar and translucent theme-specific grey
  thumb in both sidebar and conversation; keep the track transparent.
- On a fine hover pointer, conceal the sidebar thumb until the sidebar is
  hovered or contains keyboard-visible focus. Keep its gutter/scrolling geometry
  stable. The chat thumb uses the same visual primitive.
- Touch/non-hover environments retain a visible scroll affordance. Forced
  colors retain system-contrast scrollbars. No JavaScript scroll imitation,
  new dependency or changes to task/stream ownership.
- Verify actual computed standard/pseudo styles (standard non-auto scrollbar
  properties can override WebKit styling), hover exit/re-entry, keyboard focus,
  wheel scrolling, no layout shift, Light/Dark and touch/forced-color behavior.
  Rebuild the preview and production frontend, and verify the served assets.

This amendment supersedes prior scrollbar token values and the scrollbar part
of the original unchanged-Dark baseline. Other appearance acceptance gates and
remaining actual-phone checks are unchanged.

Verification on 2026-09-06:

- Both themes: computed custom width 3px and rounded 999px thumb on sidebar
  and chat. Standard properties resolve to auto in Chromium, so they no longer
  override the custom styling. Thumb colors match the shared theme tokens.
- Idle/hover/leave/keyboard checks passed. Hidden sidebar thumb is fully
  transparent; hover and keyboard-visible focus reveal it. Sidebar client
  width remains 227px and outer width 230px in all reveal states. Wheel input
  moves scrollTop from 0 to 240 without altering chat scroll position.
- Touch-emulated 390×844 client keeps a visible translucent thumb and has no
  page overflow. Forced colors resolves to system thumb color and auto width.
  These are browser checks, not new actual-phone acceptance evidence.
- Reviewed paired screenshots in local ignored
  `output/playwright/scrollbar-{dark,light}-{idle,hover}.png`. Overflow uses
  duplicated synthetic prototype rows only; no production task data changed.
- All 186 frontend tests passed; typecheck, lint, production build and prototype
  rebuild passed. DESIGN.md v1.10 and prototype guidance updated.
- Frontend-only deployment and served build verification are recorded below.


Refinement deployment verified: frontend container healthy; backend health UP;
frontend API ready. Served CSS contains the custom 3px scrollbar and sidebar
reveal rules. Image manifest list:
`sha256:609cf73f0ce8fb1a2860ef0e08e6e5812ffa17b2a13918a48c57680ab631fb0b`.

- `/assets/index-Chd5JYMN.js`: `279a23d30688843aef64e2a28bfd88e58aac340f24120b0eb6addb7018bb17f7`
- `/assets/index-D2OL3ngB.css`: `6e193bd20a9c488de69b6d737c5dd6b665f418e52bc88d6a410c6c0658261e4b`

### Approved positioning correction (2026-09-06)

Victor requested that the sidebar scrollbar sit against the sidebar's right
border, with space separating it from the task content. Extend only the scroll
viewport through the sidebar's existing end padding and restore that spacing
inside the viewport. Keep the task-row/filter widths, 3px translucent thumb,
hover/keyboard reveal, fixed header/footer and chat scrollbar unchanged.

Acceptance: measure the viewport ending at the inner sidebar border and at
least the existing .65rem spacing between row/filter content and scrollbar;
compare row widths before/after and hover states; check expanded, collapsed,
phone and both appearances. Rebuild/inspect the preview and served frontend.
Verification on 2026-09-06:

- At 1366×768 in both themes, the expanded scroll viewport ends exactly at
  the inner sidebar border. The row-to-scrollbar gap is 10.398px; row/filter
  widths remain 227.203px before/after and across idle/hover states. Chat width
  remains 1114px. The 3px thumb still hides/reveals with the existing rules.
- Collapsed navigation keeps the scrollbar at the border and a 15.203px gap
  beside the centered icon rows. Touch-emulated 390×844 navigation also ends
  at the border with 10.4px internal end padding. No tested layout introduced
  horizontal page overflow; phone emulation does not close native-client gates.
- Reviewed local ignored `output/playwright/sidebar-edge-{dark,light,phone}.png`.
  Desktop overflow used duplicated synthetic prototype rows only.
- All 186 tests in 19 files, typecheck, lint, production build and prototype
  rebuild passed. DESIGN.md v1.11 and prototype guidance document the spacing.
  `git diff --check` passed. No backend or runner code changed.
- Frontend-only deployment is healthy; backend health is UP and the frontend
  API is ready. Served CSS confirms the spacing token, end margin and internal
  padding. The first literal check mismatched minifier whitespace; inspection
  and whitespace-normalized verification passed without a code change.

Positioning correction image manifest list:
`sha256:cd81cc060c3e4bd1ec7ffe0e720d8e4291432d92429b0927b8cb3bce25e02978`.
Served assets and SHA-256:

- `/assets/index-DQSjhmz4.js`: `279a23d30688843aef64e2a28bfd88e58aac340f24120b0eb6addb7018bb17f7`
- `/assets/index-C2MgKFEN.css`: `6f78e45fb4d2d518caaf2854a0cdbba4ea8eb7db1dfbebec2d952b9041ccbaf5`
