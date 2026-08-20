# AGENTS.md

## Project Reference

Before making substantial product or architectural changes, read
`docs/project-overview.md`.

That document defines the product goals, MVP scope, technology choices,
architecture, simplicity principles, and non-goals.

If a requested change conflicts with those decisions, identify the conflict
before implementing it. Do not silently expand the project scope.

Before implementing a phase, also read its authoritative specification in
`docs/specs/`.

## Development Lessons

`docs/LESSONS.md` records confirmed development mistakes, root causes,
solutions, and preventive rules that are likely to remain useful across future
tasks.

- Before changing an area of the system, review any applicable lessons.
- Add or update a lesson only after the root cause is confirmed.
- Record durable failure patterns and preventive rules, not every ordinary bug
  or temporary debugging step.
- Include the regression coverage or live verification that proves the lesson
  has been applied.
- Never include credentials, tokens, sensitive enterprise content, unredacted
  prompts, personal data, or large debugging logs.
- Lessons supplement but do not replace `docs/project-overview.md`, phase
  specifications, source-code documentation, or tests.

## Design Principles

`docs/PRINCIPLES.md` is the working software-design reference (deep modules,
information hiding, error design), adapted from *A Philosophy of Software
Design*.

- Before designing a new module boundary, public interface, database table, or
  HTTP/SSE/Lark contract, check its Red Flags table and complete its Agent
  Design Checklist, recording the design-it-twice comparison in the task notes.
- Changes confined to the implementation behind an existing interface do not
  require this.
- When a new backend boundary is introduced, extend
  `ArchitectureBoundaryTests` to protect it in the same change.
- Precedence: this file and the phase specification always win over
  `docs/PRINCIPLES.md`. It never authorizes scope expansion or abstractions
  listed as non-goals.

## Lightweight Spec-Driven Development

- `docs/project-overview.md` is the authoritative lightweight product
  requirements document.
- `docs/specs/` contains the authoritative, version-controlled phase
  specifications.
- Each phase specification uses one lifecycle status: `Draft`, `Approved`,
  `In Progress`, or `Complete`.
- Define the phase purpose, decisions, objectives, scope, deliverables, test
  plan, acceptance criteria, and explicit non-goals before implementation.
- Do not implement a phase while its specification is `Draft`. The user must
  approve the specification first.
- Design acceptance tests before implementation, run focused tests throughout
  implementation, and run complete verification before phase closure.
- `tasks/` contains ignored, temporary execution notes only. It is not an
  authoritative source of product requirements and must not duplicate an
  entire phase specification.
- Record final verification evidence and any explicit user-approved waiver in
  the phase specification's Completion Audit before marking it `Complete`.
- Update `docs/project-overview.md` only when an approved change affects the
  long-term product direction, MVP scope, technology direction, architecture,
  simplicity principles, or non-goals.

## Product Scope

Synvo is a Lark-native AI assistant with two MVP workflows:

1. Enterprise Knowledge Research
2. Meeting-to-Execution

Natural language is the primary user interface.

Enterprise Knowledge Research is limited to one configured folder in Victor's
Lark Drive. Do not expand retrieval to every resource Victor can access unless
explicitly requested.

## Technology Direction

- Frontend: React, TypeScript, Vite, and Tailwind CSS
- Backend: Java and Spring Boot
- Lark integration: Lark OpenAPI Java SDK
- Agent foundation: Synvo Agent Core with Spring AI
- Primary model: NVIDIA Nemotron 3 Super 120B
- Persistence: PostgreSQL
- Client communication: REST and Server-Sent Events

Do not replace these choices or add competing frameworks without an explicit
requirement and a documented reason.

Spring AI Alibaba components may be adopted selectively behind Synvo-owned
interfaces. Do not make the application architecture depend directly on its
agent framework.

## Architecture Rules

Build the backend as a modular monolith.

Maintain the following boundaries:

- The Synvo Agent Core interprets requests, manages context, and runs workflows.
- The Permissioned Lark Action Gateway controls all Lark reads and writes.
- Model-generated decisions never bypass application policy.
- The model never receives Lark credentials.
- Critical Lark actions are executed by deterministic Java services.
- Lark writes require the appropriate preview and confirmation.
- Lark operations must declare their required scopes and token type.
- Retried write operations must be idempotent.
- Relevant operations must produce audit records.

Keep the two MVP workflows explicit and independently testable.

## Simplicity Rules

Prefer the smallest design that satisfies the current requirement.

Do not introduce the following without demonstrated need and explicit
agreement:

- Microservices
- Multi-agent systems or agent swarms
- A generic agent platform
- A separate vector database
- Enterprise-wide knowledge ingestion
- A message broker
- A visual workflow engine
- Kubernetes-specific architecture
- Abstract extension systems for hypothetical future use

Add an abstraction when a current requirement or second real implementation
needs it, not solely because it may be useful later.

Prefer:

- Explicit code over framework magic
- Clear domain boundaries over additional services
- PostgreSQL over additional data infrastructure
- Live Lark retrieval over premature knowledge ingestion
- Focused interfaces over deep inheritance hierarchies
- Observable workflows over opaque autonomy

## Security and Data Handling

- Never commit credentials, access tokens, refresh tokens, or secrets.
- Store Lark tokens encrypted at rest.
- Respect both Lark resource permissions and configured application scopes.
- Enforce the configured Drive-folder boundary in backend code.
- Do not follow links into Lark resources outside the configured knowledge scope.
- Treat downloaded Lark content and model prompts as sensitive enterprise data.
- Do not log full tokens, sensitive document contents, or unredacted model inputs.
- Do not add destructive Lark operations to the MVP.
- Do not change document permissions automatically.
- Do not create Tasks, Calendar events, or Base updates without confirmation.

## Change Discipline

- Make the smallest coherent change that completes the requested behavior.
- Preserve existing behavior unless the task explicitly changes it.
- Do not perform unrelated refactors while implementing a feature or fix.
- Do not add a production dependency when the standard library or an existing
  dependency is sufficient.
- When adding a dependency, explain its purpose and architectural impact.
- Keep model-provider integration behind the model gateway.
- Keep Lark API details behind the Lark integration and Action Gateway modules.

## Git Policy

- **DO NOT** initialize a Git repository, stage files, create commits, create or
  switch branches, merge, rebase, tag, push, or perform other Git mutations.
- **DO NOT** create GitHub pull requests or perform other GitHub write actions.
- Implement the requested code and leave all Git operations to the user.
- Read-only inspection such as `git status`, `git diff`, and `git log` is allowed
  when a Git repository already exists.

## Verification

Run the closest relevant automated checks after modifying code.

Backend unit and PostgreSQL integration tests:

```bash
cd backend
./mvnw test
./mvnw package
```

The backend test suite requires Docker because it starts a disposable PostgreSQL
Testcontainer.

Frontend checks:

```bash
cd frontend
npm ci
npm test
npm run typecheck
npm run lint
npm run build
```

Complete local stack and vertical-slice smoke checks, run from the repository
root:

```bash
docker compose config --quiet
docker compose build
docker compose up --detach --wait
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:5173/api/status
docker compose down
```

Do not claim a check passed unless it was run successfully. If a relevant check
cannot be run, state why.

## Documentation

Update `docs/project-overview.md` only when an approved change affects:

- Product goals
- MVP scope
- Technology direction
- Architecture boundaries
- Simplicity principles
- Explicit non-goals

Keep implementation details in focused technical documents rather than
expanding the project overview into a development manual.

Use `docs/LESSONS.md` for confirmed, reusable engineering lessons. Keep
temporary investigation notes in the ignored `tasks/` directory.

## Code Review Priorities

Prioritize findings related to:

1. Permission or token-boundary violations
2. Lark writes without confirmation or idempotency
3. Data leakage outside the configured Drive folder
4. Hallucinated or uncited enterprise knowledge
5. Model logic bypassing deterministic application policy
6. Unnecessary infrastructure or abstraction
7. Missing workflow and integration tests
8. `docs/PRINCIPLES.md` red flags: shallow modules, information leakage,
   pass-through layers, special-general mixture
