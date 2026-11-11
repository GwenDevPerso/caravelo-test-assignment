# Route Network Sync Service

Keeps each airline tenant's route network in sync with the Caravelo platform. For each tenant it fetches routes from the tenant's configured sources (primary first, then fallbacks), normalises them, stores them in PostgreSQL, records added, removed and modified routes, and serves them over REST.

- What it does and the acceptance criteria: [`SPEC.md`](SPEC.md) (REST API: §6)
- Design and trade-offs: [`DECISIONS.md`](DECISIONS.md)
- Java API, configuration keys and tables: [`docs/implementation-contract.md`](docs/implementation-contract.md)
- How it was built with AI: [`AI_WORKFLOW.md`](AI_WORKFLOW.md)

Stack: Java 21, Spring Boot 4.1, PostgreSQL 17, Flyway, JUnit 5 / Mockito / Testcontainers / WireMock / ArchUnit, Docker Compose. Optional Vue 3 frontend in [`frontend/`](frontend/README.md).

## Run

Prerequisite: Docker with Compose v2, running.

```bash
cp .env.example .env        # local database credentials; edit if you like
docker compose up --build
```

This starts:

| Service        | Port   | Role                                                                                   |
| -------------- | ------ | -------------------------------------------------------------------------------------- |
| `postgres`     | `5432` | Database (schema created by Flyway at startup)                                         |
| `mock-sources` | `8089` | WireMock serving the sample payloads: `tenant1.json` (Tenant A API), `tenant2.json` (backup provider), `docker/mock-sources/tenant-c.json` (Tenant C) |
| `app`          | `8080` | The service. At startup it syncs every tenant once, in the background                  |

Then:

```bash
curl localhost:8080/api/tenants
curl 'localhost:8080/api/tenants/tenant-a/routes?origin=ACA'        # {"ACA":["AGU","BJX",…]}  (58 destinations)
curl 'localhost:8080/api/tenants/tenant-a/routes?origin=ACA&type=DIRECT'
curl localhost:8080/api/tenants/tenant-a/changes?limit=20
curl localhost:8080/api/tenants/tenant-a/health
curl -X POST localhost:8080/api/tenants/tenant-a/sync
```

To see the resilience at work: `docker compose stop mock-sources`, trigger a sync (status `FAILED`, stored network kept, `errorCount` +1), then `docker compose start mock-sources`.

## Frontend

An optional Vue 3 app that consumes the API only (SPEC phase 2). It runs on the Vite dev server, which forwards `/api` to the backend on `:8080`, so start `docker compose up --build` first. Requires Node 22.18+ or 24.12+.

```bash
cd frontend
npx npm@11 install   # npm 10.9 crashes on this dependency tree; npm 11 works
npm run dev          # http://localhost:5173
```

| Page                          | Shows                                                                         |
| ----------------------------- | ----------------------------------------------------------------------------- |
| `/`                           | Tenants, enabled or disabled, with their sources                              |
| `/tenants/:id/routes`         | Destinations per origin, filters `origin`, `destination`, `type` (kept in the URL) |
| `/tenants/:id/changes`        | Change log (added, removed, modified)                                         |
| `/tenants/:id/health`         | Last sync, status, source used, last error, staleness, and a **sync** button  |

Build and tests: `npm run build`, `npm run test:unit -- --run`. Details: [`frontend/README.md`](frontend/README.md).

End-to-end tests (Playwright, real browser → Vite → Spring → PostgreSQL, with `mock-sources` serving the sample data), desktop and 360 px mobile:

```bash
docker compose up -d --build                      # from the repository root, first
cd frontend && npx playwright install chromium    # once
npm run test:e2e                                  # starts `npm run dev` if it is not already running
```

## Syncing

A tenant is synced in three ways:

| Trigger   | When                                                                                       | Setting                                                    |
| --------- | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------- |
| Startup   | Once per enabled tenant when the app starts, in the background                             | `caravelo.sync.on-startup` (default `true`)                |
| Scheduled | Per-tenant cron (default daily at 03:00; tenants are staggered in `application.yaml`)          | `caravelo.tenants.<id>.sync-cron`, `caravelo.sync.scheduler-enabled` |
| Manual    | `curl -X POST localhost:8080/api/tenants/<id>/sync`, or the **sync** button on the health page | —                                                  |

The manual call waits for the run and returns it (`status`, `sourceUsed`, counts, `error`). Only one sync per tenant runs at a time: a second manual call gets `409`, and a cron tick that finds a sync running is skipped. The result is visible in `GET /api/tenants/<id>/health` and, for each change, in `GET /api/tenants/<id>/changes`.

## Logs

The app logs JSON (Elastic Common Schema) to stdout; there is no log file.

```bash
docker compose logs -f app                                   # follow
docker compose logs app | grep '"tenantId":"tenant-a"'       # one tenant
docker compose logs app | grep <runId>                       # one sync (runId returned by POST …/sync)
docker compose logs app | jq -R 'fromjson? | select(.["log.level"]=="ERROR")'   # errors only
```

Running outside Docker (`./mvnw spring-boot:run`), the same lines go to the terminal.

Every line written during a sync carries `tenantId` and `correlationId` (= the run id). The useful messages:

| Message                                                            | Level | Meaning                                          |
| ------------------------------------------------------------------ | ----- | ------------------------------------------------ |
| `Sync started, sources [...]`                                      | INFO  | Run begins, with the ordered sources             |
| `Attempt n/3 on <url> failed (...), retrying in … ms`              | WARN  | Retry of a source call (API key redacted)        |
| `Source <type> failed: …` / `Using fallback source <type>`         | WARN  | Fallback to the next source                      |
| `Mapping issue R<n> from <type>: …`                                | WARN  | A record dropped or corrected by a SPEC rule     |
| `Sync SUCCESS\|DEGRADED from <type>: n routes, n added, …`         | INFO  | Run finished and applied                         |
| `Sync REJECTED by the anti-wipe guard: …`                          | ERROR | Snapshot refused, stored network kept            |
| `Sync FAILED, stored network kept: …`                              | ERROR | All sources failed                               |
| `Tenant <id> disabled: …`                                          | ERROR | Invalid tenant configuration at startup          |

The full history of runs is also in the database: table `sync_run` (one row per run) and `route_change` (the change log).

## Test

```bash
./mvnw verify
```

151 tests: unit tests (adapters' mapping rules, diff, anti-wipe guard, HTTP retry policy, sync orchestration with the database down, staleness, ArchUnit isolation) and integration tests (real PostgreSQL via Testcontainers, external APIs simulated by WireMock: timeouts, 401/403, 429, 5xx, malformed JSON, HTML with 200, empty and truncated networks, fallback guard on covered types, startup sync, log correlation). Docker must be running.

## Configuration

Values come from environment variables, read by `docker compose` from a git-ignored `.env` file (template: `.env.example`).

| Variable              | Purpose                                                                    |
| --------------------- | -------------------------------------------------------------------------- |
| `POSTGRES_USER`       | DB user (container and app)                                                |
| `POSTGRES_PASSWORD`   | DB password                                                                |
| `POSTGRES_DB`         | DB name                                                                    |
| `POSTGRES_PORT`       | Host port PostgreSQL is published on                                       |
| `TENANT_A_URL`        | Tenant A Routes API (default: the local mock)                              |
| `BACKUP_PROVIDER_URL` | `generic-backup-provider` URL (default: the local mock)                    |
| `BACKUP_PROVIDER_KEY` | `generic-backup-provider` API key, sent as `api_key` (default: `demo-key`) |
| `TENANT_C_URL`        | Tenant C source (default: the local mock)                                  |
| `DATABASE_URI`        | Connection URI for the PostgreSQL MCP server (Claude Code), from Docker    |

Outside Docker (`./mvnw spring-boot:run`), the app reads `DB_URL`, `DB_USER`, `DB_PASSWORD` (default: a local `routesync` database) and the source URLs default to the mock on `localhost:8089`.

Sync settings (`caravelo.sync.*`: timeouts, retries, startup sync, scheduler) are in `src/main/resources/application.yaml`; defaults are the assumptions of `SPEC.md` §2.

## Add a new tenant

A tenant is a block of configuration under `caravelo.tenants` in `application.yaml`; secrets come from environment variables. What you need depends on the tenant's data format:

| Tenant's format                                             | What to add                                                                         |
| ----------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| A format already supported (e.g. `generic-backup-provider`) | Configuration only                                                                  |
| A new flat JSON format                                      | Configuration only, with the `json-mapping` adapter and field paths (SPEC §6.5)     |
| A complex format (nested data, custom rules)                | One adapter class in its own package `adapter/<type>`, plus its mapping test       |

Known format (configuration only):

```yaml
caravelo:
  tenants:
    tenant-d:
      airline-iata: XY
      sync-cron: '0 15 3 * * *'     # optional, default daily at 03:00
      guard-threshold: 0.5          # optional
      stale-after: 48h              # optional
      sources:                       # ordered: primary first, then fallbacks
        - adapter: generic-backup-provider
          url: ${BACKUP_PROVIDER_URL}
          api-key: ${BACKUP_PROVIDER_KEY}
```

New flat JSON format (Tenant C, configuration only):

```yaml
    tenant-c:
      airline-iata: XX
      sources:
        - adapter: json-mapping
          url: ${TENANT_C_URL}
          mapping:
            items: '$.data.routes[*]'   # path to the array of records
            origin: '$.from'            # paths inside one record
            destination: '$.to'
```

A new adapter class implements `core.service.RouteSourceAdapter` (`type()`, `coveredTypes()`, `fetch()`, optional `validate()`), is a Spring `@Component` in `adapter/<type>`, and uses `core.service.SourceHttpClient` for HTTP (retries and timeouts included). Nothing in the core changes; `ArchitectureTest` fails the build if the core depends on an adapter.

A tenant whose configuration is invalid (unknown adapter, bad mapping) is disabled at startup with a clear error log and `enabled: false` in `GET /api/tenants`; the other tenants run normally.

## API

| Endpoint                                                          | Purpose                                                                              |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `GET /api/tenants`                                                | Configured tenants, enabled or not, with their ordered sources                       |
| `GET /api/tenants/{tenantId}/routes?origin=&destination=&type=`   | Destinations grouped by origin; `type` is `DIRECT` or `CONNECTING`                   |
| `GET /api/tenants/{tenantId}/changes?limit=`                      | Change log (`ADDED`, `REMOVED`, `MODIFIED`), newest first                            |
| `POST /api/tenants/{tenantId}/sync`                               | Runs a sync now and returns the run (`409` if one is already running)               |
| `GET /api/tenants/{tenantId}/health`                              | Last successful sync, last status, source used, last error, error count, staleness   |
| `GET /actuator/health`                                            | Service health                                                                       |

Errors are `application/problem+json`; a bad query parameter is named in `parameter`. Exact shapes: `SPEC.md` §6.3.

## Debugging a tenant

1. `GET /api/tenants/{id}/health`: is it `stale`? What is `lastStatus`, `lastSourceUsed`, `lastError`?
2. Filter the logs on the run id to follow one sync across retries, fallback and persistence: `docker compose logs app | grep <runId>` (see [Logs](#logs)).
3. Mapping problems are logged as `Mapping issue R<n> …` with the SPEC rule that dropped or corrected the record.
4. `sync_run` holds every run (status, source, counts, error); `route_change` the change log.
