# Synvo AI Assistant

Synvo is a Lark-native AI assistant for enterprise knowledge research and
meeting-to-execution workflows. This repository currently contains the Phase 0
foundation: one React H5 frontend, one Spring Boot backend, and one PostgreSQL
database.

The implemented vertical slice is intentionally small:

```text
React H5 → Spring Boot → PostgreSQL
```

Lark OAuth, events, Drive retrieval, model calls, and agent workflows are not
implemented in Phase 0. Their configuration boundaries exist but remain disabled
by default.

## Technology

- Frontend: React 19, TypeScript, Vite, and Tailwind CSS
- Backend: Java 21, Spring Boot 4, and Maven Wrapper
- Database: PostgreSQL 18
- Local runtime: Docker Compose

The product and architecture reference is
[`docs/project-overview.md`](docs/project-overview.md). The Phase 0 scope and
acceptance criteria are in
[`tasks/phase-0-foundation.md`](tasks/phase-0-foundation.md).

## Run the complete stack

Docker Desktop with Docker Compose is the only requirement for the complete
local stack. The checked-in defaults are safe for local development; copying the
environment template is optional unless you want to change ports or local
database settings.

```bash
docker compose config --quiet
docker compose up --detach --build --wait
```

Open the frontend at <http://127.0.0.1:5173>. The backend health endpoint is at
<http://127.0.0.1:8080/actuator/health>.

Verify the vertical slice:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:5173/api/status
```

Expected status payload:

```json
{"service":"synvo-backend","status":"ready"}
```

Stop the containers without deleting the persistent PostgreSQL volume:

```bash
docker compose down
```

Use `docker compose down --volumes` only when you intentionally want to delete
all local PostgreSQL data.

## Environment configuration

Use [`.env.example`](.env.example) as the reference. To customize the local
environment on a new checkout:

```bash
cp .env.example .env
```

Never commit `.env` or real credentials. Phase 0 forces Lark and model
integrations off in Compose and does not pass their credentials to containers.

## Backend checks

Java 21 and Docker are required. Docker is used by Testcontainers to run the
PostgreSQL integration test.

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
