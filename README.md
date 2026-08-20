# Synvo AI Assistant

Synvo is a Lark-native AI assistant for enterprise knowledge research and
meeting-to-execution workflows. The repository is intentionally one React H5
application, one Spring Boot modular monolith, and one PostgreSQL database.

The current tracked baseline completes Phases 0 through 2.5:

```text
Lark Chat --\
             +--> shared conversation boundary --> Synvo Agent Core --> model gateway
React H5 ---/                +--> PostgreSQL lifecycle state
```

This baseline includes the secure Lark connection, encrypted H5 authorization,
NVIDIA Nemotron model boundary, conversation memory, streaming lifecycle,
evolving Lark responses, REST/SSE H5 conversation experience, and the Phase 2.5
module-boundary hardening. Enterprise Knowledge Research, configured-Drive
retrieval, citations, and Meeting-to-Execution are not implemented in this
baseline; each requires its own approved tracked specification before
implementation.

The approved next direction is Phase 3, "Codex in Lark": it replaces the
Nemotron model gateway entirely with the OpenAI Codex agent engine, hosted by
one Python runner behind a Synvo-owned engine port. See
[`docs/project-overview.md`](docs/project-overview.md) for the updated
technology direction.

## Technology

- Frontend: React 19, TypeScript, Vite, and Tailwind CSS
- Backend: Java 21, Spring Boot 4, Maven Wrapper, and the official Lark Java SDK
- Agent engine: NVIDIA Nemotron 3 Super 120B behind the Synvo model gateway
  today; replaced by OpenAI Codex (app-server + official Python SDK) in Phase 3
- Database: PostgreSQL 18 with Flyway migrations
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
   intended pilot environment and Victor.
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

### NVIDIA Nemotron settings

These settings apply to the current baseline and are replaced when Phase 3
lands the Codex engine. Model integration is disabled for an ordinary
credential-free startup. Enable the completed Phase 2 model boundary with
these local `.env` values:

```dotenv
SYNVO_MODEL_ENABLED=true
SYNVO_MODEL_BASE_URL=https://integrate.api.nvidia.com/v1
SYNVO_MODEL_NAME=nvidia/nemotron-3-super-120b-a12b
SYNVO_MODEL_API_KEY=<nvidia-nim-api-key>
```

The model API key remains in `.env`, is passed only to the model adapter, and
must never be committed or logged.

Start the stack and inspect the safe connection state:

```bash
docker compose up --detach --build --wait
docker compose logs --follow backend
```

### Temporary HTTPS access for H5

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

## Current live smoke test

With the developer-console settings and local secrets configured:

1. Confirm the backend reports the bot WebSocket as connected.
2. Send a standalone text direct message from Victor and receive exactly one
   evolving assistant response without quoted reply decoration. Then use Lark's
   Reply action once and confirm the explicit reply anchor is preserved.
3. Confirm a group message and a message from a non-pilot account receive no
   reply.
4. Open the H5 application inside Lark and authorize as Victor.
5. Confirm the page independently reports the bot connection and Victor's user
   authorization.
6. Send one prompt from Lark Chat and one from H5. Confirm both use the shared
   waiting, streaming, and completed lifecycle without duplicate turns.
7. Restart the backend and confirm the authorized connection and persisted
   conversation history remain available.
8. Inspect backend/container logs for credentials, tokens, or message content;
   none should be present.

The live test is manual and must never run in the ordinary automated suite.

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
