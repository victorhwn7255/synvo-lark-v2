# Synvo AI Assistant

Synvo AI Assistant is a single-user, Lark-native client for OpenAI Codex. The
repository is intentionally one React H5 application, one Spring Boot modular
monolith, one PostgreSQL database, and one private Python runner sidecar.

The current codebase includes the completed Phases 0 through 3. Phase 3,
**Codex in Lark**, was completed on 2026-08-24 and provides:

```text
Lark Chat (deferred) --\
                        +--> application conversation boundary --> workspace-agent facade
React H5 (supported) ---/                 |         |                    |
                                         |         +--> PostgreSQL      +--> private runner
                                         |                                  +--> pinned Codex App Server
                                         +--> encrypted Lark-token boundary       +--> OpenAI-hosted inference
```

H5 is the supported employee surface for the current Phase 3 rollout, with
configured workspace/task management, rich activity, dynamic approvals,
skills, MCP, goals, review, steering, and thread controls. The native Lark Chat
integration remains in the architecture as a deferred companion surface, but
is not part of Phase 3 closure verification. Spring Boot owns identity,
authorization, workspace policy, audit, persistence, and the one-active-turn
lease. The runner hides the pinned stable App Server protocol and local tool
execution behind a narrow Synvo contract. Enterprise Knowledge Research,
Drive retrieval, citations, Meeting-to-Execution, and the first opinionated
Synvo workplace workflow remain outside Phase 3.

The completed H5 experience includes:

- configured Finance, Products, and Sales workspaces with permanent per-task
  workspace binding;
- Read Only and Full Edit document/data tasks, deterministic artifact
  validation, streaming results, and safe file-change summaries;
- task creation, continuation, rename, pin, archive, delete, fork, retry,
  review, cancellation, and restart recovery;
- live agent-activity milestones, steering delivery and history, terminal
  status, persistent task goals, and usage reporting;
- configured Codex skills plus allowlisted read-only MCP tools and bounded MCP
  elicitation in H5; and
- one active top-level Codex turn system-wide with deterministic lifecycle,
  authorization, idempotency, audit, and terminal outcomes.

Routine work already inside the selected sandbox runs without approval clicks.
Bounded workspace-relative file decisions and allowlisted MCP interactions use
one-time H5 decisions. Credential access, other workspaces, unrestricted host
access, network-enabling requests, opaque command elevation, and Full Access
remain unavailable rather than being converted into broad approval prompts.

## Technology

- Frontend: React 19, TypeScript, Vite, and Tailwind CSS
- Backend: Java 21, Spring Boot 4, Maven Wrapper, and the official Lark Java SDK
- Agent engine: exact `gpt-5.6-sol` through pinned `@openai/codex` /
  `codex-cli 0.148.0` App Server; the private runner uses direct stable stdio
  JSON-RPC and does not use the public Python SDK
- Database: PostgreSQL 18 with committed Flyway migrations V1 through V6
- Local runtime: Docker Compose

The lightweight product requirements and architecture reference is
[`docs/project-overview.md`](docs/project-overview.md). Authoritative phase
specifications live in [`docs/specs/`](docs/specs/).

## Run the ordinary local stack

Docker Desktop with Docker Compose is the only requirement for the complete
stack. Lark and model integrations are disabled by default, so no credentials,
public URL, or tunnel is required.

```bash
docker compose config --quiet
docker compose up --detach --build --wait
```

Open <http://127.0.0.1:5173>. Verify the backend directly and through the
frontend proxy:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:5173/api/status
curl -fsS http://127.0.0.1:5173/api/lark/connection
```

The last response reports Lark as disabled until the Lark settings below are
supplied.

Stop the containers without deleting PostgreSQL data:

```bash
docker compose down
```

Use `docker compose down --volumes` only when you intentionally want to delete
all local database data.

## Lark setup

Use one Lark custom application for both capabilities:

1. Enable **Bot** and **Web App/H5** in the Lark developer console.
2. Select WebSocket/long-connection event delivery. Do not configure a webhook.
3. Subscribe only to `im.message.receive_v1` for the current direct-message
   channel.
4. Enable the user scope `offline_access`; the H5 client requests only this
   scope so the backend can safely refresh Victor's user token on demand.
5. Keep the application's availability and contact range restricted to the
   intended single-user environment and Victor.
6. When creating the release version, set **Default feature** to **Web App** for
   both PC and mobile. This makes the Workplace tile open the H5 application;
   the Bot remains available through Messenger.
7. Publish or release the application version required by the tenant before the
   live test.

The implementation accepts only direct (`p2p`) messages from Victor's configured
`open_id`. It ignores group messages, other users, and bot-authored messages.
Accepted text enters the same conversation application boundary used by H5 and
receives one evolving assistant response. When Victor explicitly uses Lark's
Reply action, Synvo preserves that reply anchor. Unsupported content receives a
deterministic text-only notice.

### Obtain Victor's open ID

Use the Lark developer console's API Explorer for the same custom application:

1. Open the Contacts operation **Obtain user ID via email or mobile number**.
2. Restrict the application's Contacts data range to Victor rather than all
   employees.
3. Enter Victor's corporate email or mobile number and select `open_id` as the
   returned ID type.
4. Request the minimal user-ID lookup scope if the console says it is missing.
5. Copy only the returned `open_id` into the local `.env` file.

This avoids an allow-all discovery mode in the application. An `open_id` is
application-specific, so obtain it using this Synvo custom application rather
than reusing a value from another app.

### Local secrets and settings

Create a local environment file and a 256-bit token-encryption key:

```bash
cp .env.example .env
openssl rand -base64 32
```

Set these values in `.env`; never commit the file:

```dotenv
SYNVO_LARK_ENABLED=true
SYNVO_LARK_APP_ID=<custom-app-id>
SYNVO_LARK_APP_SECRET=<custom-app-secret>
SYNVO_LARK_TRANSPORT=websocket
SYNVO_LARK_PILOT_OPEN_ID=<victor-open-id>
SYNVO_LARK_H5_BASE_URL=<temporary-https-url>
SYNVO_TOKEN_ENCRYPTION_KEY=<base64-encoded-32-byte-key>
SYNVO_SESSION_SECURE_COOKIE=true
```

The application fails startup if an enabled Lark configuration is incomplete.
The browser receives only a Secure, HttpOnly Synvo session cookie; Lark access
and refresh tokens are exchanged in Spring Boot and encrypted with AES-256-GCM
before PostgreSQL persistence.

## Codex runner and business workspaces

The ordinary stack keeps Codex disabled and remains credential-free. The
enabled overlay registers three isolated synthetic business workspaces. The
tracked defaults may be replaced with other explicitly approved local folders:

```dotenv
SYNVO_CODEX_FINANCE_WORKSPACE_HOST_PATH=./workspaces/Finance
SYNVO_CODEX_PRODUCTS_WORKSPACE_HOST_PATH=./workspaces/Products
SYNVO_CODEX_SALES_WORKSPACE_HOST_PATH=./workspaces/Sales
SYNVO_CODEX_ALLOWED_MCP_SERVERS=
```

H5 can select Finance, Products, or Sales for each new task. The retained
native Lark Chat integration defaults to Products if it is reactivated for
future verification. Every task remains permanently bound to its selected
workspace, and the runner receives only the three explicit folder mounts.

Validate and build the enabled topology with both Compose files:

```bash
docker compose -f compose.yaml -f compose.codex.yaml config --quiet
docker compose -f compose.yaml -f compose.codex.yaml build
```

Codex authentication is interactive and subscription-based. It is stored in
the dedicated `synvo_codex_credentials` volume, completely separate from the
task workspaces and PostgreSQL. There is no OpenAI API-key path. Never copy,
print, inspect, or commit the credential files; use the pinned Codex CLI inside
the runner image to complete login when requested during controlled live
verification.

The verified device-login command is:

```bash
docker compose -f compose.yaml -f compose.codex.yaml run --rm --no-deps \
  --entrypoint /opt/codex/node_modules/.bin/codex codex-runner \
  login --device-auth
```

Never share or record the device code. To exercise the tracked harmless MCP
vertical slice, first set `SYNVO_CODEX_ALLOWED_MCP_SERVERS=synvo_safe_fixture`,
then register only the included bounded fixture in the same isolated Codex
home:

```bash
docker compose -f compose.yaml -f compose.codex.yaml run --rm --no-deps \
  --entrypoint /opt/codex/node_modules/.bin/codex codex-runner \
  mcp add synvo_safe_fixture \
  --env SYNVO_MCP_FIXTURE_ROOT=/var/lib/synvo-mcp-fixture \
  -- python3 /app/fixtures/safe_mcp_server.py
```

The fixture has one fixed read-only response and one fixed workspace marker.
The marker cannot be written until the stable MCP elicitation is accepted in
H5. Do not register unreviewed or Lark-capable MCP servers in Phase 3; the
runner fails startup when configured MCP inventory exceeds the deployment
allowlist or lacks a safe risk classification.

Start the enabled stack and inspect only its safe status endpoints:

```bash
docker compose -f compose.yaml -f compose.codex.yaml up --detach --wait
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:5173/api/status
```

The runner is exposed only to the private Compose network. Agent commands have
no network access; read-only tasks cannot modify the workspace, and Full Edit
tasks can write only inside the configured mount. The legacy Phase 2
Nemotron/Spring AI/NVIDIA code path remains present but disabled; it is not part
of the supported Phase 3 runtime. Do not enable it for Codex verification.

## Temporary HTTPS access for H5

The bot's outbound WebSocket does not need a public callback URL. The H5 page
does need an HTTPS URL that Lark can open. The verified local tunnel client is
ngrok 3; run it separately from Compose so it never becomes application
infrastructure:

```bash
ngrok http 5173
```

Copy the temporary HTTPS forwarding URL into all of these locations:

1. `SYNVO_LARK_H5_BASE_URL` in the local `.env` file.
2. The custom application's Web App/H5 desktop and mobile homepage settings.
3. **Security Settings → Redirect URLs** for the custom application. Use the
   exact temporary HTTPS origin/page that Lark opens.
4. **Security Settings → H5 trusted domains** for the custom application so the
   page can call authenticated Lark JSAPIs.

Then rebuild/restart the backend if the environment changed. The tunnel targets
the frontend entry point, whose Nginx configuration keeps the UI and `/api` on
the same origin. The page loads the version-pinned official Lark H5 JSSDK and
waits for `h5sdk.ready` before calling the current `requestAccess` API. It sends
only `offline_access` plus a one-time OAuth state and returns the short-lived
authorization code to the Spring Boot exchange endpoint.

Do not save the tunnel URL or ngrok credentials in the repository. Stop ngrok
with `Ctrl+C` after the H5 test; the ordinary Compose stack continues to work
without it.

## Phase 3 completion and verification

Phase 3 is complete. Its redacted Completion Audit is recorded in
[`docs/specs/phase-3-codex-in-lark.md`](docs/specs/phase-3-codex-in-lark.md).
The final verification established:

- 61 runner tests passed;
- 237 backend tests passed through both `./mvnw test` and `./mvnw package`;
- 104 frontend tests, typecheck, lint, and production build passed;
- empty-database and populated-V4 upgrades both applied V1 through V6;
- base and Codex-enabled Compose configurations validated, all application
  images rebuilt, and PostgreSQL, runner, backend, and frontend became healthy;
- runtime discovery reported App Server `0.148.0`, exact model `gpt-5.6-sol`,
  14 enabled stable features, and 45 below-stable features disabled; and
- the count-only security audit found no tracked environment secrets,
  credential-shaped production values, forbidden raw audit fields, sensitive
  browser persistence, or overlap between the credential and workspace mounts.

Controlled H5 verification covered Read Only and Full Edit document/data
tasks, deterministic CSV and Markdown validation, skills, read-only MCP,
elicitation-backed MCP approval, goals, steering and steering history, stop,
retry, task/thread management, restart recovery, and terminal presentation.
The final Full Edit check created exactly one report, preserved ten existing
files, reconciled every calculation within USD 0.01, and verified source hashes
and workspace inventory.

Native Lark Chat remains implemented but is not a supported employee surface
for this rollout. Victor explicitly waived the remaining native Chat and
Lark-to-H5 tests for Phase 3 closure. React H5 is the supported application;
native Chat must receive a separately approved test plan and direct live
verification before it is promoted again.

Live H5 checks are manual and must never run in the ordinary automated suite.
When repeating them, never print logs containing message bodies, prompts,
credentials, raw output, diffs, configured paths, or enterprise content.

## Next workflow

`wf-keystone-quotation` will add the first opinionated, bounded Synvo workplace
workflow on top of the completed Codex-in-Lark foundation. Its charter and
phase specifications live in `docs/specs/wf-keystone-quotation/`.
Permissioned elevated execution, temporary additional-folder grants, or
authenticated external operations should be introduced only if the selected
workflow phase demonstrates a concrete need and receives an approved
specification and acceptance tests.

## Environment configuration

Use [`.env.example`](.env.example) as the complete reference. `.env`, secret
files, build output, local task plans, and runtime data are ignored by Git.
Lark and model integrations remain disabled by default and are enabled only by
validated local configuration.

## Backend checks

Java 21 and Docker are required. The test suite starts a disposable PostgreSQL
Testcontainer.

```bash
cd backend
./mvnw test
./mvnw package
```

## Frontend checks

Node.js 22 and npm are required.

```bash
cd frontend
npm ci
npm test
npm run typecheck
npm run lint
npm run build
```

For native frontend development, keep the backend available on port `8080`, then
run `npm run dev` from `frontend/`. Vite proxies `/api` requests to the backend.
