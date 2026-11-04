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

| Tenant                         | Source format                         | What onboarding takes                                                                 |
| ------------------------------ | ------------------------------------- | ------------------------------------------------------------------------------------- |
| **A** (own Routes API)         | Nested stations, direct + connecting  | **Extend**: one dedicated adapter, `tenant-a-stations`. Configure the backup provider as fallback. |
| **B** (no API)                 | `generic-backup-provider` flights     | **Configure**: reuse the generic adapter, filtered by `airline-iata`.                 |
| **C** (new flat JSON format)   | Invented JSON                         | **Configure**: `json-mapping` adapter with field paths in YAML (phase 2).             |
| **Any tenant with a complex format** | Nested data, custom rules       | **Build new**: one adapter class + mapping test in its own package. No core change.   |
| **Any tenant on a known format** | —                                   | **Configure**: one YAML block, secrets through env vars.                              |

The rule: **nothing in the core changes for a tenant.** Building new is limited to an adapter, which could live in a separate jar.

**Why a dedicated adapter for A rather than `json-mapping`:** A's format nests routes per station and encodes direct/connecting status in two partly redundant lists (R9–R11). Expressing that in declarative paths would turn the mapping config into a small programming language. A small class with a unit test is clearer.

## 3. Resilience choices and trade-offs

| Choice | Why | Trade-off accepted |
| --- | --- | --- |
| **Retry 3× with exponential backoff (~1/2/4 s) + jitter**, only for timeouts, connection errors, 5xx, 429 | Absorbs short glitches; jitter keeps tenants from retrying in lockstep | Up to ~7 s before falling back. A `401` or broken JSON is not retried: it would fail the same way. |
| **Ordered fallback sources, no merge** | The primary source is the truth; merging two sources that disagree invents routes nobody operates | When on fallback, the network is only as good as the fallback. The run is marked `DEGRADED` so the situation is visible. |
| **Coverage-limited diff (R12)** | Source B has no connecting routes; without this, a fallback to B would "remove" every connecting route of Tenant A | Connecting routes that really disappear during a fallback are only detected once the primary comes back. |
| **Anti-wipe guard**: an empty snapshot, or one under 50 % of the stored routes **of the types the source covers**, is rejected | A truncated or empty "successful" response is the most damaging failure: it would delete a customer's network | A genuine large network cut is rejected too and needs a manual action (lower threshold for that tenant). Counting only covered types keeps the guard consistent with R12, so a fallback to B is not rejected for missing connecting routes. |
| **One transaction per sync** | No half-applied network, ever | One large write per sync, which is fine at this size (thousands of routes). |
| **All sources fail → keep serving the last good network** | Stale routes are better than none for a booking engine | Data can be stale; health exposes staleness (2 × sync interval) so it is noticed. |
| **One sync per tenant at a time, in-process lock** | Prevents two runs racing on the same diff | Single instance only. Running several instances needs a distributed lock (e.g. PostgreSQL advisory lock), documented as future work. |
| **Pull, not push** | Airlines' APIs are pull-only in the brief | Changes are seen at the next sync (≤ 6 h by default), or on manual trigger. |

## 4. Technology choices

- **Spring Boot 4 built-ins over extra libraries**: `@Retryable` from Spring Framework 7 for retries, Boot's `logging.structured.format` for JSON logs with MDC (`tenantId`, `correlationId`). Resilience4j or a Logstash encoder would add dependencies for features already in the platform.
- **PostgreSQL + Flyway**: unique `(tenant_id, origin, destination)` makes duplicates impossible at the database level; `TEXT[]` holds `via`; `JSONB` holds change details. Flyway owns the schema, Hibernate only validates it.
- **Testcontainers + WireMock**: a real PostgreSQL catches constraint and transaction bugs that H2 hides; WireMock reproduces timeouts, `401` and broken payloads deterministically.

## 5. Rejected alternatives

- **Merge all sources into one network**: invents routes when sources disagree (R7).
- **Event bus / message queue for changes**: a persisted change log answers the brief; a bus adds infrastructure without a consumer yet.
- **One schema or database per tenant**: stronger isolation, but N migrations and connection pools. Row-level `tenantId` is enough at this scale.
- **Diff against the previous API response**: breaks as soon as one run fails or is rejected.
