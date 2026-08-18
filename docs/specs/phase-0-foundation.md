# Phase 0 — Project Foundation

Status: **Complete**
Last updated: 2026-08-18

## Purpose

Phase 0 establishes a small, working, and testable foundation for the Synvo AI
Assistant MVP. It creates the repository structure, development toolchain, local
runtime, and minimum vertical slice required for subsequent feature work.

The outcome is not an architecture framework or a collection of empty modules.
It is a runnable system that proves this path:

```text
React H5 → Spring Boot → PostgreSQL
```

Lark and NVIDIA Nemotron integrations are represented only by configuration
boundaries and explicit placeholders. OAuth, Lark events, Drive retrieval, model
calls, agent workflows, and enterprise actions begin in later phases.

## Confirmed Decisions

| Area | Decision |
|---|---|
| Repository | One repository with separate `frontend/` and `backend/` applications |
| Backend | Java 21, Maven, Spring Boot |
| Java root package | `synvo` |
| Frontend | React, TypeScript, Vite, Tailwind CSS |
| Package manager | npm |
| Database | PostgreSQL |
| Local runtime | Docker Compose |
| Application style | Spring Boot modular monolith |
| External integrations | Configuration boundaries only in Phase 0 |

## Objectives

1. Create a clear repository structure for the frontend, backend, documentation,
   and task plans.
2. Scaffold a minimal Spring Boot application using Java 21, Maven, and the
   `synvo` root package.
3. Scaffold a minimal React H5 application using TypeScript, Vite, Tailwind CSS,
   and npm.
4. Connect the frontend to a small backend status API.
5. Establish PostgreSQL connectivity without designing premature domain tables.
6. Provide a reproducible local environment through Docker Compose.
7. Create production-capable multi-stage Dockerfiles for the frontend and backend.
8. Establish safe configuration boundaries for Lark and Nemotron without making
   real external calls.
9. Add focused automated tests and a container smoke test.
10. Document the exact commands that are proven to work.

## Target Repository Structure

```text
synvo-lark-v2/
├── AGENTS.md
├── README.md
├── .env.example
├── .gitignore
├── compose.yaml
│
├── docs/
│   └── project-overview.md
│
├── tasks/
│   └── phase-0-foundation.md
│
├── frontend/
│   ├── Dockerfile
│   ├── .dockerignore
│   ├── nginx.conf
│   ├── package.json
│   ├── package-lock.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── api/
│       ├── components/
│       └── styles/
│
└── backend/
    ├── Dockerfile
    ├── .dockerignore
    ├── .mvn/
    │   └── wrapper/
    ├── mvnw
    ├── mvnw.cmd
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/synvo/
        │   │   ├── SynvoApplication.java
        │   │   ├── api/
        │   │   ├── configuration/
        │   │   └── integration/
        │   └── resources/
        │       └── application.yml
        └── test/
            └── java/synvo/
```

Only directories needed by Phase 0 should be created. The `agent`, `research`,
`meeting`, `lark`, and persistence packages should be added when their first real
use cases are implemented rather than created as empty placeholders.

## Deliverables

### 1. Root repository configuration

- [x] Add a root `.gitignore` covering Java, Maven, Node, Vite, IDE, Docker,
      local environment, and operating-system artifacts.
- [x] Add `.env.example` containing names and safe placeholder values only.
- [x] Add a concise `README.md` with verified local setup, test, build, and
      shutdown commands.
- [x] Keep `AGENTS.md` and `docs/project-overview.md` as the governing project
      references.
- [x] Do not commit `.env`, credentials, tokens, generated build output, or npm
      dependencies.

### 2. Spring Boot backend

- [x] Generate a Maven-based Spring Boot application targeting Java 21.
- [x] Commit the Maven Wrapper so the backend does not depend on a separately
      installed Maven version.
- [x] Use `synvo` as the root Java package.
- [x] Place `SynvoApplication` directly in `src/main/java/synvo/` so Spring can
      discover its subpackages.
- [x] Add only the dependencies required for the Phase 0 vertical slice:
      web, validation, health/actuator, PostgreSQL connectivity, and tests.
- [x] Add a small `GET /api/status` endpoint with a stable JSON response.
- [x] Expose an internal health endpoint suitable for Docker health checks.
- [x] Configure graceful startup and shutdown behavior using Spring Boot
      configuration rather than custom lifecycle infrastructure.
- [x] Keep controllers thin and avoid introducing unused architecture layers.

Suggested status response:

```json
{
  "service": "synvo-backend",
  "status": "ready"
}
```

Database readiness may be exposed through the internal health endpoint rather
than embedded in the public status contract.

### 3. External integration boundaries

- [x] Add typed Spring configuration properties for Lark connection settings.
- [x] Add typed Spring configuration properties for the Nemotron model endpoint.
- [x] Keep both integrations disabled by default in local Phase 0 configuration.
- [x] Validate configuration when an integration is explicitly enabled.
- [x] Provide explicit placeholder interfaces or disabled adapters only where
      required by the application startup path.
- [x] Unsupported integration calls must fail clearly rather than silently return
      invented data.
- [x] Do not add the Lark SDK or Spring AI dependencies until a real integration
      phase requires them.

Expected configuration names should be documented without containing secrets:

```text
SYNVO_LARK_ENABLED
SYNVO_LARK_APP_ID
SYNVO_LARK_APP_SECRET
SYNVO_MODEL_ENABLED
SYNVO_MODEL_BASE_URL
SYNVO_MODEL_NAME
SYNVO_MODEL_API_KEY
```

### 4. React H5 frontend

- [x] Generate a Vite React application using TypeScript and npm.
- [x] Configure Tailwind CSS without adding a separate UI framework.
- [x] Remove starter demonstration content and assets.
- [x] Create a minimal Synvo application shell.
- [x] Add a small typed API client for the backend status endpoint.
- [x] Show backend connectivity as a clear ready, loading, or error state.
- [x] Keep state local; do not add global state management in Phase 0.
- [x] Do not add application routing until more than one real screen exists.
- [x] Configure the backend base URL through a Vite environment variable.

The Phase 0 page should prove integration, not attempt to represent the final
research or meeting user experience.

### 5. PostgreSQL

- [x] Run PostgreSQL as a local Compose service with a persistent development
      volume.
- [x] Configure the backend entirely through environment variables.
- [x] Add a health check and ensure the backend waits for database readiness.
- [x] Verify the backend can establish a real PostgreSQL connection.
- [x] Do not expose PostgreSQL outside the local host unnecessarily.
- [x] Do not design agent, conversation, memory, or workflow tables in Phase 0.
- [x] Introduce a migration tool only with a verified configuration; add the first
      schema migration with the first real persistent domain model.

### 6. Docker

- [x] Add a multi-stage backend Dockerfile that builds with Maven and runs with a
      Java 21 runtime image.
- [x] Add a frontend Dockerfile with a development target and a production target
      that serves built static files.
- [x] Run containers as non-root users where the selected base images support it
      cleanly.
- [x] Add `.dockerignore` files so build output, dependencies, secrets, and local
      files are not copied into image contexts.
- [x] Add a root `compose.yaml` containing `frontend`, `backend`, and `postgres`.
- [x] Add service health checks and explicit dependency readiness.
- [x] Keep Lark, Nemotron, tunnels, reverse proxies, and observability stacks out
      of the Phase 0 Compose file.
- [x] Do not create a production Compose override until the production hosting
      target is selected.

### 7. Documentation alignment

- [x] Update the verification section in `AGENTS.md` with the exact commands that
      work after scaffolding.
- [x] Add verified commands and environment setup to `README.md`.
- [x] Keep implementation details out of `docs/project-overview.md` unless Phase 0
      changes a documented architectural decision.
- [x] Record deviations from this plan in the task file or a focused decision
      record rather than silently changing the foundation.

## Test Plan

### Backend automated tests

- [x] Application configuration loads under the test profile.
- [x] `GET /api/status` returns HTTP 200 and the expected JSON contract.
- [x] Invalid enabled Lark configuration fails validation without exposing secret
      values.
- [x] Invalid enabled model configuration fails validation without exposing secret
      values.
- [x] Database integration is tested against PostgreSQL, not an incompatible
      substitute database.
- [x] `./mvnw test` passes from the `backend/` directory.

Use a focused PostgreSQL Testcontainers integration test if necessary to keep
the backend test suite reproducible and faithful to production SQL behavior.

### Frontend automated tests

- [x] The application shell renders.
- [x] The loading state renders while status is being requested.
- [x] The ready state renders after a successful backend response.
- [x] The error state renders after a failed backend response.
- [x] The API client correctly parses the status response.
- [x] `npm test` passes from the `frontend/` directory.
- [x] Type checking and the production build pass.

Use Vitest and React Testing Library unless the generated toolchain provides an
equally small and suitable alternative.

### Docker and integration smoke tests

- [x] `docker compose config` validates the Compose definition.
- [x] `docker compose build` builds the frontend and backend images.
- [x] `docker compose up --wait` reaches healthy state for all services.
- [x] The backend health endpoint succeeds from the host.
- [x] The frontend loads from the host.
- [x] The frontend successfully displays backend readiness.
- [x] The backend health check confirms PostgreSQL connectivity.
- [x] `docker compose down` stops the stack cleanly.
- [x] Recreating the stack preserves the local PostgreSQL volume.

### Security and repository checks

- [x] No secrets or real credentials are committed.
- [x] `.env` is ignored and `.env.example` contains placeholders only.
- [x] Docker build contexts exclude local dependencies and secret files.
- [x] Container logs do not print configured secret values.
- [x] No code attempts to call Lark or Nemotron during Phase 0 tests or startup.

## Acceptance Criteria

Phase 0 is complete when all of the following are true:

1. A new developer can clone the repository, copy the environment template, and
   start the complete local stack using documented commands.
2. The React H5 page loads and confirms connectivity to the Spring Boot backend.
3. The backend reports healthy only when its required PostgreSQL connection is
   available.
4. Frontend and backend production images build successfully using multi-stage
   Dockerfiles.
5. Backend, frontend, database integration, and Compose smoke tests pass.
6. Lark and Nemotron integrations are disabled and make no external calls.
7. Configuration contains no committed secrets.
8. The Java source root is `backend/src/main/java/synvo/` and tests mirror it at
   `backend/src/test/java/synvo/`.
9. The implementation remains a single React application, a single Spring Boot
   application, and one PostgreSQL database.
10. `README.md` and `AGENTS.md` contain only commands verified against the
    scaffolded repository.

## Explicit Non-Goals

Phase 0 does not implement:

- Lark OAuth or token storage
- Lark event, message, card, or H5 callbacks
- Lark Drive discovery or document retrieval
- NVIDIA Nemotron or other model calls
- Spring AI or Spring AI Alibaba workflow integration
- Natural-language intent routing
- Enterprise Knowledge Research
- Meeting-to-Execution
- Agent memory or conversation persistence
- Workflow, task, or audit domain schemas
- Server-Sent Events
- A reverse proxy or public HTTPS tunnel
- Cloud deployment or production infrastructure
- CI/CD tied to an unconfirmed hosting provider
- Kubernetes, microservices, message brokers, or a vector database

## Implementation Notes

- The Spring Boot parent is `4.1.0`. Spring Initializr initially supplied the
  non-existent coordinate `4.1.0.RELEASE`; Maven Central publishes this release
  without the suffix.
- Lark and model boundaries are typed, validated configuration properties. No
  empty gateway or adapter interfaces were added because Phase 0 has no startup
  path that calls either integration.
- The production frontend target uses unprivileged Nginx to serve the built H5
  files and forward same-origin `/api` traffic to the backend. This remains part
  of the frontend container rather than introducing a separate proxy service.

## Implementation Sequence

1. **Repository baseline** — root configuration, environment template, and
   verified tool versions.
2. **Backend baseline** — Spring Boot application, configuration boundaries,
   status API, health check, and tests.
3. **Frontend baseline** — React H5 shell, status client, UI states, and tests.
4. **Database baseline** — PostgreSQL connectivity and integration verification.
5. **Container baseline** — multi-stage images, Compose stack, and health checks.
6. **End-to-end verification** — full local startup, smoke tests, clean shutdown,
   and documentation of proven commands.

Each step should leave the repository runnable. Avoid creating later-phase
packages or dependencies merely to make the folder tree look complete.

## Definition of Done

- [x] Every acceptance criterion is satisfied.
- [x] All automated and smoke tests pass.
- [x] The documented local commands were executed successfully.
- [x] No real Lark or Nemotron credentials were required.
- [x] No known Phase 1 feature work was pulled into Phase 0.
- [x] The resulting codebase is smaller and clearer than an equivalent design
      requiring additional services or frameworks.

## Completion Audit

Phase 0 was completed before adoption of the lightweight specification
lifecycle. Its deliverables, automated checks, Docker smoke tests, security
checks, acceptance criteria, and Definition of Done are recorded as complete in
this specification. The resulting React, Spring Boot, PostgreSQL, and Docker
foundation remained intact through the completed Phase 1 implementation.
