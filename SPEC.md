# SPEC — Route Network Sync Service

> Status: **v1** — written before any implementation commit (Spec-Driven Development).
> Any behaviour change during implementation is reflected here first.

---

## 1. Scope

### Goal

Keep **each airline customer's (tenant's) route network** accurate in the Caravelo platform, so the subscriber booking engine only offers routes the airline actually operates.

For every tenant, the service:

1. **fetches** the route network from the tenant's configured source(s);
2. **normalises** it into one canonical model;
3. **stores** it in PostgreSQL;
4. **detects and records changes** (routes added, removed, modified) on every sync;
5. **exposes** routes (filterable by station) and changes through a REST API;
6. stays **resilient** (retries, fallback, no data loss) and **observable** (structured logs with `tenantId` + `correlationId`, per-tenant health).

### In scope

- Java 21 / Spring Boot 4 REST service, PostgreSQL, `docker compose up` runs everything.
- Any number of tenants (**N tenants**), each defined by configuration.
- Two source types for this exercise: a tenant-specific API (Tenant A) and the `generic-backup-provider` (Tenant B, and fallback for others).
- Sync triggered manually per tenant (REST endpoint) and once at startup. Automatic scheduling is a bonus (phase 2).
- Unit and integration tests (JUnit 5, Mockito, Testcontainers, WireMock).

### Out of scope

- Schedules, flight numbers, times, prices, availability: only _which_ `origin → destination` pairs exist.
- Station metadata (names, countries, coordinates).
- Authentication of our own API (assumed behind the platform gateway).
- Running several instances in parallel (single instance assumed; distributed locking documented as future work).
- Push / webhook ingestion (pull only).

### Phases

The brief asks for about 4–6 hours. The work is split so that every mandatory requirement is delivered first; bonuses come in a second phase.

**Phase 1 — mandatory requirements**

- Tenant A (own API) and Tenant B (`generic-backup-provider`), with fallback.
- Mapping, storage, routes and changes endpoints, manual sync trigger.
- Retry, fallback, idempotency, anti-wipe guard, structured logs, per-tenant health.
- Unit and integration tests for every failure scenario of the brief.
- Docker Compose, README, DECISIONS.md, AI_WORKFLOW.md.

**Phase 2 — bonuses (implemented after phase 1 is complete)**

- **Scheduler**: automatic syncs per tenant using `sync-cron` (the configuration is already designed in §2).
- **Tenant C**: a tenant with an invented JSON format, onboarded through the generic `json-mapping` adapter, i.e. configuration only. The design is already specified (§2 onboarding levels and example config) so that phase 1 does not block it; it is implemented in phase 2 to prove how cheap onboarding is.
- **Minimal Vue view** listing routes per station.

Phase 1 must not depend on anything from phase 2.

### Assumptions

The brief invites candidates to state their assumptions. These were made where the brief or the data left a choice open.

| #   | Assumption                                                                                                                                              | Reason / impact                                                                                                                                                            |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A1  | Both example sources describe the **same airline, Volaris (`Y4` / `VOI`)**, although the `generic-backup-provider` URL mentions `W6` (Wizz Air's code). | The station codes (ACA, GDL, MEX, TIJ…) and the `airline_iata` field of the records point to Volaris. Tenants filter on the code found in the data (`Y4`), not on the URL. |
| A2  | Tenant A and Tenant B are configured on the same airline data for this exercise.                                                                        | The brief says both endpoints describe the same client, to ease the mapping.                                                                                               |
| A3  | Routes are **directional**: `MEX→BOG` and `BOG→MEX` are two routes. No return route is inferred.                                                        | Some routes may only be operated in one direction; inventing the return would offer non-existent flights.                                                                  |
| A4  | **Connecting routes** (one stop) are stored and exposed, filterable with `type=DIRECT`.                                                                 | Source A provides them and a subscriber may book an itinerary with a stop. Whether the IBE offers them is a product decision, left to a filter.                            |
| A5  | When a tenant falls back to a source that covers fewer route types, routes it cannot see are kept (R12).                                                | A fallback must not create false removals.                                                                                                                                 |
| A6  | Only `(origin, destination)` pairs matter; schedules, times, prices and station metadata are out of scope.                                              | The IBE needs to know which routes exist; schedules belong to other systems.                                                                                               |
| A7  | A single service instance runs at a time.                                                                                                               | Simplest for the exercise; a multi-instance deployment would need a distributed lock (documented as future work).                                                          |
| A8  | The example API key in the `generic-backup-provider` URL is not a real secret, but keys are still injected through environment variables. The only committed defaults are local demo values (`demo-key` for the mock, `routesync` for a local database). | The brief forbids real secrets in the repository.                                                                                                                          |
| A9  | Default values (sync interval, retries, timeouts, guard threshold) are initial choices, not requirements.                                               | The brief only says syncs are daily (phase 2 scheduler); it gives no other figures. See §2 _Default values and assumptions_.                                                                                                       |
| A10 | The provided endpoints are Google Apps Script URLs, which may answer with a redirect, or with an HTML page and status 200 on error.                     | Redirects are followed; any non-JSON body is treated as a malformed response.                                                                                              |

### Brief → design decisions

**1. Brief:** _"design and build a small route network sync service (or agent) that integrates with each airline's route data, keeps it up to date in our platform and detects any change in their network (new or removed routes)"_

- **Solution:** Pull-based sync per tenant (manual trigger in phase 1, scheduled in phase 2), stored network as the reference, diff on every run.
- **Why:** The IBE must never sell a route that no longer exists. Comparing against what is **stored** (not the previous API response) makes change detection reliable even after failed or skipped runs.

**2. Brief:** _"Design and build a Java-based REST service that fetches, normalises and stores each customer's route network data"_

- **Solution:** Three distinct steps: adapter fetch → canonical model → persistence.
- **Why:** Separating fetch/normalise/store lets each step be tested alone and keeps format-specific code out of storage and API.

**3. Brief:** _"stays resilient and observable when external systems misbehave"_

- **Solution:** Failures never touch stored data; every run is recorded with its status.
- **Why:** External APIs are outside our control; the platform must keep serving the last good network and make problems visible.

**4. Brief:** _"Process and store the data in a local relational database (PostgreSQL or MySQL preferred)"_

- **Solution:** PostgreSQL.
- **Why:** Preferred by the brief; unique constraints and transactions give idempotency and atomic syncs; `TEXT[]` / `JSONB` fit `via` stations and change details.

**5. Brief:** _"Expose an endpoint to GET the routes and be able to filter by station. For example, for every origin station, have the array of possible destinations"_

- **Solution:** Routes returned grouped by origin (`{ "ACA": ["GDL", …] }`), filters by origin, destination and type.
- **Why:** Matches the example literally and is the shape a booking engine needs to build an origin → destination picker.

**6. Brief:** _"On each sync, detect and record network changes (added or removed routes) and expose them, for example as an endpoint or an event log"_

- **Solution:** Persistent change log (`ADDED`, `REMOVED`, `MODIFIED`) linked to the sync run, exposed by an endpoint.
- **Why:** A persisted log survives restarts and lets support answer "when did this route disappear, and from which source?". `MODIFIED` is added because Source A distinguishes direct and connecting routes.

**7. Brief:** _"Containerisation: Docker + docker-compose, so we can run everything with one command"_

- **Solution:** `docker compose up` starts DB + service and runs a first sync.
- **Why:** Reviewers can evaluate without any local setup.

### Glossary

| Term         | Meaning                                                                         |
| ------------ | ------------------------------------------------------------------------------- |
| **Tenant**   | An airline customer of Caravelo.                                                |
| **Station**  | An airport, identified by its IATA code (e.g. `MEX`).                           |
| **Route**    | A directed pair `origin → destination`. `MEX→BOG` and `BOG→MEX` are two routes. |
| **Network**  | All active routes of a tenant at a point in time.                               |
| **Adapter**  | A connector that fetches one source format and maps it to the canonical model.  |
| **Sync run** | One fetch → normalise → diff → persist execution for one tenant.                |

---

## 2. Tenant model

### Principle

**Adding a tenant must never change the core.** A tenant is configuration; a new data _format_ is at most one isolated adapter.

| Onboarding case                                                                         | What is needed                                                                                                                                |
| --------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| New tenant using a known format (e.g. another airline on the `generic-backup-provider`) | **Configuration only** (one YAML block / env vars)                                                                                            |
| New tenant with a simple new JSON format                                                | **Configuration only**, using the generic declarative adapter `json-mapping` (field paths in config). Used for Tenant C — **bonus, phase 2**. |
| New tenant with a complex format (nested, custom rules)                                 | **One new adapter class** + its mapping test, in its own package                                                                              |

### Brief → design decisions

**1. Brief:** _"Caravelo has one platform, but no two airline implementations are exactly the same"_

- **Solution:** One core, per-tenant configuration and adapters.
- **Why:** Variation between airlines is expected and permanent: it must live at the edges, not as `if (tenant == …)` branches in the core.

**2. Brief:** _"Tenant A exposes its own Routes API, with a format specific to that customer"_

- **Solution:** Dedicated `tenant-a-stations` adapter.
- **Why:** The nested station/connection format with direct/connecting routes is too specific for generic config; one small isolated class is clearer and fully testable.

**3. Brief:** _"Tenant B has no API of its own, so we use a generic backup provider as a fallback (AirLabs-like)"_

- **Solution:** Reusable `generic-backup-provider` adapter, selected by config, filtered by `airline-iata`.
- **Why:** The provider is shared by many airlines: one adapter serves every tenant that needs it, the airline code being just a parameter.

**4. Brief:** _"Fetch customer network data from their own Routes API"_ / _"If the customer does not have an API, fetch data from a generic backup service"_

- **Solution:** Ordered `sources` list per tenant: primary first, then fallbacks.
- **Why:** The same mechanism covers both cases: a tenant without API simply has the generic provider as primary; a tenant with API gets it as fallback. No special case in the core.

**5. Brief:** _"Tenant C is coming next month with yet another format. Your design should make onboarding it a matter of configuration or a small isolated adapter, never a change to the core"_

- **Solution:** Three onboarding levels: config only (known format), config only via `json-mapping` (simple new format), one adapter class (complex format). Tenant C itself is implemented as a bonus in phase 2.
- **Why:** Most new formats are flat JSON: a declarative adapter makes them pure configuration. The adapter class remains the escape hatch for complex cases. N tenants scale without touching the core.

**6. Brief:** _"tenant-specific logic (connectors, field mappings, business rules) must stay isolated from the core, as if it lived in a customer plugin"_

- **Solution:** Single contract `RouteSourceAdapter`; adapters in their own packages, discovered by `type()`; ArchUnit test forbids core → adapter dependencies.
- **Why:** The isolation is not only a convention: an automated test fails the build if anyone breaks it. Each adapter could be moved to a separate module/jar with no core change.

**7. Brief:** _"Onboarding a new customer should only need configuration or a small adapter, never a change to the core service"_

- **Solution:** Tenants are YAML entries; secrets via env vars; unknown adapter disables only that tenant.
- **Why:** Onboarding is a deploy of configuration, and a bad config for one customer cannot break the others.

**8. Brief:** _"We work in a multitenant environment"_

- **Solution:** Every row and query scoped by `tenantId`; one sync at a time per tenant; tenants run independently.
- **Why:** Prevents data leaks between customers and ensures one failing airline never blocks the rest.

### Configuration

```yaml
caravelo:
    tenants:
        tenant-a:
            airline-iata: Y4
            sync-cron: '0 0 3 * * *'
            sources: # ordered: primary first, then fallbacks
                - adapter: tenant-a-stations
                  url: ${TENANT_A_URL}
                - adapter: generic-backup-provider
                  url: ${BACKUP_PROVIDER_URL}
                  api-key: ${BACKUP_PROVIDER_KEY}
        tenant-b:
            airline-iata: Y4
            sync-cron: '0 30 3 * * *'
            sources:
                - adapter: generic-backup-provider
                  url: ${BACKUP_PROVIDER_URL}
                  api-key: ${BACKUP_PROVIDER_KEY}
        tenant-c: # bonus, phase 2: invented format, config-only onboarding
            airline-iata: XX # no sync-cron → default daily at 03:00
            sources:
                - adapter: json-mapping
                  url: ${TENANT_C_URL}
                  mapping:
                      items: '$.data.routes[*]'
                      origin: '$.from'
                      destination: '$.to'
```

### Default values and assumptions

Apart from "daily syncs" (phase 2 bonus), the brief gives no figures for sync frequency, retries or thresholds. The values below are **initial assumptions**, not requirements: they are configurable per tenant and meant to be tuned with real traffic.

#### `sync-cron` — how often a tenant is synced

> Used by the scheduler, a bonus implemented in phase 2. In phase 1, syncs run at startup and on demand.

Spring cron expressions have **6 fields**, seconds first:

```
second  minute  hour  day-of-month  month  day-of-week
  0       0      3         *          *          *
```

| Field        | Value | Meaning                                              |
| ------------ | ----- | ---------------------------------------------------- |
| second       | `0`   | at second 0                                          |
| minute       | `0`   | at minute 0                                          |
| hour         | `3`   | at 03:00                                             |
| day-of-month | `*`   | every day of the month                               |
| month        | `*`   | every month                                          |
| day-of-week  | `*`   | every day of the week                                |

- `0 0 3 * * *` (Tenant A) → runs **every day at 03:00**.
- `0 30 3 * * *` (Tenant B) → runs **every day at 03:30**.
- Times are in the service's time zone (UTC by default in the container).
- A tenant without `sync-cron` uses the default `0 0 3 * * *` (daily at 03:00).
- A sync can always be triggered manually, independently of the cron.

#### Why these values

| Setting                | Default                      | Reasoning                                                                                                                                                                         |
| ---------------------- | ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Sync interval          | **daily** (03:00)            | The brief asks for daily syncs; a route network changes rarely (a few times a month), so a change is seen by the next morning. 03:00 is a low-traffic hour for the airlines' APIs. A manual sync covers urgent changes.                            |
| Offset between tenants | **30 min**                   | Avoids all tenants calling the APIs at the same moment, especially when several use the same `generic-backup-provider`.                                                           |
| Retry attempts         | **3**                        | Enough to absorb a short glitch (network blip, restart) without delaying the fallback too long.                                                                                   |
| Backoff                | **~1 s, then 2 s** + jitter  | Exponential, as required by the brief: gives a struggling provider time to recover. Jitter (±20 %) spreads retries of different tenants. 3 attempts = 2 waits: worst case ≈ 3 s of waiting (plus timeouts) before falling back. |
| Connect timeout        | **5 s**                      | A healthy API accepts a connection in milliseconds; 5 s means it is unreachable.                                                                                                  |
| Read timeout           | **10 s**                     | A full network payload can be large (Source A returns all stations with all connections); 10 s leaves room while still failing fast.                                              |
| Anti-wipe guard        | **50 %** of stored routes of covered types | No airline loses half of its network between two syncs; such a drop is far more likely a broken or truncated response.                                                            |
| Stale threshold        | **2 × sync interval** (48 h) | Tolerates one failed run (temporary outage) but raises the alarm on the second.                                                                                                   |

### Plugin contract

```java
public interface RouteSourceAdapter {
    String type();                                   // "generic-backup-provider", "tenant-a-stations", "json-mapping", ...
    Set<RouteType> coveredTypes();                   // which route types this source can see
    SourceResult fetch(TenantSourceConfig config);   // fetch + map to canonical routes
    default void validate(TenantSourceConfig config) {}   // startup check of the tenant's config (added for §6.5)
}

public record CanonicalRoute(String origin, String destination, RouteType type, Set<String> via) {}
public enum RouteType { DIRECT, CONNECTING }

public record SourceResult(String sourceType, Set<CanonicalRoute> routes, List<MappingIssue> issues) {}
```

### Rules

- Adapters are discovered by `type()`; the core resolves them from the tenant config.
- The core (sync, diff, persistence, API) depends only on the contract above, never on an adapter.
- Tenant data is isolated: every stored row and every query is scoped by `tenantId`.
- A tenant's failure never blocks or affects another tenant's sync.
- Secrets (API keys) come from environment variables, never from committed config.

---

## 3. Data mapping

Both example sources describe the **same airline: Volaris (`Y4` / `VOI`)**, and are expected not to agree completely.

### Brief → design decisions

**1. Brief:** _"the previously shared endpoints have similar information of the same client to ease your understanding and field mapping"_

- **Solution:** Both sources mapped to one canonical model; the shared airline (Volaris, `Y4`) is used to cross-check them.
- **Why:** A single canonical model is what makes sources interchangeable (primary/fallback) and keeps the API independent of any source format.

**2. Brief:** _"Expect them not to agree completely: document your field mapping and how you resolved the inconsistencies"_

- **Solution:** Field-by-field mapping tables (3.2, 3.3) and numbered resolution rules R1–R13 (3.4); observed differences listed in `docs/mapping-analysis.md`.
- **Why:** Every rule is explicit, testable and referenced in logs (`MappingIssue`), so a support engineer can see _why_ a record was dropped.

**3. Brief:** _"fetches, normalises"_

- **Solution:** Canonical route = `origin`, `destination`, `type`, `via`.
- **Why:** Keeps only what the IBE needs. `type`/`via` are kept because Source A carries them and a subscriber may book connecting itineraries; everything else (times, terminals, flight numbers) is out of scope.

**4. Brief:** Two sources disagree on coverage (A: direct + connecting, B: direct only)

- **Solution:** No merge; primary source is the truth; diff limited to the types the used source covers (R12).
- **Why:** Merging two inconsistent sources creates routes nobody operates. Limiting the diff prevents a fallback to B from "removing" every connecting route.

### 3.1 Canonical model

| Field         | Rules                                                               |
| ------------- | ------------------------------------------------------------------- |
| `origin`      | IATA code, trimmed, upper-case, `^[A-Z]{3}$`                        |
| `destination` | same rules, `≠ origin`                                              |
| `type`        | `DIRECT` (non-stop) or `CONNECTING` (bookable with a stop)          |
| `via`         | possible connection stations (valid IATA codes); empty for `DIRECT` |

`(tenant, origin, destination)` is unique. If a pair exists both direct and via a connection, it is `DIRECT`.

### 3.2 Source A — tenant Routes API (`GetStation`)

One item per **departure station**, with its routes nested.

| Source field                                                                                                                                                         | Canonical                      | Transformation                                            |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------ | --------------------------------------------------------- |
| `stationCode`                                                                                                                                                        | `origin`                       | normalise, validate                                       |
| `route.direct[].arrivalStation`                                                                                                                                      | `destination`, `type = DIRECT` | one route per element                                     |
| `route.connection[].arrivalStation`                                                                                                                                  | `destination`                  | one route per element                                     |
| `route.connection[].routeDirect`                                                                                                                                     | `type`                         | `1` → `DIRECT`, `0` → `CONNECTING`                        |
| `route.connection[].connectionStation`                                                                                                                               | `via`                          | `"GDL,MEX,TIJ"` → split, trim, validate, de-duplicate     |
| `route.*[].routeName`                                                                                                                                                | —                              | consistency check only: must equal `origin + destination` |
| `stationName`, `country`, `alias`, `Terminal`, `globalPosition`, `mac`, `bestDayID`, `isNew`, `isPrincipal`, `exclusiveCarrier`, `stationLocalTime*`, `elapsedTimes` | —                              | ignored                                                   |

Example `ACA`: 3 direct (`GDL`, `MEX`, `TIJ`) + 55 connecting = 58 routes.
Covered types: `DIRECT`, `CONNECTING`.

### 3.3 Source B — `generic-backup-provider` (`routes`)

One item per **scheduled flight**.

| Source field                                                                            | Canonical     | Transformation                                                               |
| --------------------------------------------------------------------------------------- | ------------- | ---------------------------------------------------------------------------- |
| `dep_iata`                                                                              | `origin`      | normalise, validate                                                          |
| `arr_iata`                                                                              | `destination` | normalise, validate                                                          |
| —                                                                                       | `type`        | always `DIRECT` (a flight record is non-stop)                                |
| `airline_iata`                                                                          | —             | filter: must equal the tenant's `airline-iata` (`Y4`), compared after trim + upper-case (R2)                        |
| `cs_airline_iata`, `cs_flight_iata`, `cs_flight_number`                                 | —             | if set, the record is a codeshare marketed under another flight: ignored     |
| `days`                                                                                  | —             | empty or missing → flight not operating → ignored                            |
| `dep_icao`, `arr_icao`                                                                  | —             | fallback only if `*_iata` is missing (not resolved: record rejected, see R3) |
| `flight_*`, `*_time*`, `*_terminals`, `duration`, `aircraft_icao`, `counter`, `updated` | —             | ignored                                                                      |

Covered types: `DIRECT` only.

### 3.4 Inconsistency resolution rules

| #   | Inconsistency                                                                                                    | Resolution                                                                                                                                                                                         |
| --- | ---------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R1  | Same pair appears several times (several flights per day, both directions listed per flight)                     | De-duplicate on `(origin, destination)`. In Source A a duplicate inside one list is unexpected: one `MappingIssue` per extra copy, `via` sets of duplicate connecting entries are unioned. In Source B several flights per pair are normal: no issue.                                                                                                                                                           |
| R2  | Lower-case codes, surrounding spaces                                                                             | Trim + upper-case.                                                                                                                                                                                 |
| R3  | Code null, empty or not `^[A-Z]{3}$`                                                                             | Record rejected, `MappingIssue` logged, sync continues.                                                                                                                                            |
| R4  | `origin == destination`                                                                                          | Rejected, `MappingIssue` logged.                                                                                                                                                                                          |
| R5  | Source B record for another airline                                                                              | Ignored.                                                                                                                                                                                           |
| R6  | Route present in one direction only                                                                              | Kept as-is; return route never inferred.                                                                                                                                                           |
| R7  | A and B disagree on the network                                                                                  | **No merge.** The tenant's primary source is the source of truth; the fallback is used only when the primary fails. Differences found in the sample data are listed in `docs/mapping-analysis.md`. |
| R8  | Station with no routes                                                                                           | Nothing stored.                                                                                                                                                                                    |
| R9  | Source A lists direct routes twice (`direct[]` and `connection[]` with `routeDirect = 1`) and the lists disagree | Union; the route is `DIRECT`; `MappingIssue` logged. A station with no `connection[routeDirect = 1]` entry at all only uses `direct[]`: nothing to compare, no issue.                                                                                                                                               |
| R10 | `routeName` ≠ `stationCode + arrivalStation`                                                                     | Codes win; `MappingIssue` logged.                                                                                                                                                                  |
| R11 | `connectionStation` empty, invalid, or contains origin/destination                                               | Bad codes dropped from `via`; route kept; `MappingIssue` logged.                                                                                                                                   |
| R12 | Fallback source covers fewer route types than the primary (B sees no connecting routes)                          | The diff only compares types covered by the source used: a fallback to B never removes `CONNECTING` routes.                                                                                        |
| R13 | Codeshare duplicates in Source B                                                                                 | Ignored (R1 would also collapse them).                                                                                                                                                             |

---

## 4. Failure scenarios

### Brief → design decisions

**1. Brief:** _"Implement retry logic with exponential backoff for external API failures"_

- **Solution:** 3 attempts, ~1 s then ~2 s with jitter; only transient errors retried.
- **Why:** Backoff gives a struggling provider time to recover; jitter avoids all tenants retrying at the same instant; retrying a `401` or a broken JSON is useless and only delays the fallback.

**2. Brief:** _"plus a sensible fallback or degradation strategy"_

- **Solution:** Ordered fallback sources; `DEGRADED` status; if everything fails, last known good network keeps being served.
- **Why:** Serving slightly stale routes is far better than serving none. The status makes the degradation visible instead of silent.

**3. Brief:** _"Make syncs idempotent: re-running a sync must not corrupt or duplicate data"_

- **Solution:** Set-based diff against stored data, unique `(tenant, origin, destination)`, single transaction, one sync per tenant at a time.
- **Why:** Re-running the same input mathematically yields an empty diff; the transaction guarantees no half-applied sync; the lock prevents two runs racing.

**4. Brief:** _"Provide structured logging with correlation and tenant IDs"_

- **Solution:** JSON logs; `tenantId` + `correlationId` (= sync run id) in MDC on every line.
- **Why:** One id lets an engineer grep the full story of one sync across retries, fallback and persistence.

**5. Brief:** _"plus basic health signals (e.g. last successful sync and error count per tenant) that an engineer other than you could use to debug a production issue"_

- **Solution:** Every run persisted (status, source used, counts, error); per-tenant health with last success, last status, error count, staleness.
- **Why:** Answers the first on-call questions without reading code: _is it stale? since when? which source? what error?_

**6. Brief:** _"Cover failure scenarios: API timeout, authentication failure, malformed or partial responses, provider unavailable"_

- **Solution:** Scenario matrix 4.4, one row per case, each with a defined behaviour and final status.
- **Why:** Each brief scenario has an explicit, testable outcome. Extra rows (empty response, DB down, one tenant failing) cover the other ways a network could be silently corrupted.

**7. Brief:** (implicit) A broken API returning an empty or truncated list

- **Solution:** Anti-wipe guard (empty or < 50 % of stored routes of the types the source covers → `REJECTED`).
- **Why:** Without it, a "successful" but empty response would delete a customer's whole network — the most damaging failure for an IBE.

### 4.1 Retry policy (per source call)

- Up to 3 attempts, exponential backoff with jitter (~1 s before the 2nd, ~2 s before the 3rd); a `Retry-After` in seconds replaces the backoff (capped at 30 s).
- Timeouts: connect 5 s, read 10 s.
- **Retried**: timeout, connection error, HTTP 5xx, HTTP 429 (honour `Retry-After`).
- **Not retried**: HTTP 401/403, 400/404, malformed payload — deterministic, retrying is useless.

### 4.2 Fallback and degradation

- Primary exhausted or non-retryable error → next configured source.
- Data from a fallback → run `DEGRADED` (persisted, source recorded).
- All sources fail → run `FAILED`; **stored network untouched** (last known good data keeps being served).

### 4.3 Data protection

- A sync is applied in **one transaction**; any error rolls back everything.
- **Idempotent**: same input ⇒ 0 changes, no duplicates.
- **Anti-wipe guard**: an empty snapshot, or one smaller than 50 % of the stored routes **of the types the source covers** (configurable per tenant), is not applied → run `REJECTED`. Counting only covered types keeps the guard consistent with R12: a fallback to a direct-only source is compared with stored direct routes only.
- Only one sync per tenant at a time; a concurrent trigger is refused.

### 4.4 Scenario matrix

| Scenario                                            | Behaviour                                                        | Final status                       |
| --------------------------------------------------- | ---------------------------------------------------------------- | ---------------------------------- |
| API timeout                                         | retry ×3, then fallback                                          | `DEGRADED` / `FAILED`              |
| Authentication failure (401/403)                    | no retry, error logged, fallback                                 | `DEGRADED` / `FAILED`              |
| Provider unavailable (5xx, connection refused)      | retry ×3, then fallback                                          | `DEGRADED` / `FAILED`              |
| Rate limited (429)                                  | retry with backoff                                               | depends on outcome                 |
| Malformed response (invalid JSON, unexpected shape) | no retry, fallback                                               | `DEGRADED` / `FAILED`              |
| Partial response (some invalid records)             | invalid records skipped and logged, valid ones kept, then guard  | `SUCCESS` with issues / `REJECTED` |
| Empty or truncated network                          | guard blocks it                                                  | `REJECTED`                         |
| Database unavailable                                | rollback, error logged                                           | `FAILED`                           |
| Unknown adapter type in config                      | tenant disabled at startup with a clear error; other tenants run | —                                  |
| One tenant failing                                  | other tenants unaffected                                         | —                                  |

Every failure is logged with `tenantId`, `correlationId` and source, and is visible in the tenant's health (last successful sync, last status, error count).

---

## 5. Acceptance criteria

Each criterion is covered by at least one automated test.

### Brief → design decisions

**1. Brief:** _"Write unit and integration tests (JUnit, Mockito, Testcontainers) for fetching, mapping, persistence and change detection"_

- **Solution:** Unit tests per adapter and for the diff engine (JUnit, Mockito); integration tests on a real PostgreSQL (Testcontainers) with external APIs simulated by WireMock.
- **Why:** A real database catches what an in-memory one hides (constraints, transactions, arrays); WireMock reproduces timeouts, `401`, broken JSON deterministically.

**2. Brief:** _"Cover failure scenarios: API timeout, authentication failure, malformed or partial responses, provider unavailable"_

- **Solution:** AC7–AC12.
- **Why:** One criterion per failure family, asserting both the behaviour (retries, fallback) and that stored data is preserved.

**3. Brief:** _"Onboarding a new customer should only need configuration or a small adapter"_

- **Solution:** AC15–AC18 (config-only tenant, `json-mapping` tenant, ArchUnit, tenant isolation).
- **Why:** Extensibility is proven by tests, not only claimed.

**4. Brief:** _"Spec-Driven Development: … define clearly what needs to be built"_

- **Solution:** Given/When/Then criteria, each mapped to a test.
- **Why:** Criteria are written before the code, so "done" is objective and the AI assistant has a precise target.

**Integration & mapping**

- **AC1** — Given Tenant A on its own API, when a sync runs, then its direct and connecting routes are stored and `GET /api/tenants/tenant-a/routes` returns destinations grouped by origin.
- **AC2** — Given Tenant B on the `generic-backup-provider`, when a sync runs, then only `Y4`, non-codeshare, operating flights become routes, de-duplicated by pair.
- **AC3** — Given `?origin=ACA`, then only destinations from `ACA` are returned; `?type=DIRECT` returns only non-stop routes; an invalid code returns `400`.
- **AC4** — Given a payload with duplicates, invalid codes and inconsistent `routeName`, then only valid unique routes are stored and each problem is logged as a mapping issue.

**Change detection & idempotency**

- **AC5** — Given a stored network and a snapshot with one route added, one removed and one switching from connecting to direct, then exactly one `ADDED`, one `REMOVED` and one `MODIFIED` change are recorded and exposed by the changes endpoint.
- **AC6** — Given identical source data, when the sync runs twice, then the second run records 0 changes and the stored network is identical.

**Resilience**

- **AC7** — Given the primary source times out, then it is called 3 times with increasing delays, the fallback is used and the run is `DEGRADED`.
- **AC8** — Given the primary returns `401`, then it is called once and the fallback is used.
- **AC9** — Given a malformed response, then nothing from it is persisted.
- **AC10** — Given all sources fail, then the stored network is unchanged, the run is `FAILED` and the tenant's error count increases.
- **AC11** — Given an empty or drastically smaller snapshot, then nothing is persisted and the run is `REJECTED`.
- **AC12** — Given Tenant A falls back to Source B, then no `CONNECTING` route is removed.

**Observability**

- **AC13** — Every log line emitted during a sync contains `tenantId` and `correlationId`.
- **AC14** — The tenant health endpoint returns last successful sync time, last status, source used and error count.

**Extensibility (N tenants)**

- **AC15** — A third tenant reusing an existing adapter is onboarded with configuration only (test with a config-only tenant).
- **AC16** _(bonus, phase 2)_ — Tenant C, with an invented JSON format, is onboarded with the `json-mapping` adapter and configuration only.
- **AC17** — An architecture test (ArchUnit) proves the core has no dependency on any adapter.
- **AC18** — A failing tenant does not prevent other tenants from syncing.

**Delivery**

- **AC19** — `docker compose up` starts PostgreSQL and the service; a first sync of every tenant runs at startup. The startup sync (and a failing tenant not blocking the others) is covered by an automated test; the Compose half is a documented manual smoke check (README *Run*), since building the image inside the test suite would make `./mvnw verify` slow and dependent on Docker build.
- **AC20** — `./mvnw verify` runs all tests green.

---

## 6. REST API and definitions

Added before implementation to remove the ambiguities found when reviewing v1 (endpoint paths, `MODIFIED`, error count, staleness).

### 6.1 Definitions

- **`MODIFIED`**: a stored route whose `type` or `via` set differs in the new snapshot. Both old and new values are recorded.
- **Error count**: number of runs of the tenant that ended `FAILED` or `REJECTED` (monotonic, never reset; AC10).
- **Stale**: no `SUCCESS` or `DEGRADED` run finished (`finishedAt`) within the stale threshold (default **48 h**, configurable per tenant), or no successful run at all.
- **First sync**: with nothing stored, a non-empty snapshot is applied as is (all routes `ADDED`); an empty snapshot is `REJECTED`.
- **Statuses** of a run: `RUNNING`, `SUCCESS` (primary source), `DEGRADED` (a fallback source), `REJECTED` (guard), `FAILED` (all sources failed, or persistence failed).
- **Correlation id** = the sync run id (UUID).
- **Database unavailable**: the run is reported `FAILED` (response and logs) even if it could not be recorded. A run left `RUNNING` by a crash or a lost database is marked `FAILED` ("interrupted") at the next startup, so it counts in the error count.

### 6.2 Endpoints

All paths under `/api`. Unknown tenant → `404`. Errors use RFC 9457 `application/problem+json`; a `400` on a query parameter adds a `parameter` property naming it (`"parameter": "origin"`).

| Method & path | Success | Errors |
| --- | --- | --- |
| `GET /api/tenants` | `200` list of configured tenants | — |
| `GET /api/tenants/{tenantId}/routes?origin=&destination=&type=` | `200` routes grouped by origin | `400` invalid IATA code or type, `404` |
| `GET /api/tenants/{tenantId}/changes?limit=` | `200` changes, newest first (`limit` default 100, 1–1000) | `400`, `404` |
| `POST /api/tenants/{tenantId}/sync` | `200` the finished run (whatever its status, including `FAILED` when the database is unavailable) | `404`, `409` a sync of this tenant is already running or the tenant is disabled (no run recorded) |
| `GET /api/tenants/{tenantId}/health` | `200` health | `404` |

### 6.3 Response shapes

```jsonc
// GET /api/tenants
[{ "tenantId": "tenant-a", "airlineIata": "Y4", "enabled": true, "sources": ["tenant-a-stations", "generic-backup-provider"] }]

// GET /api/tenants/tenant-a/routes?origin=ACA   — origins and destinations sorted
{ "ACA": ["GDL", "MEX", "TIJ"] }

// GET /api/tenants/tenant-a/changes
[{ "runId": "…uuid…", "changeType": "MODIFIED", "origin": "ACA", "destination": "CUN",
   "oldType": "CONNECTING", "newType": "DIRECT", "oldVia": ["MEX"], "newVia": [],
   "detectedAt": "2026-11-05T10:00:00Z" }]

// POST /api/tenants/tenant-a/sync
{ "runId": "…uuid…", "tenantId": "tenant-a", "status": "DEGRADED", "sourceUsed": "generic-backup-provider",
  "startedAt": "…", "finishedAt": "…", "routeCount": 412, "added": 3, "removed": 1, "modified": 0,
  "issueCount": 2, "error": null }

// GET /api/tenants/tenant-a/health
{ "tenantId": "tenant-a", "enabled": true, "lastSuccessAt": "…", "lastStatus": "SUCCESS",
  "lastSourceUsed": "tenant-a-stations", "lastError": null, "errorCount": 0, "stale": false, "routeCount": 412 }
```

For `ADDED` the old fields are `null`, for `REMOVED` the new ones. `issueCount` is the number of `MappingIssue`s of the run; the issues themselves are logged (rule id, record, reason), not exposed.

### 6.4 Startup and local demo

- At startup every enabled tenant is synced once, in the background, one after the other (`caravelo.sync.on-startup`, default `true`).
- `docker compose up` also starts a `mock-sources` WireMock container serving the sample payloads (`tenant1.json` = Source A, `tenant2.json` = Source B). Real URLs and key replace them through environment variables (`TENANT_A_URL`, `BACKUP_PROVIDER_URL`, `BACKUP_PROVIDER_KEY`).
- The backup provider key is sent as the `api_key` query parameter. Its response is either a JSON array of flights or an object whose `response` field is that array.
- Tenant A's API answers a JSON array of stations (format §3.2).

### 6.5 Phase 2: `json-mapping` and scheduler

- `json-mapping` mapping keys: `items` (path to the array of route records, from the payload root, e.g. `$.data.routes[*]`), `origin` and `destination` (paths relative to one record, e.g. `$.from`, `$.legs[0].to`). Supported path syntax: `$`, `.field`, `[n]`, and `[*]` at the end of `items`. Records are normalised and validated with R1–R4 (issues logged); every route is `DIRECT` and the covered type is `DIRECT` (a format with connections needs its own adapter).
- An invalid mapping (missing `items` / `origin` / `destination`, unsupported path) disables the tenant at startup like an unknown adapter.
- Scheduler: each enabled tenant is synced on its `sync-cron` (default `0 0 3 * * *`, daily at 03:00, service time zone). A scheduled run that finds a sync already running for the tenant is skipped and logged, not queued. `caravelo.sync.scheduler-enabled` (default `true`) turns it off.
