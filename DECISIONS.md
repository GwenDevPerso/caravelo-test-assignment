# DECISIONS

Why the service is built the way it is. The detailed rules are in `SPEC.md`; this file explains the choices and what they cost.

## 1. System design

One sync run per tenant, in five steps:

```
 tenant config ──► sources (ordered)
                     │
                     ▼
 ┌──────────┐   ┌───────────┐   ┌────────┐   ┌──────┐   ┌───────────────────┐
 │ adapter  │──►│ canonical │──►│ guard  │──►│ diff │──►│ persist (1 tx)    │
 │ fetch +  │   │ routes +  │   │anti-   │   │vs    │   │ routes + changes  │
 │ retry    │   │ issues    │   │wipe    │   │stored│   │ + sync run        │
 └──────────┘   └───────────┘   └────────┘   └──────┘   └───────────────────┘
      │ fails → next source (fallback)                          │
      └──────────── every outcome recorded as a sync run ◄──────┘
```

- **Adapters** (one per data format) fetch one source and map it to `CanonicalRoute(origin, destination, type, via)`. They know nothing about storage.
- **The core** (sync orchestration, guard, diff, persistence, API) knows only the `RouteSourceAdapter` contract. An ArchUnit test fails the build if the core imports an adapter.
- **The diff compares against what is stored**, not against the previous API response. Change detection then stays correct after failed, rejected or skipped runs.
- **Every run is persisted** (status, source used, counts, error). Health and the change log are queries on that table, with no separate monitoring state.
- **Every row and query carries `tenantId`.** Tenants share tables but never data.

## 2. Configure, extend or build new: per tenant

| Tenant                               | Source format                        | What onboarding takes                                                                              |
| ------------------------------------ | ------------------------------------ | -------------------------------------------------------------------------------------------------- |
| **A** (own Routes API)               | Nested stations, direct + connecting | **Extend**: one dedicated adapter, `tenant-a-stations`. Configure the backup provider as fallback. |
| **B** (no API)                       | `generic-backup-provider` flights    | **Configure**: reuse the generic adapter, filtered by `airline-iata`.                              |
| **C** (new flat JSON format)         | Invented JSON                        | **Configure**: `json-mapping` adapter with field paths in YAML (phase 2).                          |
| **Any tenant with a complex format** | Nested data, custom rules            | **Build new**: one adapter class + mapping test in its own package. No core change.                |
| **Any tenant on a known format**     | —                                    | **Configure**: one YAML block, secrets through env vars.                                           |

The rule: **nothing in the core changes for a tenant.** Building new is limited to an adapter, which could live in a separate jar.

**Why a dedicated adapter for A rather than `json-mapping`:** A's format nests routes per station and encodes direct/connecting status in two partly redundant lists (R9–R11). Expressing that in declarative paths would turn the mapping config into a small programming language. A small class with a unit test is clearer.

## 3. Resilience choices and trade-offs

| Choice                                                                                                                                     | Why                                                                                                                 | Trade-off accepted                                                                                                                                                                                                                          |
| ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **3 attempts with exponential backoff (~1 s, then ~2 s) + jitter, `Retry-After` honoured**, only for timeouts, connection errors, 5xx, 429 | Absorbs short glitches; jitter keeps tenants from retrying in lockstep                                              | About 3 s of waiting (plus timeouts) before falling back. A `401` or broken JSON is not retried: it would fail the same way.                                                                                                                |
| **Ordered fallback sources, no merge**                                                                                                     | The primary source is the truth; merging two sources that disagree invents routes nobody operates                   | When on fallback, the network is only as good as the fallback. The run is marked `DEGRADED` so the situation is visible.                                                                                                                    |
| **Coverage-limited diff (R12)**                                                                                                            | Source B has no connecting routes; without this, a fallback to B would "remove" every connecting route of Tenant A  | Connecting routes that really disappear during a fallback are only detected once the primary comes back.                                                                                                                                    |
| **Anti-wipe guard**: an empty snapshot, or one under 50 % of the stored routes **of the types the source covers**, is rejected             | A truncated or empty "successful" response is the most damaging failure: it would delete a customer's network       | A genuine large network cut is rejected too and needs a manual action (lower threshold for that tenant). Counting only covered types keeps the guard consistent with R12, so a fallback to B is not rejected for missing connecting routes. |
| **One transaction per sync**                                                                                                               | No half-applied network, ever                                                                                       | One large write per sync, which is fine at this size (thousands of routes).                                                                                                                                                                 |
| **All sources fail → keep serving the last good network**                                                                                  | Stale routes are better than none for a booking engine                                                              | Data can be stale; health exposes staleness (2 × sync interval) so it is noticed.                                                                                                                                                           |
| **Database down → run reported `FAILED`, never an HTTP 500**                                                                               | The caller and the logs always get a status; a run left `RUNNING` by a crash is marked `FAILED` at the next startup | If the database is down, the run itself cannot be recorded until the next startup clean-up.                                                                                                                                                 |
| **One sync per tenant at a time, in-process lock**                                                                                         | Prevents two runs racing on the same diff                                                                           | Single instance only. Running several instances needs a distributed lock (e.g. PostgreSQL advisory lock), documented as future work.                                                                                                        |
| **Pull, not push**                                                                                                                         | Airlines' APIs are pull-only in the brief                                                                           | Changes are seen at the next sync (≤ 6 h by default), or on manual trigger.                                                                                                                                                                 |

## 4. Technology choices

- **Spring Boot 4 built-ins over extra libraries**: Boot's `logging.structured.format` for JSON logs with MDC (`tenantId`, `correlationId`), JDK `HttpClient` behind Spring's `RestClient`, `@EnableScheduling` with one cron task per tenant. No Resilience4j, Logstash encoder or JSONPath library.
- **A 40-line retry loop instead of `@Retryable`** (`core.SourceHttpClient`): Spring Framework 7's retry support was the first choice, but honouring `Retry-After` on a 429 and classifying failures (timeout and 5xx retried, 4xx and malformed payloads not) per attempt was simpler and clearer as an explicit loop, unit-tested through WireMock. The API key is never logged (query strings are redacted).
- **Plain JDBC (`JdbcClient`) instead of JPA**: three tables, set-based writes (batch upsert/delete) and `TEXT[]` columns. Entities and dirty checking would add mapping code and hide the SQL that makes a sync idempotent (`INSERT … ON CONFLICT`). Flyway owns the schema; without an ORM there is no entity/schema validation at startup, so drift is caught by Flyway's checksum validation and by the integration tests running every query on a real PostgreSQL.
- **PostgreSQL**: primary key `(tenant_id, origin, destination)` makes duplicates impossible at the database level; `TEXT[]` holds `via`; change details are plain columns (old/new type and via), queryable without JSON functions.
- **`json-mapping` with a tiny path subset** (`$`, `.field`, `[n]`, final `[*]`): enough for flat formats like Tenant C; anything richer is a sign the format deserves its own adapter. Adapters gained an optional `validate(config)` so a bad mapping disables only that tenant at startup.
- **Scheduler skips, never queues**: a cron tick that finds a sync running (manual trigger) is logged and skipped; the next tick catches up.
- **Local demo with a mock**: `docker compose up` serves the sample payloads with WireMock, so the service is evaluable without real endpoints or keys; real URLs come from environment variables.
- **Testcontainers + WireMock**: a real PostgreSQL catches constraint and transaction bugs that H2 hides; WireMock reproduces timeouts, `401` and broken payloads deterministically.

## 5. Frontend (phase 2 bonus)

A minimal Vue app in `frontend/` to browse a tenant's network: what the brief asks ("routes per station") plus the change log and health, because those are what an engineer opens first when a tenant looks wrong.

| Choice                                                                                                                                                              | Why                                                                                                                                                                                                   | Trade-off accepted                                                                             |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| **API consumer only**, a separate app in `frontend/`                                                                                                                | The backend stays a REST service; the UI can be replaced (or dropped) without touching it. Built by a dedicated subagent against SPEC §6 while the backend was being written.                         | Two builds; not served by the Spring app or by Docker Compose (dev server only).               |
| **Vue 3.5 Composition API (`<script setup lang="ts">`), vue-router 5, Vite, TypeScript**                                                                            | Current stable stack; `vue-tsc` type-checks the §6.3 response shapes in `src/api/client.ts`, so a contract change breaks the build.                                                                   | —                                                                                              |
| **No state library (no Pinia), no HTTP library**                                                                                                                    | Each view reads its own data; a small `useAsyncData` composable (data / error / isLoading / reload) and one typed `fetch` wrapper are enough.                                                         | Data is refetched when you navigate back to a view; no client cache.                           |
| **No CSS framework, no UI library**                                                                                                                                 | A few screens do not justify one; the design is the author's own (gwenaelbihan.fr). Its tokens (colours, fonts, spacing, 0 radius) were extracted from that site's CSS and declared as CSS variables. | Every component is written by hand.                                                            |
| **Global CSS only in `App.vue`, `<style scoped>` everywhere else, only `var(--…)` values**                                                                          | Follows the official Vue style guide; one place to change the theme, and no style leaks between components.                                                                                           | Slightly more verbose components.                                                              |
| **Presentational components prefixed `Base*`** (`BaseButton`, `BaseCard`, `BaseStatRow`, `BaseTerminal`, `BaseTag`, `BaseField`…)                                   | Vue style guide naming; they take props, slots and events only, never call the API or the router, so views compose them and each visual pattern exists once.                                          | —                                                                                              |
| **Tenant and filters in the URL** (`/tenants/:id/routes?origin=ACA&type=DIRECT`)                                                                                    | Shareable links for support ("look at this"), browser back/forward work, and the filters match the API's query parameters one to one.                                                                 | —                                                                                              |
| **Errors shown where they belong**: a `400` with the `parameter` property of the problem+json goes on that field; a `409` on sync shows "a sync is already running" | The backend names the bad parameter (added to SPEC §6.2 for this), so the UI needs no client-side IATA validation duplicating R2/R3.                                                                  | The UI depends on the problem+json shape.                                                      |
| **Every view handles loading, error, empty and success**, with `aria-live` messages and visible focus                                                               | An empty network or a down backend is a normal state for an ops tool; accessibility is not optional.                                                                                                  | —                                                                                              |
| **Vite dev proxy `/api` → `localhost:8080`**                                                                                                                        | No CORS configuration in the backend just for a dev tool.                                                                                                                                             | A production deployment would need the frontend served behind the same origin (or CORS added). |
| **Tests with Vitest + Vue Test Utils**: the API client with a mocked `fetch`, each view in its four states with the API module mocked                               | Fast, no browser; checks the contract handling and the states, not pixels.                                                                                                                            | No end-to-end or visual test; the layout (incl. 360 px) was not checked in a real browser.     |

## 6. Rejected alternatives

- **Merge all sources into one network**: invents routes when sources disagree (R7).
- **Event bus / message queue for changes**: a persisted change log answers the brief; a bus adds infrastructure without a consumer yet.
- **One schema or database per tenant**: stronger isolation, but N migrations and connection pools. Row-level `tenantId` is enough at this scale.
- **Frontend with Nuxt / SSR, Pinia or a component library (Vuetify, PrimeVue, Tailwind)**: more build and dependencies than five read-mostly screens need, and a library's look would replace the chosen design.
- **Frontend in a separated repo**: for facilitating the test of the exercise
