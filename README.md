# Route Network Sync Service

Keeps each airline tenant's route network in sync with the Caravelo platform. For each tenant it fetches routes from the tenant's configured sources, normalises them, stores them in PostgreSQL, records added, removed and modified routes, and serves them over REST.

- What it does and the acceptance criteria: [`SPEC.md`](SPEC.md)
- Design and trade-offs: [`DECISIONS.md`](DECISIONS.md)
- How it was built with AI: [`AI_WORKFLOW.md`](AI_WORKFLOW.md)

> **Status:** project skeleton only (Spring Boot 4.1.2, PostgreSQL, Docker). Sync, adapters and the API are specified but not implemented yet. Sections marked *planned* describe the specified behaviour.

## Prerequisites

- Docker with Compose v2 (running).
- For local development only: JDK 21 or newer. Maven is not needed; the wrapper `./mvnw` downloads it.

## Run

```bash
docker compose up --build
```

This starts PostgreSQL and the service on `http://localhost:8080`.

```bash
curl localhost:8080/actuator/health   # {"status":"UP"}
```

## Test

```bash
./mvnw verify
```

Integration tests start PostgreSQL with Testcontainers, so Docker must be running.

## Configuration

Values come from environment variables. Defaults suit local development.

| Variable                | Default                                      | Purpose                                         |
| ----------------------- | -------------------------------------------- | ----------------------------------------------- |
| `DB_URL`                | `jdbc:postgresql://localhost:5432/routesync` | JDBC URL                                        |
| `DB_USER`               | `routesync`                                  | DB user                                         |
| `DB_PASSWORD`           | `routesync`                                  | DB password                                     |
| `TENANT_A_URL`          | —                                            | Tenant A Routes API (*planned*)                 |
| `BACKUP_PROVIDER_URL`   | —                                            | `generic-backup-provider` base URL (*planned*)  |
| `BACKUP_PROVIDER_KEY`   | —                                            | `generic-backup-provider` API key (*planned*)   |

Never commit secrets. Put local values in a `.env` file next to `docker-compose.yml`; `.env` must stay out of git.

## Add a new tenant (*planned*)

A tenant is a block of configuration under `caravelo.tenants` in `application.yaml`. What you need depends on the tenant's data format:

| Tenant's format                                                 | What to add                                                          |
| --------------------------------------------------------------- | -------------------------------------------------------------------- |
| A format already supported (e.g. `generic-backup-provider`)     | Configuration only                                                   |
| A new flat JSON format                                          | Configuration only, with the `json-mapping` adapter and field paths  |
| A complex format (nested data, custom rules)                    | One adapter class in its own package, plus its mapping test          |

Example (configuration only, on the generic provider):

```yaml
caravelo:
  tenants:
    tenant-d:
      airline-iata: XY
      sync-cron: '0 15 */6 * * *'   # optional, default every 6 h
      sources:                       # ordered: primary first, then fallbacks
        - adapter: generic-backup-provider
          url: ${BACKUP_PROVIDER_URL}
          api-key: ${BACKUP_PROVIDER_KEY}
```

If a tenant's config names an unknown adapter, that tenant is disabled at startup and the other tenants keep running. See `SPEC.md` §2 for the full model and the `json-mapping` example.

## API (*planned*)

| Endpoint                                  | Purpose                                                                                       |
| ----------------------------------------- | --------------------------------------------------------------------------------------------- |
| `GET /api/tenants/{tenantId}/routes`      | Destinations grouped by origin. Filters: `origin`, `destination`, `type` (`DIRECT`/`CONNECTING`) |
| `GET /api/tenants/{tenantId}/changes`     | Change log (`ADDED`, `REMOVED`, `MODIFIED`) per sync run                                      |
| `POST /api/tenants/{tenantId}/sync`       | Trigger a sync manually (`409` if one is already running)                                     |
| `GET /api/tenants/{tenantId}/health`      | Last successful sync, last status, source used, error count, staleness                        |
| `GET /actuator/health`                    | Service health (available now)                                                                |

The changes, sync and health paths are proposals; `SPEC.md` does not fix them yet.
