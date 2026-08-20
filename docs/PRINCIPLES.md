# Software Design Principles

Condensed from *A Philosophy of Software Design* (John Ousterhout, 2nd ed.) as
working rules for Synvo maintainers and AI coding agents. Every rule carries
its own counter-rule; applying one half without the other is how these
principles get misused.

Precedence when guidance conflicts: the current task instruction, then
`AGENTS.md`, then `docs/project-overview.md` and the phase specification, then
`docs/LESSONS.md`, then this file. This file never authorizes expanding scope,
adding infrastructure, or introducing abstractions listed as non-goals.

## When this applies

Run the Design Checklist below when a change introduces:

- a new public interface, or a change to an existing one
- a new module, package, or frontend module boundary
- a new database table or migration
- a new HTTP, SSE, or Lark-facing contract

Work confined to the implementation behind an existing interface needs no
checklist — that containment is the point of the boundaries.

## Principles

### The objective

1. **Manage complexity.** Complexity is dependencies plus obscurity; its
   symptoms are change amplification, cognitive load, and unknown unknowns
   (the worst). It accumulates in small steps — treat every small degradation
   as significant.
2. **Never trade structure for speed silently.** Working code is the
   by-product; a system that stays cheap to change is the goal. If a shortcut
   is the right call, say so explicitly and state its cost.
3. **Decide what matters.** Prefer designs with leverage — one solution covers
   several problems, one invariant explains many behaviours. Minimize what
   callers must decide; put the important things where they will be seen and
   hide the rest.

### Modules and interfaces

4. **Modules should be deep**: substantial functionality behind a small,
   simple interface. Interface cost is paid by every caller; implementation
   cost once. Do not multiply small classes and services because smaller looks
   cleaner — justify each new boundary by the complexity it hides.
5. **Hide information.** Storage formats, retry policy, protocol and library
   choices live in exactly one module; if changing one fact requires edits in
   several modules, it has leaked. Structure modules by knowledge, never by
   execution order.
6. **Somewhat general-purpose.** Functionality reflects today's needs; the
   interface speaks the module's own vocabulary, not the current caller's.
   Generality applies to interface shape only — it never authorizes code paths
   nothing currently exercises. Hiding what a caller genuinely needs to know
   is not abstraction; it is obscurity.
7. **Push specialization to the edges.** User-facing specifics go up;
   vendor, protocol, and provider variants go down behind one interface. Keep
   the middle general.
8. **Different layer, different abstraction.** A pass-through method, threaded
   variable, or wrapper that adds no new abstraction should not exist.
9. **Pull complexity downwards.** Let the implementer absorb an awkward detail
   once rather than every caller forever — but only detail that belongs to the
   module's function and that it has the information to decide correctly.
10. **Split or join by knowledge, not length.** Join code that shares
    non-obvious information or can only be understood together; split chiefly
    to isolate general-purpose from special-purpose logic. Depth beats length:
    never split a function merely because it is long.

### Errors and special cases

11. **Eliminate special cases by representation.** Choose representations in
    which the edge case is an ordinary value — deleting nothing succeeds,
    an empty range is legal — so no conditional has to detect it.
12. **Define errors out of existence**, in order of preference: make the
    condition legal; mask it low (retry, reconnect, fallback); aggregate
    handling in one place; fail fast on violated invariants. Never silently
    swallow a failure a user or operator must act on.

### Process

13. **Design it twice — at the triggers above.** Compare two materially
    different options with trade-offs and a recommendation, recorded in the
    task notes, before writing code. Skip it inside an existing boundary; do
    not pad routine work with performative alternatives.
14. **Describe the interface before implementing it.** If the description
    comes out long or exception-ridden, fix the design, not the wording. Hard
    to describe and hard to name are the same defect.
15. **Smallest coherent change wins** (`AGENTS.md` change discipline). When a
    change reveals a materially better structure, propose it explicitly —
    silently refactoring it up and silently patching it down are both
    violations.

### Obviousness

16. **Comment only what code cannot say**: invariants, cross-module
    assumptions, the non-obvious why. Match the codebase's sparse convention;
    a comment restating the code is worse than none.
17. **Names are precise and consistent** — the same name always means the same
    thing, and hard-to-name means unclear purpose. Consistency outranks
    preference, including preference derived from training data.
18. **Code must be obvious on first read.** Named types over generic
    containers; visible dependencies over implicit ones. If understanding
    required tracing several files, restructure or document — don't pass that
    cost on. Simpler designs are usually also faster; measure before
    optimizing.

## Red Flags

Any of these should stop an agent and prompt a redesign or a question.

| Red flag | Meaning | Usual fix |
|---|---|---|
| Shallow module | Interface nearly as complex as what it hides | Merge, deepen, or remove the boundary |
| Information leakage | One design decision known to several modules | Consolidate the knowledge into one module |
| Temporal decomposition | Modules organized by execution order, not knowledge | Reorganize around what each module knows |
| Over-exposure | A rare feature must be understood to use the common case | Provide a default and hide it |
| Pass-through method | Forwards to a nearly identical method below | Remove the layer or give it a real abstraction |
| Repetition | The same non-trivial code recurs | Factor out, or redesign so it is unnecessary |
| Special-general mixture | Case-specific code inside a general mechanism | Extract the general core; push specifics outward |
| Conjoined methods | Two units understandable only together | Rejoin them, or redraw the boundary |
| Comment repeats the code | The comment adds no information | Rewrite at a different level, or delete |
| Vague or impossible name | The entity lacks one clear purpose | Rename, split, or redefine it |
| Hard to describe | The abstraction is wrong, not the wording | Redesign the interface |
| Non-obvious code | A reader cannot follow it on a first pass | Document or restructure |

## Agent Design Checklist

Before writing code:

1. Which existing module owns this concern? Extend it before creating a new one.
2. Can the interface — behaviour, errors, side effects, invariants — be stated
   in a few sentences, in the module's own vocabulary?
3. Have two materially different designs been compared and recorded?
4. Does this conflict with `docs/project-overview.md` or the phase spec?
   Raise the conflict before implementing.

While writing:

5. Is any design knowledge now present in more than one module?
6. Does each layer present a different abstraction? Can any special case be
   removed by a better representation?
7. Do names, structure, and patterns match the existing conventions?

Before finishing:

8. Would a maintainer who has never seen this code understand it in one pass?
9. Do the tests lock down interface behaviour, not implementation details?
10. Was anything degraded to keep the change small? If so, say so explicitly.

## Synvo applications

- One clear entry point per domain; other domains never import internals.
- External SDK types stay inside the adapter that owns them — Lark SDK types
  in `synvo.lark`, Spring AI types in the model adapter and configuration. A
  vendor type in a domain signature is information leakage.
- A new backend boundary gets its rule added to `ArchitectureBoundaryTests` in
  the same change. Default to package-private visibility.
- The file system matches the mental model of the system: an agent entering
  the codebase has no memory of it and navigates by structure and names alone.
- Model reasoning proposes; deterministic services validate and execute.
  Idempotent retried writes define duplicate-write errors out of existence;
  preview-and-confirm emphasizes consequential actions instead of hiding them.
