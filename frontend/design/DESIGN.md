# Synvo H5 Design System

Status: **Approved design system — implementation reference**

Version: 1.11 · Updated: 2026-09-06 · Owner: frontend

Applies to: the H5 frontend and all future approved features and workflows

Victor approved the charcoal prototype's color theme and requested this guide
as the frontend implementation reference. This approval covers the visual
direction; it does not approve a new workflow phase or certify a production
deployment. The approved revamp is implemented; section 11 and its phase
specification define release verification.

The [Light/Dark extension in section 14](#14-light-and-dark-appearance) and its
[phase specification](../../docs/specs/frontend-light-dark-appearance.md) were
approved on 2026-09-06. Both appearances are implemented through shared tokens;
the phase audit records client compatibility and outstanding live release gates.

## 1. Purpose and precedence

This document defines the target visual language, component vocabulary,
interaction patterns, and accessibility requirements for Synvo H5. New
features and new workflows (starting with `wf-keystone-quotation`) must read
as part of the same application, reuse the same primitives, and respect the
same trust-boundary presentation rules.

Precedence when guidance conflicts: the current task instruction, then
`AGENTS.md`, then `docs/project-overview.md` and the governing phase or
workflow specification, then `docs/LESSONS.md`, then `docs/PRINCIPLES.md`,
then this file. This file never authorizes a new API field, a backend change,
a new UI framework, or a change to approval policy.

Read this guide before implementing frontend work. Use these references:

- [Approved visual prototype](prototype/index.html): task setup, navigation,
  conversations, composer, and Task details using the actual app components.
- [Original theme baseline](prototype/theme-baseline.html) and
  [preview and rebuild instructions](prototype/README.md). Production tokens
  in `src/styles/foundation.css` are authoritative; `prototype/theme.css` is
  retained only as a historical reference.
- `src/codex/CodexWorkspace.tsx` and `src/styles/`: current layout, sizes,
  interactions, and responsive behavior. Paths beginning `src/` in this guide
  are relative to `frontend/`.

**Preserve the approved desktop shell.** The theme migration retained its
geometry. The approved [UI/UX revamp specification](../../docs/specs/frontend-ui-ux-revamp.md)
then introduced bounded changes: instruction-first setup, steering in the
composer, prioritized Task details, current-product Settings, phone overlays,
and readable conversation/form typography. These are now the implementation
baseline. Preserve navigation order, desktop sidebar/main/panel arrangement,
and existing API/lifecycle ownership. Future features compose this shell;
they do not redesign it or introduce a theme switch as a side effect.

The prototype contains local sample APIs and simulated activity. Its runtime
status, workspace names, and messages are not product contracts.
Do not import its fixtures, bootstrapping, CSP, or generated HTML into the
production application. Accessibility requirements in section 8 are release
gates even where inherited prototype styling needs a targeted correction.

Update this file in the same change that adds a token, a component, a state
vocabulary, or a workflow module. A design decision that lives only in a CSS
file or a component is not yet part of the system.

## 2. Product context the UI must express

- **One user inside Lark.** Victor opens Synvo from the Lark Workplace tile.
  The app runs in the Lark H5 WebView on desktop and mobile, and in a plain
  browser for development. Lark owns identity chrome; Synvo never repeats the
  user's profile in the sidebar.
- **H5 is the supported surface.** Native Lark Chat is a deferred companion.
  Phase 3 serves document, report, presentation, CSV, and numerical-data tasks;
  neither knowledge ingestion nor software-development workflows are implied
  by a sample screen. New workflows need their own approved specifications.
- **The trust boundary is the product.** Routine work inside the selected
  folder runs automatically. H5 shows a one-time decision only for a bounded
  workspace-relative file change or an allowlisted MCP request. There is no
  command approval, no session grant, no Safe Approve, no Full Access. Access
  outside the selected folder is blocked, never prompted. Copy, controls, and
  visuals must never suggest otherwise.
- **Honest state.** Every screen shows one of a finite set of application
  states that come from the backend contract. The UI never infers lifecycle
  from text, never invents a pending decision, and never hides a terminal
  outcome.
- **Sensitive detail is transient.** Commands, paths, MCP arguments, and
  outputs appear only in owner-scoped no-store views, are never written to
  browser storage, and are always framed by a privacy notice.

## 3. Design principles

1. **Neutral surfaces, readable content.** Black/charcoal in Dark and off-white/white in Light, with grey controls,
   establish hierarchy. Text and interactive cues remain legible. The conversation and
   its results carry the visual weight.
2. **State before decoration.** Color is used to encode state first (running,
   waiting, completed, failed, stopped) and brand second.
3. **Everything degrades.** Loading, empty, error, reconnecting, and terminal
   states are designed for every surface. Auxiliary data failing never hides
   durable data.
4. **Same primitive, same behavior.** A row, a chip, a card, a drawer, and a
   notice behave identically wherever they appear. A new module composes them
   before it invents.
5. **Keyboard and screen reader are first-class.** Every state change is
   announced; every control is reachable; every modal contains and restores
   focus.
6. **Motion explains, never entertains.** Transitions are short and directional.
   Ambient loops mean "alive", nothing else. Reduced motion removes all of it.
7. **Color has a specific job.** Blue identifies primary actions, links, focus,
   and activity. Green, amber, and red identify outcomes or attention. Keep
   large backgrounds neutral; use the existing cyan Synvo asset for branding.

## 4. Foundations

### 4.1 Color tokens

The application supports **Dark and Light**. The table below defines the
approved Dark baseline; section 14 defines the paired Light values. Both live
in `src/styles/foundation.css`: `:root` supplies Dark fallback values and
`:root[data-appearance="light"]` supplies Light overrides. Components use
semantic tokens in both modes; never create a second component stylesheet.

`src/appearance.ts` resolves the environment's appearance and updates the
root, native `color-scheme` and document `theme-color`. Lark desktop's verified
WebView media source reflects Lark's own choice; plain browsers reflect their
browser/OS setting. See section 14.5 and the phase's client compatibility audit.
There is no in-app theme preference. Preserve the exact approved Dark values.
The original comparison remains frozen in `prototype/theme-baseline.html`.

The table defines production token names. Existing names are retained where
their meaning remains useful; new semantic names replace prototype-only and
violet-specific names through the alias map below. These tokens are implemented in
`src/styles/foundation.css` and form the production appearance contract.

| Token | Approved Dark value | Role |
|---|---|---|
| `--page-bg` | `#171819` | Document and body background, no radial tint |
| `--sidebar-bg` | `#171819` | Sidebar |
| `--surface-strong` | `#1c1d1f` | Main canvas, topbar, dialog, interaction drawer |
| `--surface-panel` | `#202123` | Task details panel |
| `--surface` | `#232426` | Cards, composer, setup form, New task control |
| `--surface-muted` | `#2b2c2f` | User bubble, quiet controls and hover fills |
| `--surface-selected` | `#292a2d` | Selected task row and access-mode card |
| `--accent-soft` | `#292a2e` | Neutral fill used by existing accent-tinted recipes |
| `--ink` | `#ededf0` | Primary text |
| `--ink-soft` | `#b5b5bd` | Secondary text and labels |
| `--ink-faint` | `#a1a1aa` | Hints, timestamps, metadata |
| `--border` | `#343539` | Quiet dividers and card edges |
| `--border-strong` | `#48494f` | Emphasized decorative borders; see section 8 for essential control boundaries |
| `--border-control` | `var(--ink-faint)` | Essential editable-control boundary |
| `--border-selected` | `#35363a` | Neutral inset edge of selected task row |
| `--border-selected-control` | `#607c9b` | Selected access-mode card outline |
| `--accent` | `#69afff` | Activity indicators, small accent icons, links |
| `--accent-strong` | `#8cc6ff` | Emphasized links, checked native controls |
| `--primary` | `#1672db` | Filled primary action background and border |
| `--on-primary` | `#ffffff` | Text and icons on primary actions |
| `--cyan` | `#03adf0` | Synvo brand accent; keep supplied logo artwork intact |
| `--positive` / `--positive-soft` | `#6bd69b` / `#22372b` | Success, connected, completed; text/indicator and soft fill |
| `--warning` / `--warning-soft` | `#f0be72` / `#3a3021` | Waiting, attention; text/indicator and soft fill |
| `--negative` / `--negative-soft` | `#ff96a3` / `#3d262c` | Error, stopped, expired; text/indicator and soft fill |
| `--destructive` / `--on-destructive` | `#ad3048` / `#ffffff` | Filled destructive actions |
| `--focus` | `#69afff` | Keyboard focus |
| `--scrollbar-size` | `.1875rem` (3px at default text size) | Shared custom scrollbar width/height |
| `--scrollbar-track` | `transparent` | Scrollbar track |
| `--scrollbar-thumb` / `--scrollbar-thumb-hover` | `rgb(161 161 170 / 38%)` / `rgb(161 161 170 / 58%)` | Translucent neutral scrollbar |
| `--backdrop` | `rgb(0 0 0 / 58%)` | Dialog and interaction backdrops |

Compatibility map for existing selectors and the prototype:

| Existing name | Production destination |
|---|---|
| `--violet`, `--color-synvo-violet` | `var(--accent)` |
| `--violet-strong` | `var(--accent-strong)` |
| `--violet-soft` | `var(--accent-soft)` |
| `--danger` | `var(--negative)` |
| `--color-synvo-cyan` | `var(--cyan)` |
| `--prototype-primary` | `var(--primary)` |
| `--prototype-on-primary` | `var(--on-primary)` |

The migration replaced old violet/danger consumers with semantic names;
new CSS must use the production names directly. Do not copy the prototype's
`data-prototype-theme` selectors into production. Filled actions explicitly
pair `--primary` with `--on-primary`, or `--destructive` with
`--on-destructive`. Pale semantic fills use their matching readable text.

Rules:

- Define raw theme values once in the foundation. Component styles consume
  tokens. Preserve existing `color-mix()` recipes where they still express the
  approved appearance; measure the composited result, not just the base token.
- Every semantic tone has a strong value for text, markers, and borders and a
  `-soft` value for fills. Never put `-soft` text on a `-soft` fill.
- Task selection uses a neutral grey fill, normal text emphasis, and a quiet
  inset edge. Do not add a blue left rail, oversized tile, or blue glow.
- Blue and other saturated colors belong to small controls and indicators.
  Do not use navy, blue gradients, or tinted glass as the main workspace theme.
- `--cyan` is decorative. Never encode state with it.
- The earlier blue swatches (`#e3f2fd`, `#90caf9`, `#2196f3`, `#0d47a1`)
  were exploration references, not additional production tokens. In particular,
  white on `#2196f3` is only 3.12:1; use the approved action pair instead.
- Text contrast target is 4.5:1 for readable interface text. See section 8 for
  measured pairs and requirements for focus, icons, and essential boundaries.

### 4.2 Typography

Font stack: `"Avenir Next", Avenir, "Segoe UI", Arial, sans-serif`,
antialiased, no synthesis. Set `--font-sans` consistently in the foundation and
any retained Tailwind `@theme` definition; form controls inherit it. Do not
download fonts from a CDN or copy licensed system fonts into the repository.
Fallbacks are intentional: check Avenir Next on macOS/iOS and an available
fallback in other browsers. CJK and missing glyphs use platform fallback.
Monospace for commands, paths, and code: `ui-monospace, SFMono-Regular, Menlo,
Monaco, Consolas, monospace`.

Use typography by role. Conversation content uses `.8125rem` with a `1.7`
line height; the desktop composer uses `.8125rem` with a `1.6` line height.
Standard controls and their labels use `.875rem`;
initial request text uses `.875rem`, with normal font weight. At widths up to
760px, composer and initial-request textareas retain `1rem` editable text;
conversation prose remains `.8125rem`. Do not change the root font size.
Inherit the Avenir stack through native controls.

| Role | Size | Weight | Use |
|---|---|---|---|
| Overline | Existing `.58rem`, uppercase, `.09em` tracking | 730–780 | Compact section eyebrows |
| Metadata | Existing component-specific `.64rem` range | 450–650 | Timestamps, hints, short diagnostics |
| Sidebar label | `.8125rem` | 400 default; existing selected/action weight | Task titles, workflow links, Settings, New task |
| Sidebar section / filter | `.6875rem` uppercase section; `.75rem` filter | 750 section; 670 filter | Workflows/Tasks headings, Active/Archived |
| Sidebar supporting text | `.75rem` empty states; `.625rem` badges | Existing component weight | Empty task list, Coming soon |
| Control | `.875rem` | 400 input text; 620–700 actions/labels | General form fields and selects |
| Composer controls | `.8125rem` | Existing action/select weight | Reasoning/skill selects and Send/Update/Stop |
| Activity summary/control | `.75rem` | Existing heading/control weight | Agent phase, tool chips, milestone headings |
| Activity detail | `.75rem` | 400–450 | Expanded category detail, supplied summaries and decision receipts |
| Activity elapsed | `.625rem` | Existing metadata weight | Elapsed wall-clock time; retain existing smaller overline/count roles |
| Body small | `.8125rem`–`.875rem` | 400–450 | Panel descriptions and form consequences |
| Conversation body | `.8125rem`, line height `1.7` | 400 | User/assistant text and result tables |
| Desktop composer text | `.8125rem`, line height `1.6` | 400 | Chat textarea |
| Initial request | `.875rem`, line height `1.6` | 400 | New-task textarea |
| Phone input text | `1rem` at ≤ 760px | 400 | Composer and initial-request textareas |
| Conversation headings | `1.125rem` / `1rem` / `.875rem` | Existing heading weight | h1 / h2 / h3–h4 |
| Setup heading | `clamp(1.75rem, 3vw, 2.2rem)` | Existing heading weight | Instruction-first setup |

Keep sidebar row spacing compact and status metadata in its existing role. New
long-form content must use a reading role rather than a metadata size. Result
tables inherit conversation text size so that responses use one body scale.

Rules: sentence case except overlines; `tabular-nums` for times and counters;
wrap user- and model-supplied prose; preserve code formatting with local
overflow when needed. Use ellipsis only for chrome titles with an accessible
full name. Never clamp a response, error, decision consequence, or important
instruction. New summary previews may truncate only when the full content is
available through a clear disclosure. Font fallback may change wrapping;
verify it instead of shrinking text to force a fit.

### 4.3 Spacing

Preserve current spacing during migration. Most spacing is rem-based. For
new compositions, prefer this ladder: `.25 .5 .75 1 1.25 1.5 2 2.5
3rem`. Fine adjustments below `.25rem` are allowed inside a component but
never between components. Cards and panel sections use `.85rem` padding;
drawers and dialogs use `1.15rem` to `1.25rem`; conversation column padding
is `2.5rem 1.5rem` and drops to `1rem` inline at 480px.

### 4.4 Radius

| Radius | Use |
|---|---|
| `.35rem` to `.5rem` | Inline code, workspace-reference chip, row action buttons, composer selects |
| `.65rem` to `.7rem` | Inputs, buttons, icon buttons, nav rows, status cards |
| `.8rem` to `.9rem` | Cards, panel sections, live activity block, notices, markdown `pre` |
| `1rem` to `1.15rem` | Composer, dialog, task setup form, settings cards |
| `1.75rem` (`--radius-synvo-card`) | Connection card only |
| `999px` | Pills, chips, dots, orbs, the loading line |

### 4.5 Surfaces and elevation

Depth comes from opaque charcoal layers and quiet borders. Keep existing
shadow geometry and use the approved neutral recipes:

| Element | Shadow |
|---|---|
| Selected task row | `inset 0 0 0 1px var(--border-selected)`; external colored lift is transparent |
| Live activity | `0 18px 42px -34px rgb(0 0 0 / 55%)` |
| Composer | `0 18px 46px -32px rgb(0 0 0 / 48%)` |
| New task button | `0 5px 18px -14px rgb(0 0 0 / 55%)` |
| Selected task filter | `0 5px 14px -12px rgb(0 0 0 / 60%)` |
| Synvo mark | `0 10px 28px -14px rgb(0 0 0 / 48%)` |
| Setup brand assets | `0 14px 32px -20px rgb(0 0 0 / 65%)` |
| Selected access mode | `inset 0 0 0 1px rgb(105 175 255 / 12%)` |
| Task details panel | `-24px 0 60px -38px rgb(0 0 0 / 70%)` |
| Interaction drawer | `-24px 0 70px -34px rgb(0 0 0 / 80%)` |
| Dialog | `0 24px 70px -28px rgb(0 0 0 / 78%)` |
| Phone navigation | `18px 0 48px -36px rgb(0 0 0 / 85%)` |
| Connection primary action / hover | `0 14px 32px -16px rgb(0 0 0 / 55%)` / `0 18px 36px -16px rgb(0 0 0 / 60%)` |

These recipes live in foundation `--shadow-*` tokens. Backdrops use
`--backdrop` with the existing 3px or 4px blur. Do not add shadows to every
card. Connection, boot, error, and decision states use the same foundations;
the prototype's `scenario` fixtures support their visual checks. Large
gradients and translucent glass are not patterns for new workflow screens.

### 4.6 Motion

| Purpose | Duration | Easing |
|---|---|---|
| Color, opacity, background hover | 140–160ms | `ease` |
| Movement: reveal, slide, collapse, enter | 180–260ms | `cubic-bezier(.22, 1, .36, 1)` |
| Removal | 210ms | `cubic-bezier(.4, 0, 1, 1)` |
| Live response/cursor/actions appearance | 150ms | `ease-out` |
| Newly confirmed decision receipt | 180ms | `ease-out`, small vertical offset |
| Ambient "alive" loops | 1.05s typing, 1.8s activity pulse, 2s availability ping, 2.4s status orb | `ease-in-out` or `ease-out`, infinite |
| Progress | `.75s` spinner, `1.25s` loading line | `linear`, `ease-in-out` |

`prefers-reduced-motion: reduce` is honored globally in `connection.css`
(near-zero durations, single iteration) and explicitly in `codex.css` for the
activity state dot, chevrons, backdrop, and drawer. New animations add an
explicit reduced-motion override in the same stylesheet.

### 4.7 Iconography

- Icons are inline SVG from `src/workspace/visuals.tsx`: `viewBox="0 0 24 24"`,
  `fill="none"`, `stroke="currentColor"`, `stroke-width="1.8"`, round caps and
  joins, `aria-hidden="true"`. Add new icons there; do not add an icon font or
  library.
- Default size inside the shell is `1.15rem`. Row actions use `.82rem` to
  `.98rem`. Chevrons are `.85rem` to `.9rem` on a 20-unit viewBox.
- Select chevrons are app-owned inline SVG inside a `*-select-wrap` span with
  `appearance: none` on the select. URL-backed CSS background images do not
  render reliably inside the Lark WebView; never use them.
- Composer Reasoning/Skill selects use this same pattern: `.65rem` left text
  padding, `2rem` right reserved space, and a `.85rem` chevron inset `.65rem`
  from the right edge. Keep native select behavior, a non-interactive decorative
  SVG and visible disabled/focus states; use system `ButtonText` for the arrow
  in forced colors. The wrapper and select can shrink and
  the label row can wrap on phones.
- Raster assets: `assets/logo.jpg` (Synvo), `assets/codex.png`,
  `assets/user.png` (fallback avatar, also used on image error).
- Keep supplied asset colors, proportions, and placement, including the Codex
  mark's own colors. The ban on violet UI accents does not recolor vendor
  artwork. Do not add CSS filters or redraw an existing logo for a workflow.

### 4.8 Layout, sizes, and breakpoints

The desktop shell is a two-column CSS grid, `min-width: 320px`. Its height
tracks `visualViewport.height` at normal zoom through `--workspace-height`,
with `100dvh` fallback; `--workspace-offset` accounts for keyboard panning.
Pinch zoom retains browser behavior. These geometry variables are transient,
not theme tokens. Do not persist them or disable zoom.

| Region | Size |
|---|---|
| Sidebar expanded / collapsed | `15.75rem` / `4.5rem` |
| Topbar | `min-height: 3.75rem` |
| Conversation column and composer | `min(48rem, 100%)` |
| Task setup | `min(37rem, 100% - 2rem)`; compact laptop variant: `min(33rem, 100% - 2rem)` |
| Task details panel | 480px default, 384px to 640px, keyboard step 24px, reset on double-click |
| Artifact panel (legacy) | `minmax(18rem, 21rem)` |
| Interaction drawer | `min(31rem, 100%)`, full height, right-anchored, safe-area padding |
| Dialog | `min(27rem, 100%)`, centered |
| Composer textarea | Desktop: 32px to 120px, auto-grow; phone: 44px minimum, 120px maximum; see section 6.9 |

| Breakpoint | Behavior |
|---|---|
| Width > 760px and either height ≤ 900px or width ≤ 1200px | Compact new-task setup only: 33rem maximum width, 3rem logos, 1.75rem heading, 13px form text, tighter boxes/spacing. Larger desktop windows keep the existing scale. |
| ≤ 980px | Side panels overlay the content instead of splitting it; the resize handle is hidden |
| ≤ 820px | Connection page stacks to one column |
| ≤ 760px | Header navigation toggle and fixed drawer; no sidebar gutter when closed; navigation closes after selection. Task details is a full-screen sheet with visible Close. Access choices stack. |
| ≤ 520px | Connection page compact spacing |
| ≤ 480px | Compact topbar and composer, single-column access options and drawer actions, hidden activity summary text |
| `hover: none` | Hover-revealed row and turn actions are always visible |

Z-index layers: ambient art `-1`, desktop sidebar `3`, overlaid side panel
and phone navigation backdrop `4`, phone navigation `5`, phone details and
resize handle `6`, dialog backdrop `30`, interaction drawer backdrop `50`.
Phone sheets honor safe-area insets. Avoid introducing a competing layer.

Scrollbars use one themed primitive in `foundation.css`: 3px width/height at
normal text size, fully rounded ends, translucent neutral grey thumb and a
transparent track. Sidebar and chat share the same shape and theme tokens.
On a fine hover pointer, show the sidebar thumb only while the sidebar is
hovered or contains keyboard-visible focus. Do not change overflow or gutter
width to conceal it. The sidebar scroll viewport reaches the inner right
border using an end margin that offsets `--sidebar-inline-padding`; apply the
same .65rem as viewport end padding to keep the rows/filters about 10px away
from the thumb without shrinking them. Keep the fixed header/footer insets.
Touch/non-hover clients retain the visible affordance;
forced colors use system colors and automatic width. Add
`workspace-themed-scrollbar` where a stable gutter is needed.

In WebKit-scrollbar-capable clients, standard `scrollbar-width` and
`scrollbar-color` must resolve to `auto`, allowing the custom 3px pseudo-element
styles to work. Other browsers retain standard `thin`/semantic-color fallback;
their exact native width is user-agent controlled. Do not add a component-level
`scrollbar-width: thin` override: it disables the custom styling in current
Chromium. See [MDN's precedence note](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::-webkit-scrollbar).

## 5. Application shell anatomy

The supported shell is `src/codex/CodexWorkspace.tsx`. The non-Codex shell in
`src/workspace/Workspace.tsx` is legacy and is not a model for new work.

1. **Sidebar** (`workspace-sidebar`): brand row with collapse toggle; one
   task-creation entry (`workspace-new-button`, neutral bordered treatment);
   **Workflows** section
   (`codex-workflow-navigation`); **Tasks** section
   (`codex-task-navigation`) with the Active/Archived segmented filter and
   task rows; footer with Settings and the availability dot.
2. **Topbar** (`workspace-topbar`): folder icon or back button, a single-line
   title, an optional meta line (`codex-topbar-meta`, workspace and access
   mode), and one secondary button that toggles the side panel.
3. **Notices** under the topbar show operation reconnection and retained
   creation recovery. Native-channel diagnostics belong in Settings and do
   not determine authenticated H5 availability.
4. **Content** (`workspace-content`): the main view and, when open, the side
   panel as a second grid column (`data-codex-panel-open`).
5. **Overlays**: existing destructive confirmations and one-time decision
   drawer; phone navigation and details use modal focus containment and
   restoration through `useModalFocus`. The pending decision owns focus and
   makes the surrounding workspace inert.

Sections are ordered by importance, never by execution order. Workflows sit
above Tasks because they are the product's future entry points.

The Active/Archived filter fills the same available width as the task rows,
with no extra inline margin. Match their `.7rem` outer radius and `2.35rem`
desktop minimum height; use two equal segments with `.2rem` internal padding.
On phones, each segment remains at least 44px high. Align outer backgrounds,
not the inset selected segment, with neighboring rows.

## 6. Component catalog

Classes follow a block, `block__element`, `block--modifier` convention with a
module prefix: `workspace-` for the shell and shared chrome, `codex-` for the
Codex task client, `workflow-` for presentation cards, and a new prefix per
workflow module. Keep the existing Tailwind integration and named CSS classes;
the application does not use utility classes in its components. Avoid mixing
styling conventions or adding a second component library for a new workflow.
Visual state uses `data-*` attributes; ARIA communicates the corresponding
semantics. Existing variant classes can remain; do not rename them as part
of the theme migration.

### 6.1 Buttons

| Variant | Class or rule | Notes |
|---|---|---|
| Filled primary | `.button--primary`, `.codex-task-setup form > button`, enabled composer action, enabled `.codex-goal__save`, `[data-decision^="approve"]` | Solid `--primary`, `--on-primary` text. One dominant action per action group; no gradient. |
| Bordered secondary | `.workspace-secondary-button`, `.codex-task-panel button`, `.codex-interaction-actions button` | Default action style inside panels and drawers |
| Quiet / icon | `.workspace-icon-button` (2.25rem square), `.button--quiet` | Toggle, close, back |
| Danger | `.workspace-danger-button`, `.codex-danger-action`, `[data-decision="cancel"]` | Task delete entry stays bordered with `--negative` text; prototype's disconnect and Cancel task actions use `--destructive` with `--on-destructive`. Preserve each action's confirmation flow. |
| Row action | `.codex-task-row__action`, `.workspace-turn__actions button` | 1.65rem to 1.7rem, revealed on hover or focus-within, always visible on touch |
| Segmented | `.codex-task-filters button[aria-pressed]` | Two to three options, `aria-pressed` on the active one |

Keep existing button dimensions: typically `min-height` 2.2rem in panels,
2.75rem for decisions, and 2.85rem on the connection page. Check actual hit
areas against section 8 rather than treating these visual sizes as proof.

| State | Treatment |
|---|---|
| Enabled primary | Solid action pair from section 4.1; never pale accent blue with white text |
| Hover / pressed | Preserve existing transitions and neutral hover recipes. Keep filled primary text/background pair unchanged; use the existing border or shadow cue without introducing a bright fill. |
| Keyboard focus | Visible ring from section 8, independent of hover and selection |
| Disabled | Native `disabled`; retain component-specific opacity/cursor treatment. Composer action stays grey when disabled. Do not lower opacity on readable surrounding instructions. |
| Submitting | Prevent duplicate submission; preserve width where practical and change the verb, e.g. "Saving…" or "Creating task…"; no new spinner inside the shell |

Selected controls must not be indistinguishable from disabled ones. Blue
emphasis does not authorize an action: availability still comes from the
existing state and policy contracts.

### 6.2 Inputs

Standard editable controls use `--border-control`, `.65rem` radius,
`--surface` fill, `.875rem` text, and a solid 2px focus outline overlapping the
normal 1px border (`outline-offset: -1px`). This produces one visible edge
without a detached second border or focus glow. Text inputs
use normal weight. Labels sit above the field and remain visible. Request and
composer textareas use `.875rem` on desktop and `1rem` at widths up to 760px.
Prominent actions are at least 40px high on
desktop and 44px on phones. Selects retain the `*-select-wrap` pattern. Radio
choice cards (`.codex-task-setup__access-options label[data-selected]`)
present mutually exclusive modes with a title and a wrapping consequence.
Checkboxes and radios use `accent-color: var(--accent-strong)`. The selected
access-mode card uses `--surface-selected`, `--border-selected-control`, and
the checked radio; it does not get a large blue fill. Inputs retain visible
labels, required-field indications, and errors adjacent to the affected field.

### 6.3 Status chips and pills

`.status-chip--{neutral|working|positive|warning|negative}` (connection page),
`.codex-goal__status[data-state]`, `.codex-steering-history__status`, and
`.workflow-status-label` are the same idea: `999px` radius, `.52rem` to
`.65rem` uppercase or capitalized text, tone border and soft fill. A chip
always has a text label; a dot alone never carries meaning.

### 6.4 Notices

| Notice | Class | Tone |
|---|---|---|
| Channel health under the topbar | `.workspace-connection-notice` | warning |
| Reconnecting to activity | `.codex-reconnect-notice` | blue accent |
| Inline form or mutation error | `.codex-inline-error`, `.workspace-history-state--error` | negative, `role="alert"` |
| Expired decision | `.codex-interaction-expired` | negative, `role="alert"` |
| Privacy or boundary reminder | `.codex-interaction-notice`, `.codex-steering-history__privacy`, the supplied-analysis summary note | faint text, no fill |

Notices sit at the top of their region and wrap as needed. Combine redundant
notices instead of hiding errors or clipping important messages to one line.

### 6.5 Cards and sections

`.codex-panel-section` (side panel), `.workspace-settings__section`
(settings), `.workflow-card` (artifact panel): 1px `--border`, `.8rem` to
`1rem` radius, `--surface` fill, `.85rem` to `1.2rem` padding, an `h3` at
`.74rem` to `.9rem`, and `dl > div` key-value rows with faint `dt` and
right-aligned `dd`. Danger zones inside a card use `.codex-delete-confirm`
inline rather than a separate dialog when the target is already in view.

### 6.6 Status cards

`.codex-runtime-status[data-state]`, `.codex-operation-status[data-status]`,
`.codex-goal__state-card[data-state]`:
an indicator dot or icon, a bold one-line title, and a one-line description.
Tone follows section 7.1. These cards are `role="status"` with
`aria-live="polite"` when they change during a run.
Steering acknowledgements use `.codex-composer-feedback`, `role="status"`
for delivery and `role="alert"` for failure, with recoverable input retained.

### 6.7 Live activity block

`.codex-live-activity[data-state]` sits inside the owning assistant turn. Keep
one toggle header with the state dot, Agent activity overline, readable phase,
milestone count, and chevron. `activityPresentation.ts` projects the existing
conversation phase, operation, normalized events and bounded interaction. It
owns no subscription, persistence or agent lifecycle.

Phase priority: authoritative terminal outcome (including specific failure),
Action required, Stopping…, Reconnecting…, Writing response…, observed work,
Analyzing your request…, Starting…, then generic Working…. Work may alternate;
never present a fabricated checklist, percentage or ETA. A terminal event
wins over stale running, waiting or reconnect state. Keep the indicator subtle;
one current-state loop is enough. Use one concise phase announcement, outside
the growing answer. Elapsed time and event rows are not live regions.

The optional Elapsed counter shows whole seconds of wall-clock time including
waits. It requires a valid, nonfuture start on the owning operation. Hide it
before attachment or when metadata is invalid. Stop ticking at terminal; show
a final duration only from authoritative terminal metadata. A running record
whose status was locally projected from SSE does not supply a terminal time.
Clean up timers on operation switch and unmount.

The expanded body contains category chips and coalesced execution rows. It
opens while running, waiting or failed; completed/stopped work collapses into
a reopenable summary. Historical rows do not receive entrance animations.
Normalized-event counts and technical explanation belong in the existing
Task details diagnostics. Keep privacy framing next to supplied analysis
summaries and expanded technical details; private reasoning is never shown.

**Tool chips** (`.codex-tool-chip`) use native `details/summary`, a category
icon, label, observed count and explicit state. Categories are Workspace
commands, Connected tools and File operations. They wrap on phones and expand
by keyboard into bounded start/completion counts. These are category summaries,
not per-call records: the contract supplies no reliable invocation identities,
pairing, duration or individual outcomes. Activity finished does not mean the
result succeeded. Unmatched activity becomes Unconfirmed on completion or
Interrupted on stop/failure; keep those states neutral/amber. Unknown events
stay in diagnostics and never create guessed tools or filenames.

**Execution rows** (`.codex-live-activity__steps li[data-status]`) summarize
observed analysis, workspace work, file work, resolved decisions, steering and
the terminal outcome. Deduplicate by sequence within the owning operation.
Preserve every unfinished category when another becomes current. Labels and
icons communicate state alongside color. Rows are inside the conversation;
selected sidebar task rows retain the existing neutral styling.

**Decision receipts** (`.codex-decision-receipt`) appear only after the decision
API confirms an identity-matched interaction with status `DECIDED` and a
returned decision (the existing backend contract). Labels use the returned
choice: Approved once, Declined, Cancellation requested. Never infer acknowledgement
from a click, a disappearing dialog, or an SSE resolved event. Cancellation
requested does not mean stopped, and approval does not prove work resumed.
An acknowledgement suppresses stale Action required presentation only for its
matching interaction; a new pending interaction always keeps priority.
The drawer remains the sole decision control, with existing one-time choices,
validation, expiry, focus handling and submission lock.

Receipts retain only task/operation/interaction identity and confirmed decision
in component-session memory, with at most 20 per task. Clear on deletion or
unmount; no browser storage. No reason, command, path, form or MCP content is
copied. On reload, a resolved event supports only a generic decision milestone.
Late responses cannot dismiss a replacement dialog, move focus across tasks,
or announce another task's choice. Failed/ambiguous requests show recovery
without a receipt. Animate only a newly confirmed receipt (180ms); retained
receipts and replay do not animate as new. Preserve drawer backdrop/entrance
at 160ms/210ms, without delaying the API or focus for animation.

### 6.8 Conversation turn

`.workspace-turn[data-role][data-status]`: 2rem avatar, body column. User
turns are right-aligned bubbles on `--surface-muted` with a `.25rem` top-left
corner. Assistant turns render Markdown through `AssistantMarkdown` with the
allowlist in that file: no raw HTML, no images, only `http(s)` links open in
a new tab, workspace-relative references render as the non-clickable
`.assistant-workspace-reference` chip. Short line-delimited results render as
the `.assistant-compact-result` list. Turn actions (copy, fork, time) appear
on hover or focus-within. A failed turn shows negative text and a retry
button. The typing indicator is three blue dots when no richer activity presentation
is attached. A decorative cursor appears only on the owning actively streaming
answer. Hide it during tools, decisions, reconnect, stopping and terminal states,
and on completed replay. Keep it outside Markdown and clipboard text and mark
it `aria-hidden`. Use a 150ms appearance for a newly introduced live answer/cue
and live completion actions; never remount or fade the answer on every delta.
Reduced motion removes these animations. Append arbitrary fragments immediately,
preserve whitespace and content reset, and retain selection and scroll intent.
The conversation viewport is not an `aria-live` region: announce concise phase
changes without repeatedly reading the full growing answer. Tables render with semantic
`table/thead/tbody/tr/th/td` elements and `scope="col"` headers inside the
focusable, labeled `.assistant-table-scroll` region. Only that region scrolls
horizontally; prose and relative file references wrap. Table grammar uses
[micromark-extension-gfm-table](https://github.com/micromark/micromark-extension-gfm-table)
and [mdast-util-gfm-table](https://github.com/syntax-tree/mdast-util-gfm-table)
inside the existing Markdown renderer. No raw-HTML plugin, image support,
autolink/task-list extension, artifact access, or new UI framework is added.
Escaped pipes, code spans, alignment, and incomplete streamed tables must
retain parser/security regression coverage.

### 6.9 Composer

`.workspace-composer` stays at the bottom of the conversation, with an
auto-growing textarea, existing reasoning/skill controls, and visible actions.
Idle input sends **Send message**. An eligible running operation exposes
**Update instructions** and nearby **Stop**; reasoning/skill selectors are
unavailable during execution. Enter submits, Shift+Enter adds a newline, and
IME composition does not submit. The whole card receives the textarea focus
indicator using the same overlapping-edge treatment as other controls; the
inner textarea does not draw another outline. Reasoning/skill selectors and
actions retain their own indicators when focused.

Keep the idle composer compact: one textarea row, `.5rem .75rem` frame
padding, and `.375rem` control-row bottom padding/margin. At the default root
size the desktop textarea starts at 32px, grows with the draft to a 120px cap,
then scrolls internally; shortening or clearing a draft shrinks it again.
Desktop Send/Update/Stop buttons are at least 32px high. Phone textareas retain
16px text and a 44px minimum; phone actions remain at least 44px. Preserve
wrapping and rem text enlargement. Do not fix the entire composer height or
hide feedback, controls or multiline draft content to save space.

Task drafts remain in memory under their task identity. Steering drafts also
retain their operation identity. A terminal or selection race must never turn
an update into a new message. Show the retained draft and an explicit **Use as
new message** or **Use for current operation** action; this transfers input
without sending it. Delivery acknowledgement is separate from completion.
Keep acknowledgement/history attached to the owning task and operation.

While a decision is pending, the existing decision drawer owns interaction,
including **Cancel task**. The composer is not an alternative approval or
submission path behind it. Preserve reading scroll position, auto-growth,
request identities, replay, retry, and stop ownership in existing hooks.

### 6.10 Navigation rows

`.workspace-nav-item` is the base: full width, `2.25rem` min height, icon and
label, `data-active`, `aria-current="page"`, disabled at `.58` opacity with a
`small` suffix for "Coming soon" or "Planned". `.codex-task-row` wraps a nav
item with hover-revealed actions, inline rename (`.codex-task-rename`), and
inline delete confirmation (`.codex-task-delete-confirm`, `role="alertdialog"`).
In the collapsed sidebar rows shrink to a `2.35rem` icon tile with a `title`
tooltip.
The selected task uses the neutral row treatment in section 4.1. Retain the
current single-line label, weight emphasis, icon, and hover/focus actions.
Do not add a colored left rail or a second date/status line from an earlier
mockup. Workflow navigation uses the same row grammar and hierarchy.

### 6.11 Modal surfaces

- **Dialog** (`.workspace-dialog`, `role="alertdialog"`, `aria-modal`): icon,
  heading, description, optional error, two actions with the destructive one
  last and filled negative. Escape cancels unless busy. Focus starts on Cancel
  and is trapped.
- **Interaction drawer** (`.codex-interaction-drawer`, `role="dialog"`,
  `aria-modal`): "Action required" overline, heading, summary definition list
  (workspace, permission, expiry), reason paragraph, sensitive detail blocks
  (`.codex-sensitive-detail` with a monospace `code` capped at 16rem), optional
  form fields, actions rendered from the contract's decision list in order,
  submitting status, privacy notice. Focus starts on the first decision and is
  trapped; previous focus is restored on close.

### 6.12 Empty, loading, and error states

`.workspace-empty-state` (logo, display heading, one sentence),
`.workspace-history-state` (centered muted card for loading and inline errors),
`.centered-state` with `.loading-line` (app boot) or `.error-mark` (fatal
error with a retry button), `.codex-live-activity__empty` (waiting for the
first event). Every list has an explicit empty sentence
(`.workspace-sidebar__empty`, `.codex-panel-empty`).

The reload-time “Preparing Synvo AI Assistant…” card sits inside
`.codex-startup-state` with 1.5rem padding, leaving space below the topbar and
beside the content edges. Its card keeps its natural content height instead of
stretching to fill the workspace grid. Other history/loading cards are unchanged.

### 6.13 Disclosure

Native `details`/`summary` with a rotating inline chevron
(`.codex-technical-activity`, `.codex-steering-history__list`). Use it for
secondary detail; never for primary controls.

### 6.14 Task setup, details, and Settings

- Setup leads with **What would you like to work on?**, a required request,
  and a readiness banner. Form order is optional **Task title**, required
  **Your request**, configured workspace, access mode and **Start task**.
  **Read Only** remains the default; **Edit workspace files** is available only
  where enabled. Omit the old introductory sentence. Keep the 37rem maximum
  width and position the body near the top: 1rem top margin with 1.5rem internal
  padding on desktop; no top margin and 1.25rem padding on phones. Bound its
  height and allow scrolling on short screens. Both existing logos use 4rem.
  Creation is confirmed before
  the first message is sent to its loaded conversation. Disable duplicates.
  If creation cannot be confirmed, preserve input, refresh tasks, and offer
  explicit recovery. Never repeat creation automatically. If the first
  message fails, retry in the created task with existing failed-turn identity.
- Use the compact setup variant for laptop-sized windows as defined in the
  breakpoint table. At the default root size, logos reduce from 64px to 48px,
  heading from 35.2px to 28px, form text from 14px to 13px, and maximum width
  from 592px to 528px. Single-line fields are at least 36px, the request box
  at least 80px, and Start task remains at least 40px. Form padding is `.75rem`,
  field gaps `.625rem`, and access descriptions `.6875rem` with `1.45` line height.
  Setup uses `1.5rem` top margin and `.75rem` block padding, with maximum height
  `calc(100% - 1.5rem)` to reserve the top margin. Keep these values
  scoped to setup; do not scale the root or the whole H5 shell. Preserve rem
  enlargement, square logos and overflow scrolling. Phone inputs and touch
  targets keep their existing sizes. Full desktop sizes apply above both the
  1200px width and 900px height thresholds.
- The readiness banner includes the actual status model and the default
  reasoning effort used for the initial request. Display `gpt-5.6-sol` as
  **GPT-5.6 Sol**. Prefer advertised **High** effort; if unavailable, show the
  supported fallback and an explicit explanation. Missing model/effort data
  says unavailable and prevents creation until defaults can be confirmed.
  Defaults appear as plain text, without nested pills. Effort can be changed
  in the created task's composer; keep that override local to the task for the
  current H5 session. New tasks start from the default, and their first-message
  handoff captures it. Phase 3 keeps the model fixed to GPT-5.6 Sol.
- Task details leads with current activity, workspace/access, and a compact
  persistent goal. **Edit goal** reveals existing goal controls; **Task
  actions** groups title, mode, pin, archive, fork, and delete. Preserve review,
  normalized technical activity, inventory, and transient instruction history.
  Detailed global account/model/runtime information belongs in Settings; the
  setup banner shows the concise model/effort context needed before starting.
- Settings shows the authenticated H5 connection, Codex readiness, available
  account/usage, and configured workspace boundaries. Missing metadata says
  unavailable, never zero. Collapse runtime/model/native-channel diagnostics.
  Quotation remains a disabled placeholder; no active knowledge-source claim.

## 7. Interaction and state patterns

### 7.1 Tone mapping

| Application state | Tone | Where it comes from |
|---|---|---|
| Running, reconnecting | blue accent | operation `RUNNING`, run phases, SSE reconnect |
| Selected navigation / access option | neutral selected surface; blue checked radio for access options | current selection, `aria-current`, checked state; not an execution status |
| Waiting for a decision, attention, unsaved | warning | `WAITING_FOR_INTERACTION`, pending interaction, goal `blocked` / `usageLimited` / `budgetLimited`, dirty form |
| Completed, connected, sent, active goal | positive | terminal `COMPLETED`, `botConnection: connected`, steering `sent` |
| Failed, stopped, expired, destructive | negative | terminal `FAILED` family, `STOPPED`, expired interaction, delete |
| Idle, unknown, disabled | faint | no operation, `DISABLED`, coming soon |

Execution and permission state comes from the validated API contracts.
Presentation state such as selection, disclosure, dirty input, and submission
comes from the owning React state. Components map these explicit values to
`data-*`, ARIA, and tone; they never infer execution from labels or answer text.
Color accompanies a label or icon. A selected task is not necessarily running,
and blue never means completed.

### 7.2 Loading and recovery

- Load the durable, application-owned record first and render it. Load
  replaceable metadata (inventory, goal, status) with `Promise.allSettled` and
  degrade each to an empty presentation independently.
- Guard every asynchronous read and stream callback with the request
  generation that initiated it. A late response for a previous selection is
  dropped, not applied.
- A terminal stream event is authoritative over a stale refresh; project it
  onto task detail immediately and close the subscription.
- Reconnect by replaying into empty active state, or by snapshot plus cursor,
  never both. Preserve whitespace-only fragments.
- Show "Reconnecting…" as a blue notice, keep existing content visible, and
  poll a non-ready runtime status every 3 seconds until it is ready.

### 7.3 Mutations

- One mutation in flight at a time; the hook rejects a second with a safe
  message. Controls disable while submitting, and the submit button changes
  its verb.
- Success feedback is inline (`role="status"`), clears when a new operation
  starts, and never uses a toast.
- Idempotent retries replace the failed turn in place and carry the explicit
  failed-turn identifier.

### 7.4 Human decisions

- A decision surface is modal, shows only contract-provided detail, offers
  only the decisions the backend returned in the order returned, disables
  during submission, and fails closed when expired.
- Labels are fixed: **Approve once**, **Decline**, **Cancel task**. No other
  approval vocabulary exists.
- A consequential confirmation in a future workflow names the exact target
  (identifier, version, or hash) it authorizes and cannot authorize anything
  else.

### 7.5 Deep links and navigation

`?codexTask=` and `?codexInteraction=` open the owning task and decision on
load and are kept in sync with `history.replaceState`. A new workflow adds its
own explicit, documented query keys and never reuses these.

### 7.6 Time and numbers

Times use `Intl.DateTimeFormat('en-SG', { timeZone: 'Asia/Singapore',
hourCycle: 'h23' })`, `tabular-nums`, and a `title` with the full date.
Durations are humanized ("2 minutes", "1m 20s"). Counts are pluralized
explicitly.

### 7.7 Copy

- Sentence case, plain workplace language, second person. "Codex is working",
  "Task completed", "Needs your approval".
- Name the workspace and the access mode as **Read Only** or **Edit workspace files**.
- Say what happens next after every terminal state.
- Use task-oriented language in primary flows. Existing runtime diagnostics
  may retain model/version labels in their current locations during this
  visual migration. New workflow copy must not expose protocol internals;
  do not make users understand JSON-RPC, engine, or runner concepts to act.
- Never show or imply an absolute path, a credential, a command the contract
  did not expose, or an approval scope broader than one decision.
- Never claim an action ran, a file changed, or a decision is pending unless
  the contract state says so.

## 8. Accessibility requirements

The production target is WCAG 2.2 AA. A visually approved prototype is not an
accessibility certification. These requirements apply to new components and
are release checks for the theme migration.

### 8.1 Color and contrast

Use at least 4.5:1 for interface text, including placeholders, hints, and
errors. Measure actual foreground/background pairs with opacity and mixing
applied. This guide uses 4.5:1 for all interface text rather than depending on
the large-text exception. See [W3C text contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html).

Calculated solid-color pairs, rounded for reporting (2026-09-05):

| Foreground / background | Ratio |
|---|---|
| `--ink` / `--surface-strong` | 14.44:1 |
| `--ink-soft` / `--surface` | 7.63:1 |
| `--ink-faint` / `--surface-muted` | 5.45:1 |
| `--on-primary` / `--primary` | 4.70:1 |
| `--accent` / `--surface` | 6.79:1 |
| `--positive` / `--positive-soft` | 7.10:1 |
| `--warning` / `--warning-soft` | 7.58:1 |
| `--negative` / `--negative-soft` | 6.71:1 |
| `--on-destructive` / `--destructive` | 6.38:1 |

Essential icons, control boundaries, and state indicators need at least 3:1
against adjacent colors. Quiet card borders can remain subtle when they carry
no essential information. `--border-strong` against `--surface` is only 1.73:1:
do not use it as the sole cue identifying an editable input. Use an existing
higher-contrast token such as `--ink-faint` for that essential boundary.
Selected access-card outline contrast is 3.32:1 against its selected fill.
See [W3C non-text contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).

Use a solid 2px `--focus` indicator. For buttons, selects, text inputs and
textareas, use `outline-offset: -1px` to overlap their normal 1px border and
produce a single continuous edge. Do not layer a detached ring or another
glow over that edge. Keep the essential neutral border when unfocused.
In Light, filled primary and destructive buttons use a 3px offset so a visible
surface-colored gap separates the 2px blue outline from the filled action. This
is the sole filled-action exception; inputs retain their single continuous edge.
Links, standalone disclosures, native checkboxes/radios and focusable reading
regions retain a 3px offset. The resize handle retains its 2px inset geometry.

Each compound control has one focus owner: the composer frame for its textarea
and the tool-chip frame for its summary. Suppress the descendant's outline
only when its frame provides the visible replacement. Other descendants
such as composer selects/buttons keep their own focus indicators. Forced
colors maps `--focus` to system `Highlight` for both native and compound
controls. Never change layout dimensions to draw focus.

### 8.2 Interaction and reading

- Every interactive element has an accessible name. Text buttons use visible
  text; icon-only buttons use `aria-label` and a tooltip/title. A title alone
  is not the naming strategy. Labels must describe the actual action.
- `role="status"` with `aria-live="polite"` for progress; `role="alert"` for
  errors and expiry; `aria-busy` on submitting forms and dialogs.
- Preserve modal focus containment and restoration. Destructive dialogs start
  on Cancel. The existing interaction drawer follows its current focus and
  decision order; do not turn Escape or a backdrop click into a permission
  decision. Ordinary dismissible dialogs support Escape unless busy.
- Focused controls stay visible when a panel opens, a sticky composer is
  present, or content scrolls. Check [W3C focus visibility guidance](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html).
- Custom resize handles are `role="separator"` with `aria-orientation` and
  value attributes, keyboard operable with arrows, Home, and End.
- Hover-only affordances are also shown on `focus-within` and on `hover: none`
  devices.
- Pointer targets meet 24 × 24 CSS pixels or a documented WCAG exception;
  prefer 44px touch targets for new prominent actions where the composition
  permits. Check the actual hit area and spacing, not icon size. See
  [W3C target-size guidance](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html).
- Minimum viewport 320px. No horizontal page scroll. Safe-area insets on
  full-height overlays. Wide code or approved data tables may scroll inside
  their own labeled region while surrounding content reflows.
- Verify 200% text enlargement and reflow at a 320px CSS viewport; do not
  disable browser zoom or fix heights that clip translated or enlarged text.
- Support reduced motion and forced-colors mode. Keep native focus/selection
  cues available in forced colors; do not use `forced-color-adjust: none` to
  enforce branding across the interface.
- Test both resolved appearances, including changes while input, streaming or
  decisions are active. Keep the Dark baseline unchanged. There is no in-app
  theme preference; supported clients follow the source in section 14.5.

## 9. Adding a feature or a workflow

1. **Confirm the governing specification.** A new phase needs user approval
   before implementation; a Draft phase remains non-executable. Existing
   approved behavior can be maintained without inventing a new phase. A
   disabled "Coming soon" entry does not grant implementation scope.
2. **Use the smallest appropriate boundary.** Extend the existing component
   when the behavior belongs there. A separately approved workflow can use
   `src/<workflow>/` for screen components and focused state ownership,
   `src/api/<workflow>.ts` for its approved typed client and response
   validators, and `src/styles/<workflow>.css` imported by `styles/index.css`.
   Reuse the application lifecycle and shared shell; do not put workflow
   business rules inside the generic conversation or Codex task hooks.
   Before adding an interface or module boundary, follow the design checklist
   in `docs/PRINCIPLES.md` and the phase specification. No generic workflow
   engine, theme runtime, or speculative component framework is needed.
3. **Model state explicitly.** Enumerate the workflow's states from the API
   contract, map each to a tone in section 7.1, and render each with a
   `data-state` attribute. Design loading, empty, error, reconnecting, and
   terminal presentations before the happy path.
4. **Reuse the catalog.** Shell, topbar, side panel, notices, cards, status
   cards, chips, inputs, dialog, drawer, and empty states come from section 6.
   Add a new component only when no existing one can express the state, add
   it to this file, and give it the module prefix.
5. **Present human gates consistently.** Use an inline form or panel for
   ordinary review and correction; use a modal for a consequential decision
   when the approved workflow calls for one. A publish-style action gets its
   required preview and confirmation naming the exact version. The backend
   remains the authority for allowed actions; a disabled button is not policy.
6. **Keep sensitive detail transient.** Working detail loads on demand from
   owner-scoped no-store endpoints and is never persisted in the browser.
7. **Add the sidebar entry.** Under Workflows, a `workspace-nav-item` with an
   icon from `visuals.tsx`, `data-active`, `aria-current`, and a collapsed
   `title`. Keep Workflows above Tasks.
8. **Design acceptance checks before implementing a feature.** Cover
   meaningful behavior and failure states: permissions, request ownership,
   degraded auxiliary loads, replay, terminal authority, and duplicate
   submission. For an appearance-only change, use existing regression tests
   and visual/contrast checks; do not add tests that merely repeat CSS values.
9. **Complete the release checks in section 11.** Update this guide when a
   reusable visual rule changes and record phase verification in its
   authoritative specification. Keep temporary task notes in `tasks/`.

For `wf-keystone-quotation`, reuse the current navigation, content area,
secondary panel, form, status, and decision patterns as its approved phases
require. Long descriptions and numerical results must remain readable;
amounts use tabular numbers and explicit units/currency, errors identify their
field or row, and previews show the exact document/version to be confirmed.
This is presentation guidance, not a definition of quotation states, APIs,
publication permissions, or data tables. Those come from its specifications.
Other workflows use the same palette and semantic statuses; do not give each
workflow its own color theme.

## 10. Do not

- Add Tailwind utility classes, a component library, an icon font, or a
  design-token runtime.
- Add raw component colors instead of foundation tokens, or normalize existing
  spacing/type/radii during a theme-only migration.
- Infer execution or permission state from text; use explicit state and ARIA.
- Use violet as the UI accent, white text on pale brand blue, blue-filled
  workspace backgrounds, large decorative gradients, or blue-striped task tiles.
- Change sidebar order, component geometry, approval vocabulary, or data
  contracts merely to apply the theme.
- Use CSS background images for icons or chevrons.
- Show a spinner where a verb change or the loading line will do.
- Introduce toasts, snackbars, or auto-dismissing messages.
- Persist task, activity, interaction, goal, or detail data in browser storage.
- Render a decision control, scope, or label that the contract did not
  provide.
- Reuse the legacy `Workspace.tsx` shell, the Phase 2 confirmation card, or the
  planned Enterprise Research and Meeting entries as executable patterns.

## 11. Production migration and acceptance

### 11.1 Implementation sequence

The initial theme migration used steps 1–5 below without changing geometry.
The approved revamp then applied its bounded behavior changes in step 6.
Future work starts from the implemented system and follows its own approved
specification; it does not repeat the migration or revert the current UX.

1. Capture the current supported screens and the approved prototype at the
   same viewport and with the same sample content. Note existing defects
   separately. Preserve structure and geometry while applying this guide.
2. Implement the foundation tokens and Avenir stack from section 4. Set the
   root text/background and the resolved `color-scheme`; remove conflicting old
   palette declarations, body gradients, and stale font definitions. Keep
   the existing Tailwind integration and CSS import structure.
3. Map existing consumers through the compatibility aliases. Replace the
   prototype's hardcoded surface overrides with the semantic surface tokens
   in the owning stylesheet. Explicitly migrate primary action pairs,
   neutral selection, scrollbar colors, and shadow colors. Do not just append
   the prototype stylesheet after production CSS.
4. Audit `:hover`, `:active`, `:focus-visible`, checked, selected, disabled,
   submitting, and semantic state selectors. Later or more-specific rules
   must not restore old colors or put white text on a light-blue fill.
   Make the focus and essential-boundary corrections specified in section 8.
5. Apply the foundations to supported connection, boot, error, settings,
   modal, and interaction surfaces. The prototype did not exercise every
   one of these states; verify them with safe fixtures and the actual app.
   Keep their present layout, copy, permission decisions, and lifecycle.
6. Implement the approved composition changes through the existing owners:
   composer steering and Task details/Settings first, then task setup, phone
   overlays, typography, and result tables. Preserve request identities,
   permission contracts, owner-scoped drafts, and explicit recovery.
7. Run the checks below. Record screenshots, contrast results, browser and
   viewport details, and any unverified live behavior. A production rollout
   requires the project's normal verification; prototype approval alone
   does not complete it.

### 11.2 Required coverage

| Area | Required states / checks |
|---|---|
| App entry | Loading, connection/authorization, disconnected, fatal error and recovery |
| Shell and navigation | Expanded/collapsed sidebar, active/archived task list, empty list, selected row, hover/focus actions, inline rename/archive, unavailable workflow |
| Task setup | Workspace selection, both access choices, disabled/submitting creation, validation and runtime errors |
| Conversation | Empty, user and assistant turns, long Markdown, links/relative references, streaming, stopped, failed/retry, replay/reconnect, copy and fork feedback |
| Composer | Empty/disabled/enabled, focus, long input and auto-growth, reasoning/skill controls, sending, stop; mobile keyboard and scroll behavior |
| Task details | Open/closed, resizing by pointer and keyboard, loading/auxiliary failures, management actions, goal states, review states, activity and disclosure |
| Decisions and dialogs | Bounded file and MCP decisions, form fields, incomplete required values, submitting, error, expiry, destructive confirmation, focus containment/return |
| Semantics | Neutral, running, waiting, success, failure and disabled; color plus label/icon; no invented pending decision |
| Responsive and typography | 320, 390, 760, 980, 1200, and 1440px widths, including both sides of relevant breakpoints; Avenir and fallback fonts; long titles and mixed-language content |
| Accessibility | Contrast in computed states, keyboard-only operation, screen-reader names/status announcements, target sizes, text enlargement, reflow, reduced motion, forced colors |
| Host environment | Browser with OS light and dark preferences; actual supported desktop/mobile Lark WebViews, including viewport/safe-area and select rendering |

Use synthetic/redacted content for screenshots and fixtures. An ordinary
feature needs coverage for its changed and affected areas; the initial global
theme migration needs the full surface matrix. Do not make deferred native
Lark Chat or software-development workflows into new acceptance requirements.

Run from `frontend/` after implementing application changes:

```sh
npm ci
npm test
npm run typecheck
npm run lint
npm run build
```

Use the current lockfile and existing dependencies. Run relevant stack/live
checks from `AGENTS.md` for the rollout being claimed. Automated tests do not
prove visual quality or an authenticated Lark flow. A documentation-only
revision requires document/token/reference checks rather than a new stack run.

### 11.3 Definition of done

- The supported app uses the approved paired palettes and shared font stack;
  each supported client follows its verified appearance source. Theme changes
  preserve application state. Record client/version limits in the phase audit.
- Desktop shell geometry and the approved bounded UX compositions match
  this guide and the governing revamp specification. Font-metric differences
  are reviewed; no controls or important content clip.
- All visible color states use the intended semantic roles, with no stale
  violet accents, tinted canvas, or unreadable filled-button labels.
- Accessibility checks pass, including essential input boundaries and focus.
  The inherited prototype appearance is not an exemption from those checks.
- Existing lifecycle, security, one-time decisions, and navigation behavior
  remain intact; all relevant automated and live verification is recorded.
- Production imports no prototype fixtures/assets bundle and adds no new
  dependency solely for theming. This guide matches the delivered tokens.

### 11.4 Release boundaries

The charcoal theme, semantic controls, readable forms, current-product
Settings, and phone sheets are implemented. Legacy workspace/presentation
components remain compatibility code, not a source of new requirements.

The implementation audit in the [revamp specification](../../docs/specs/frontend-ui-ux-revamp.md)
is authoritative for release evidence and remaining gates. Passing unit tests
or approving a visual prototype never substitutes for required live H5 and
actual mobile-keyboard checks. Record a permitted explicit waiver before
claiming an unavailable check is waived.

## 12. File map

| Concern | Location |
|---|---|
| Document appearance and early paint | `src/appearance.ts`, `index.html`, `src/main.tsx` |
| Tokens, scrollbar, focus, brand mark | `src/styles/foundation.css` |
| Shell, sidebar, topbar, rows | `src/styles/workspace.css` |
| Settings, dialog, responsive shell rules | `src/styles/workspace-controls.css` |
| Conversation, markdown, composer | `src/styles/conversation.css` |
| Connection page and reduced motion | `src/styles/connection.css` |
| Codex task client, live activity, panel, drawer | `src/styles/codex.css` |
| Artifact panel and workflow cards | `src/styles/workflows.css` |
| Icons, logos, avatars | `src/workspace/visuals.tsx`, `assets/` |
| API contracts and validators | `src/api/*.ts` |
| State owners | `src/conversation/useConversation.ts`, `src/codex/useCodexWorkspace.ts` |
| Supported shell | `src/codex/CodexWorkspace.tsx` |
| Current rendered reference / original baseline | `design/prototype/index.html`, `design/prototype/theme-baseline.html` |
| Modal focus and breakpoint ownership | `src/workspace/useModalFocus.ts`, `src/workspace/useMediaQuery.ts` |
| Prototype-only local data and build | `design/prototype/src/`, `design/prototype/build.mjs`, `design/prototype/README.md` |

## 13. Design-system readiness record

Updated through 2026-09-06 to match the approved UI/UX revamp, agent interaction
clarity, and user-requested chat/sidebar sizing, filter alignment and continuous
focus-edge corrections, followed by setup placement, field order and visible
GPT-5.6 Sol / High defaults, a compact setup scale for laptop windows, and
the smaller chat composer and 13px conversation typography, 12px activity
text, thinner 2px focus edges and inset composer dropdown chevrons.
Version 1.9 adds paired Light/Dark tokens and automatic appearance resolution.
Version 1.10 adopts the user-requested 3px translucent scrollbars and sidebar
hover/keyboard reveal, shared with chat and preserved under both appearances.
Version 1.11 places the sidebar scrollbar at the right border with a separate
content gap, retaining row/filter widths and the fixed header/footer insets.
Production owns both palettes, the font stack, component states, and
responsive behavior. The self-contained prototype renders these same
components using synthetic in-memory APIs. The original theme-only reference
is frozen in `prototype/theme-baseline.html`; it is historical evidence of
checkpoint 1's geometry, not the current behavior contract.

This guide specifies future frontend work. Release acceptance and exact
verification results belong in the [phase Completion Audit](../../docs/specs/frontend-ui-ux-revamp.md#completion-audit).
Do not interpret documentation readiness as completion of a pending live gate.

## 14. Light and Dark appearance

Status: **Approved** · Adopted in v1.9: 2026-09-06

This section defines the implemented visual extension. The
[Light/Dark appearance specification](../../docs/specs/frontend-light-dark-appearance.md)
owns scope, source verification, implementation order, acceptance, and release.
Implementation was approved on 2026-09-06. Approval does not substitute for
required live verification on each supported client.

### 14.1 Direction and preserved design

Light uses an off-white shell, white content surfaces, light grey controls,
charcoal text, and restrained Synvo blue actions. The user's light-mode
reference from [Beautiful UI](https://www.beautifului.dev/) informs surface
hierarchy and restraint. The values below are Synvo design decisions, not colors
claimed to have been extracted from that site. Keep secondary text more
readable than the faint grey in the reference image.

Dark retains the exact section 4.1 palette and section 4.5 shadow recipes.
This extension changes appearance, not composition: keep the Avenir stack,
current desktop/sidebar/panel arrangement, neutral task selection, full-width
Active/Archived control, and the approved responsive rules. Preserve 13px
chat text, 12px activity text, the compact composer, laptop task-setup scale,
16px phone editable text, and 44px prominent phone controls. Do not introduce
new font sizes, logo sizes, spacing, radii, or a root-font scaling rule.

Both appearances share the same components, content, state labels, motion,
and permission behavior. New approved workflows must support both through
semantic tokens; a workflow must not define its own palette or theme state.

### 14.2 Light color tokens

Use the existing token names. Section 4.1 supplies each token's Dark value
and meaning; this table supplies its Light value. Define the values centrally
in `src/styles/foundation.css`. The initial
Dark fallback and an explicit resolved appearance must not depend on stylesheet
import order. Do not create separate Light copies of component stylesheets.

| Token | Approved Light value | Application |
|---|---|---|
| `--page-bg` | `#f8f9fb` | Document and outer shell |
| `--sidebar-bg` | `#f8f9fb` | Quiet sidebar |
| `--surface-strong` | `#ffffff` | Main canvas, topbar, dialog and interaction drawer |
| `--surface-panel` | `#f7f8fa` | Task details panel |
| `--surface` | `#ffffff` | Cards, composer, setup form and New task control |
| `--surface-muted` | `#f0f1f3` | User bubble, quiet controls and hover fill |
| `--surface-selected` | `#e8ebef` | Selected task and access card |
| `--accent-soft` | `#edf0f4` | Neutral fill for existing accent recipes |
| `--ink` | `#202124` | Primary text |
| `--ink-soft` | `#4b5563` | Secondary text and labels |
| `--ink-faint` | `#596273` | Readable hints, placeholders and metadata |
| `--border` | `#e0e3e8` | Decorative dividers and card edges |
| `--border-strong` | `#c5cad3` | Emphasized decorative edges |
| `--border-control` | `#7b8493` | Essential input and select boundaries |
| `--border-selected` | `#d6dbe3` | Neutral selected-row inset edge |
| `--border-selected-control` | `#58779a` | Selected access-mode boundary |
| `--accent` | `#145fb8` | Links, activity and meaningful small icons |
| `--accent-strong` | `#0d47a1` | Emphasized links and checked controls |
| `--primary` | `#1672db` | Primary action fill; same brand action as Dark |
| `--on-primary` | `#ffffff` | Primary-action text and icons |
| `--cyan` | `#03adf0` | Supplied Synvo branding only |
| `--positive` | `#167342` | Success text and indicators |
| `--positive-soft` | `#eaf6ee` | Success fill |
| `--warning` | `#885500` | Attention text and indicators |
| `--warning-soft` | `#fff4df` | Attention fill |
| `--negative` | `#b42336` | Error, stopped and expired text/indicators |
| `--negative-soft` | `#fff0f1` | Error fill |
| `--destructive` | `#ad3048` | Filled destructive action |
| `--on-destructive` | `#ffffff` | Destructive-action text and icons |
| `--focus` | `#1672db` | Continuous keyboard focus edge |
| `--scrollbar-track` | `transparent` | Unchanged track behavior |
| `--scrollbar-thumb` | `rgb(89 98 115 / 38%)` | Translucent neutral scroll thumb |
| `--scrollbar-thumb-hover` | `rgb(89 98 115 / 58%)` | Hovered scroll thumb |
| `--backdrop` | `rgb(23 32 43 / 24%)` | Overlay scrim; retain existing blur geometry |

Retained Tailwind foundation aliases must resolve the same semantic values:
`--color-synvo-accent` to `--accent`, and `--color-synvo-cyan` to `--cyan`.
Verify the generated CSS and computed consumers; do not assume a build-time
alias updates at runtime. Typography, radius, animation and scrollbar-size
tokens are theme-independent. Add no Tailwind utility classes for this phase.

### 14.3 Light elevation

Use borders and surface separation before shadows. Keep current offsets,
blur, spread and radius; reduce shadow opacity for Light. No blue glows.
Dark keeps the existing values, including the retained connection-card token.

| Token | Approved Light value |
|---|---|
| `--shadow-composer` | `0 18px 46px -32px rgb(0 0 0 / 16%)` |
| `--shadow-activity` | `0 18px 42px -34px rgb(0 0 0 / 16%)` |
| `--shadow-new-task` | `0 5px 18px -14px rgb(0 0 0 / 16%)` |
| `--shadow-selected-filter` | `0 5px 14px -12px rgb(0 0 0 / 18%)` |
| `--shadow-brand` | `0 10px 28px -14px rgb(0 0 0 / 16%)` |
| `--shadow-setup-brand` | `0 14px 32px -20px rgb(0 0 0 / 20%)` |
| `--shadow-selected-control` | `inset 0 0 0 1px rgb(20 95 184 / 10%)` |
| `--shadow-panel` | `-24px 0 60px -38px rgb(0 0 0 / 22%)` |
| `--shadow-drawer` | `-24px 0 70px -34px rgb(0 0 0 / 26%)` |
| `--shadow-dialog` | `0 24px 70px -28px rgb(0 0 0 / 24%)` |
| `--shadow-navigation` | `18px 0 48px -36px rgb(0 0 0 / 26%)` |
| `--shadow-primary` | `0 14px 32px -16px rgb(0 0 0 / 18%)` |
| `--shadow-primary-hover` | `0 18px 36px -16px rgb(0 0 0 / 22%)` |
| `--shadow-synvo-card` | `0 30px 80px -38px rgb(0 0 0 / 16%)` |

The selected task row continues to use the neutral `--border-selected` inset
edge. The selected access card keeps its distinct `--border-selected-control`
boundary; its shadow is decorative and cannot substitute for that boundary.

### 14.4 Component and state parity

| Surface or pattern | Light treatment and invariant |
|---|---|
| Entry, loading and recovery | Apply the resolved palette before authenticated workspace content, including loading line, connection card and fatal-error recovery |
| Sidebar and task rows | Off-white shell, grey selection, dark text; preserve row/pill dimensions, counts, icons and selection semantics; no blue left rail |
| Task setup | Same logo pair, heading and field order; white form and grey readiness strip; readiness still comes from runtime data |
| Chat and results | Dark prose on white canvas, grey user bubbles; tables, inline code, code blocks, links and inert file references consume semantic tokens |
| Composer and dropdowns | White frame, quiet grey controls, readable placeholder and inset chevrons; preserve compact auto-growth and touch behavior |
| Thinking and streaming | Same honest activity summary and streaming cue; existing durations and reduced-motion rules; no simulated progress or private reasoning |
| Tool chips and execution rows | Neutral containers with matching semantic text/marker colors; preserve expansion, grouping and contract-derived state |
| Decisions and receipts | White drawer/dialog and Light scrim; pending, submitting, acknowledged, failed and expired states retain explicit labels and the existing decision ownership |
| Settings, details and overlays | White/grey hierarchy and modest shadows; preserve disclosure, focus trapping/return, resizing and phone sheets |
| Disabled, hover, focus and selected controls | Check each actual composited pair; decorative border colors are not essential input boundaries; text remains readable |

Focus stays one continuous **2px** edge. Preserve the existing overlap with
the 1px border and the compound composer's single focus owner; no nested
outlines or thick blue halos. Keep the current dropdown chevron inset and
text reservation. On blue or destructive filled buttons, a same-color focus
edge needs a contrasting separation or another compliant focus treatment;
verify the actual focused state rather than relying on the base token pair.

Keep the original Synvo cyan artwork and Codex artwork. The Synvo asset's
black tile may remain black in Light; do not invert, recolor or filter logos,
avatars, or user content to manufacture a theme. Any new asset variant needs
an explicit visual reason. Semantic interface SVGs inherit token colors.

### 14.5 Automatic appearance behavior

The default is **Follow Lark** inside a compatible Lark H5 WebView and follow
the browser/OS preference in a plain browser. The existing H5 authorization
adapter remains unchanged. Official H5 documentation excludes `getSystemInfo`
theme output and `onThemeChange`/`offThemeChange` web-app support; do not borrow
gadget or Base-extension APIs. The phase specification links the sources.

`src/appearance.ts` owns the standard `prefers-color-scheme` source. Live
verification on macOS Lark 7.75.20 showed Light with macOS still Dark and a
Dark change event in the same H5 document. This establishes that client's
host-following source; it does not establish mobile or Windows compatibility.
See the phase audit for exact remaining cases and release gates.

1. Read both `light` and `dark` queries. Exactly one match gives a valid value.
   No match, contradictory matches or an exception retain the last valid
   in-memory value; use Dark if no valid value is known.
2. Read before React mounts; the small HTML bootstrap selects the initial
   palette before stylesheet paint. The root owner subscribes once to both
   queries, supports legacy media listeners, and removes its listeners on
   disposal/replacement. No component subscribes for appearance.
3. Every notification reads the current source rather than trusting an old
   event payload. Re-read on `pageshow` and `visibilitychange` for resume.
   There is no asynchronous bridge-read race or delayed SDK dependency.
4. Native `color-scheme`, semantic CSS and `theme-color` follow the same root.
   Failure never blocks startup or authentication. An unavailable/non-matching
   host source is a compatibility limit, not proof of Lark synchronization.

Apply changes without navigation, reload, component remount or restarting
authentication/SSE. Preserve unsent input, focus, selection, scroll position,
expanded activity, pending decisions and submission state. Recheck on resume
when the verified source requires it; clean up listeners and prevent stale
initial reads from overwriting newer change events. Avoid perpetual polling.

Match CSS `color-scheme`, native form controls and the document `theme-color`
metadata to the resolved appearance. Apply known appearance as early as the
current CSP allows; do not delay authorization or show a blank app while
waiting for theme metadata. Verify cold-load appearance and changes in Lark. Do not promise a flash-free native WebView background that
the page cannot control. Do not add global color crossfades or restart activity
animations on a theme change; preserve reduced-motion and forced-colors rules.
The root owner finishes pending color/shadow CSS transitions on a palette
change so old hover fades cannot mix the two themes; movement and activity
animations continue.

No new in-app theme preference, storage, backend field, permission scope or
production dependency is introduced. A Light/Dark selector in the local
prototype is a preview control only and does not demonstrate Lark integration.

### 14.6 Contrast and verification

Approved opaque pairs calculated on 2026-09-06 with WCAG relative luminance:

| Pair | Contrast |
|---|---|
| Primary text / darkest neutral fill (`#e8ebef`) | 13.46:1 |
| Secondary text / `#e8ebef` | 6.32:1 |
| Hint text / `#e8ebef` | 5.14:1 |
| Link blue / `#e8ebef` | 5.23:1 |
| Input boundary / `#e8ebef` | 3.16:1 |
| Selected access boundary / `#e8ebef` | 3.88:1 |
| Focus blue / `#e8ebef` | 3.93:1 |
| Success text / success fill | 5.31:1 |
| Attention text / attention fill | 5.76:1 |
| Error text / error fill | 5.87:1 |
| White / primary action | 4.70:1 |
| White / destructive action | 6.38:1 |

Use the section 8 thresholds: 4.5:1 for readable interface text and 3:1 for
essential boundaries, meaningful icons and focus against adjacent colors.
See [text contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)
and [non-text contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).
Quiet dividers are decorative and intentionally do not meet control-boundary
contrast. Cyan remains decorative. Do not use `--primary` as small Light link
text on grey surfaces; `--accent` is the readable text role.

These calculations validate base pairs only. They do not certify
existing `color-mix()`, opacity, hover, focus, disabled, shadow or backdrop
recipes. Measure computed and composited states in both themes during
implementation; fix failing recipes at their semantic owner. Keep the
forced-colors override effective after all theme selectors.

Run the full section 11.2 surface matrix in both appearances, including the
six agent interaction patterns and real desktop/mobile Lark checks. Future
approved features must add their affected states to that same matrix rather
than relying on one happy-path screenshot.

### 14.7 Adoption and current readiness

Both appearances and the source owner are implemented. Sections 3, 4, 8,
11 and 12 incorporate their rules. The standalone prototype uses the same
production components and supports preview-only `appearance=light` or
`appearance=dark` query values; without an override it follows the environment.
Historical Dark sizing and phase audits remain intact.

The [appearance Completion Audit](../../docs/specs/frontend-light-dark-appearance.md#completion-audit)
records automated checks, rendered checks, actual desktop evidence and the
remaining mobile/host matrix. Neither this guide's approval nor desktop browser
emulation completes an actual phone check or waives existing release gates.

## 15. Billing Insights workflow

The simplified Phase 1 experience/design was accepted by the user on 2026-09-07.
Phase 1 is Complete. The user explicitly deferred native high-contrast and
reduced-motion checks to mandatory Phase 3 pre-release gates; neither is waived
for production or claimed to have passed.
The [Billing Insights specification](../../docs/specs/wf-billing-insights/phase-1-experience-and-design.md)
and [experience design](../../docs/specs/wf-billing-insights/experience-design.md)
govern its original isolated synthetic prototype. The approved
[Phase 3 specification](../../docs/specs/wf-billing-insights/phase-3-h5-reports-and-investigation.md)
now governs the implemented local Billing window and report/questions flow.
Its Completion Audit records the remaining native-client and live-data gates;
implementation and synthetic previews are not production acceptance. This does
not reorder the Keystone roadmap.

Billing uses the same shell, icons, semantic palette and appearance source.
The approved [daily spending enhancement](../../docs/specs/wf-billing-insights/phase-3-daily-spending-grid.md)
places the daily calendar directly below the main title (no subtitle), then a
compact Saved analyses toolbar before the report,
with contextual questions underneath. Only “+ New Analysis” opens the period
selector (1/3/6 completed months or a custom contiguous range), consent and
Generate form, including on an empty first visit. Remove the old Billing period
pill and persistent “Analysis complete” banner; announce “Report ready” accessibly.
Never suppress failure or factual-only notices with the success copy.
Keep the last saved calendar interactive during New Analysis setup and generation.
Back restores the prior saved report/question draft, or the first-use landing view,
without generating or cancelling work. Draft ranges never retarget saved evidence.
Show scope/currency/basis/freshness, total/change, monthly trend when relevant,
service breakdown and evidence-qualified optimization next steps. Source/version
details and invoice reconciliation use a disclosure. Missing evidence is not
zero cost or validated savings. A draft period does not retarget the active report.

The UI change metric uses magnitude plus direction: “USD 216.82 less” /
“44.42% lower” in `--positive` for cost decreases; “more” / “higher” in
`--warning` for increases. Direction comes from the saved exact delta, not
rounded totals. Zero change and unavailable comparisons remain neutral; never
invent a percentage for zero/negative comparison cost. Preserve sub-cent
qualifiers and use “< 0.01%” when a nonzero change rounds to zero percent.
Green signifies lower cost, not verified recurring savings. This card wording
does not alter saved facts, evidence, narratives or PDF presentation.

“What your team should know” uses small blue disc markers (`--accent-strong`)
for each takeaway. Keep native `ul`/`li` semantics, normal paragraph text color
and outside markers so wrapped lines align with the text, not the bullet.
Restore list styling explicitly because the global CSS reset removes markers.

Follow-up questions place progress, Stop and agent activity after the submitted
question and before the composer, not above the report. Render the accepted
question while saved history catches up; deduplicate by work ID. Report-generation
activity remains above the report. Terminal question status stays in its own card.

Use existing navigation, focus and notice patterns; billing-specific CSS uses
`billing-*` names. Use the same single reading flow at narrow widths, with
the shared mobile navigation drawer. Design-only appearance/scenario controls must
never enter production. No new global tokens, palette or component framework
is introduced. The [design review](../../docs/specs/wf-billing-insights/design-review.md)
records verification scope and outstanding acceptance gates.

### Saved analyses and daily calendar

- Use the billing-local `BillingSelect` for both Year and Saved analyses: a
  select-only combobox with a themed, rounded listbox portaled above clipping
  containers. Keep one blue inset focus edge, selected checkmarks, muted chevrons
  and document icons before saved-report labels. Use shared surface/ink/accent
  tokens in both themes. Enabled saved-report rows gain an 18% accent/surface
  background mix on pointer hover, clearing on exit without changing selection;
  forced colors uses Highlight/HighlightText. Year stays 76×32px on desktop (44px touch target).
  Preserve Arrow/Home/End/typeahead navigation, Enter/Space selection and
  Escape/Tab/outside dismissal; navigation alone never changes the selected data.
  Constrain popup width/height to the viewport and scroll longer histories.
  Keep period-first labels with separate muted generation metadata
  and adjacent blue New Analysis action. Distinguish repeated period/time labels
  with IDs only when necessary; preserve pagination and factual-only states.
  Inset noninteractive chevrons from the edge, with reserved flex space so long
  labels cannot overlap them. No new UI dependency or global select replacement.
- Render a UTC January–December year with 365/366 dates, weekly columns and
  seven weekday rows. Source coverage is snapshot-bound, not an implied full
  year of finalized costs. Year switching never fetches Azure or invokes a model.
- Java owns exact daily costs, source-role reconciliation, service grouping and
  up to five distribution-based numeric bands (nearest-rank quintiles, readable
  upward thresholds, merge ties). Label relative spending, retain dollar ranges
  and never split equal costs. Use shared accent/surface tokens for increasing
  blue intensity in both themes. Missing data is hatched, recorded zero marked 0,
  negatives signed, future dates marked with a dot; future source records remain explicit.
- User-approved borderless refinement (2026-09-08): no calendar frame, cell border
  or persistent selected/period outline in ordinary themes. Use a compact uppercase
  heading/year and a blue Less/More ramp, retaining exact numeric legends below.
  Pointer hover adds a slight light overlay only while over a cell. Hover preview
  is independent of click/tap selection and clears on pointer exit. Keep keyboard
  `:focus-visible` outlines; forced-colors may restore system borders for visibility.
- Keep selected-period attribution in date labels and preserve the report's
  source caveats. The user removed the calendar's coverage/range/provenance
  paragraphs and daily source/table disclosure. Do not equate source-date annual
  totals with billing-received-period report headlines or call them finalized.
- Hover, focus and tap share a persistent date-detail panel. Roving keyboard
  focus avoids hundreds of Tab stops. Compact annual cells are about 10–12px;
  the user explicitly removed the table and date selector, so do not claim the
  prior equivalent-control target-size exception. Actual phone/minimum-target
  acceptance remains open. Keep its plain bold date and cost/status
  on one row at normal widths, wrapping only as needed on narrow screens.
- At narrow widths stack toolbar controls and contain horizontal scrolling
  inside the calendar. Other content reflows at 320px/200% text. Provide system
  colors/patterns in forced-colors mode and no calendar animation. Actual native
  forced-colors/reduced-motion and phone Lark acceptance remain release gates.
- Bind loading, details and errors to report/year identity; clear stale responses
  and revoked/expired data. Calendar-local errors must not hide an otherwise
  authorized report, PDF or questions. Never generate a new analysis for a retry
  of this read-only view.
- During a year change, retain the heading/year controls and replace old-year
  amounts with a noninteractive 365/366-cell skeleton laid out for the requested
  UTC year. Preserve the previous calendar body's height while loading so the
  saved-report toolbar does not jump upward. Use neutral theme-based placeholders,
  a subtle pulse (static for reduced motion), `aria-busy` and one loading status;
  skeleton cells are hidden from assistive technology and never imply zero cost.
  Background refresh keeps the already loaded matching-year grid interactive.

The enhancement's Completion Audit records passing automated/local checks and
remaining native/live checks. No new Azure/model job or PDF change was needed.
