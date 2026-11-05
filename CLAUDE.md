# CLAUDE.md — Route Network Sync Service

Java 21 / Spring Boot 4 / PostgreSQL. Multi-tenant route network sync (see `SPEC.md`).

## Source of truth

- `SPEC.md` defines what to build. Read the relevant section before changing code.
- `docs/implementation-contract.md`: public Java API, config keys and tables shared by tests and code; update it with any signature change.
- A behaviour change goes into `SPEC.md` **first**, in its own commit, then the code.
- Design rationale lives in `DECISIONS.md`; update it when a design choice changes.
- Log notable AI mistakes and key prompts in `AI_WORKFLOW.md`.

## Architecture rules

- Core (sync, diff, persistence, API) depends only on the `RouteSourceAdapter` contract, never on an adapter package. ArchUnit enforces it (AC17).
- Adapters live in their own package under `adapter/<type>`, discovered by `type()`.
- No `if (tenant == …)` in the core: tenant variation is configuration or an adapter.
- Every stored row and every query is scoped by `tenantId`.
- A failed sync never touches stored data; a sync is applied in one transaction.
- Secrets come from environment variables only, never from committed config.

## Code rules

- Smallest change that fully solves the task. No dependency for a few lines.
- Prefer Spring built-ins (`logging.structured.format` for JSON logs, `RestClient`, scheduling). HTTP to sources goes through `core.SourceHttpClient` (retry, timeouts, key redaction).
- Schema is owned by Flyway migrations (`src/main/resources/db/migration`); persistence is plain JDBC (`JdbcClient`), no JPA.

## Tests

- Tests for a new AC are written first by the `test-designer` subagent (spec-only, never sees `src/main`), then the implementation makes them pass.
- Every acceptance criterion `ACn` in `SPEC.md` §5 has at least one test whose name or `@DisplayName` starts with `ACn`.
- Unit tests: JUnit 5 + Mockito. Integration: Testcontainers PostgreSQL + WireMock for external APIs.
- Run `./mvnw verify` and see it green before claiming a task done (needs Docker running).
- Before committing a feature, run the `spec-reviewer` subagent and address its blocking findings.

## Commands

- `./mvnw verify` — build + all tests
- `docker compose up --build` — PostgreSQL + service
