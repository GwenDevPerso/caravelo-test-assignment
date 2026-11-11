# Implementation contract

The public Java API, configuration keys and tables that tests are written against. It complements `SPEC.md`
(behaviour) with names and signatures, so tests can be written before the code (`test-designer`) and the
implementation provides exactly this. Root package: `com.caravelo.routesync`. Jackson 3 (`tools.jackson.*`).

## Packages

| Package | Content | May depend on |
| --- | --- | --- |
| `core` | Adapter contract (SPEC §2), canonical model, HTTP client with retry | — |
| `sync` | Diff, guard, sync orchestration | `core`, `persistence`, `tenant` |
| `persistence` | JDBC repositories | `core`, `sync` model |
| `tenant` | Tenant configuration, adapter registry | `core` |
| `api` | REST controllers | `sync`, `persistence`, `tenant` |
| `adapter.<type>` | One package per adapter: `adapter.tenanta`, `adapter.backup`, `adapter.jsonmapping` | `core` only |

Rule (AC17): no class outside `..adapter..` depends on a class in `..adapter..`.

Inside `core` and `sync`, classes are grouped by kind:

```
core.model      CanonicalRoute, MappingIssue, SourceResult, TenantSourceConfig
core.type       RouteType
core.service    RouteSourceAdapter, SourceHttpClient
core.exception  SourceFetchException
core.config     SyncProperties
core.util       IataCodes
sync.service    SyncService, HealthService, NetworkDiff, AntiWipeGuard
sync.model      RouteChange, SyncRunView, TenantHealth
sync.type       ChangeType, SyncStatus
sync.exception  SyncInProgressException, TenantDisabledException, TenantNotFoundException
sync.scheduling StartupSync, TenantSyncScheduler
sync.config     ClockConfiguration
```

## `core`

```java
public enum RouteType { DIRECT, CONNECTING }
public record CanonicalRoute(String origin, String destination, RouteType type, Set<String> via) {}
public record MappingIssue(String rule, String message) {}          // rule = "R3", "R9"…
public record SourceResult(String sourceType, Set<CanonicalRoute> routes, List<MappingIssue> issues) {}
public record TenantSourceConfig(String tenantId, String adapter, String url, String apiKey,
                                 String airlineIata, Map<String, String> mapping) {}

public interface RouteSourceAdapter {
    String type();
    Set<RouteType> coveredTypes();
    SourceResult fetch(TenantSourceConfig config);   // throws SourceFetchException
    default void validate(TenantSourceConfig config) {}   // startup check; throwing disables the tenant
}

public class SourceFetchException extends RuntimeException {
    public SourceFetchException(String message, boolean retryable, Throwable cause);
    public boolean retryable();
}
```

`SourceHttpClient` (Spring bean in `core.service`): `JsonNode getJson(URI uri)` — timeouts, retries with backoff (SPEC §4.1),
a non-JSON body or non-2xx status becomes a `SourceFetchException`.

## Adapters

Each adapter is a Spring `@Component` with a constructor taking `SourceHttpClient`, and a **pure mapping method**
used by unit tests (no HTTP):

```java
// adapter.tenanta — type "tenant-a-stations", covers DIRECT + CONNECTING (SPEC §3.2)
public class TenantAStationsAdapter implements RouteSourceAdapter {
    public TenantAStationsAdapter(SourceHttpClient http);
    public SourceResult map(JsonNode stations, TenantSourceConfig config);
}
// adapter.backup — type "generic-backup-provider", covers DIRECT (SPEC §3.3)
public class GenericBackupProviderAdapter implements RouteSourceAdapter {
    public GenericBackupProviderAdapter(SourceHttpClient http);
    public SourceResult map(JsonNode payload, TenantSourceConfig config);   // array, or object with "response" array
}
// adapter.jsonmapping — type "json-mapping" (phase 2, SPEC §6.5)
public class JsonMappingAdapter implements RouteSourceAdapter {
    public JsonMappingAdapter(SourceHttpClient http);
    public SourceResult map(JsonNode payload, TenantSourceConfig config);   // config.mapping(): items, origin, destination
    // invalid mapping → IllegalArgumentException from map()
}
```

In tests, build a `JsonNode` with `tools.jackson.databind.json.JsonMapper.builder().build().readTree(...)`.
Real sample payloads: `tenant1.json` (Source A, 146 stations) and `tenant2.json` (Source B, 866 flights) at the repo root.

## `sync`

```java
public enum ChangeType { ADDED, REMOVED, MODIFIED }
public record RouteChange(ChangeType changeType, String origin, String destination,
                          RouteType oldType, RouteType newType, Set<String> oldVia, Set<String> newVia) {}

public final class NetworkDiff {
    // stored routes whose type is not in coveredTypes are never REMOVED (R12)
    public static List<RouteChange> compute(Collection<CanonicalRoute> stored, Set<CanonicalRoute> snapshot,
                                            Set<RouteType> coveredTypes);
}
public final class AntiWipeGuard {
    // false if snapshot empty; true if nothing stored; else snapshotSize >= threshold * storedCoveredCount
    public static boolean accepts(int storedCoveredCount, int snapshotSize, double threshold);
}

public enum SyncStatus { RUNNING, SUCCESS, DEGRADED, REJECTED, FAILED }
public record SyncRunView(UUID runId, String tenantId, SyncStatus status, String sourceUsed,
                          Instant startedAt, Instant finishedAt, int routeCount,
                          int added, int removed, int modified, int issueCount, String error) {}

@Service public class SyncService {
    public SyncRunView sync(String tenantId);   // TenantNotFoundException (404), SyncInProgressException (409)
}
```

## Configuration (`caravelo.*`)

```yaml
caravelo:
  sync:
    on-startup: true            # false in tests
    connect-timeout: 5s
    read-timeout: 10s
    retry:
      max-attempts: 3
      initial-backoff: 1s       # doubled each attempt, ±20 % jitter; tests use ~10ms
  tenants:
    <tenant-id>:
      airline-iata: Y4
      sync-cron: '0 0 3 * * *'
      guard-threshold: 0.5
      stale-after: 48h
      sources:
        - adapter: tenant-a-stations
          url: http://…
          api-key: …            # optional
          mapping: {}           # json-mapping only
```

## Tables (Flyway `V1__init.sql`)

Tests should assert through the REST API (SPEC §6) where possible. Direct SQL (`JdbcClient`) only for setup or
"nothing persisted" checks:

- `route(tenant_id, origin, destination, type, via text[], updated_at)` — PK `(tenant_id, origin, destination)`
- `sync_run(id uuid, tenant_id, status, source_used, started_at, finished_at, route_count, added, removed, modified, issue_count, error)`
- `route_change(id, tenant_id, run_id, change_type, origin, destination, old_type, new_type, old_via, new_via, detected_at)`

Clean between integration tests with `TRUNCATE route, route_change, sync_run`.

## Logging

During a sync every log line carries MDC keys `tenantId` and `correlationId` (= run id). Logs are ECS JSON
(`logging.structured.format.console: ecs`), so MDC keys appear as JSON fields.

## Constructors and collaborators (for unit tests with Mockito)

```java
// core
public SourceHttpClient(tools.jackson.databind.json.JsonMapper json, SyncProperties properties);
public record SyncProperties(boolean onStartup, boolean schedulerEnabled, Duration connectTimeout,
                             Duration readTimeout, Retry retry) {
    public record Retry(int maxAttempts, Duration initialBackoff) {}
}
// Backoff before attempt n+1 = initialBackoff * 2^(n-1), ±20 % jitter; a 429/503 Retry-After (seconds) replaces it
// (capped at 30 s). Exception messages and logs never contain the query string (api_key).

// tenant
public TenantRegistry(CaraveloProperties properties, List<RouteSourceAdapter> adapters);
public record CaraveloProperties(Map<String, Tenant> tenants) {
    public record Tenant(String airlineIata, String syncCron, double guardThreshold, Duration staleAfter, List<Source> sources) {}
    public record Source(String adapter, String url, String apiKey, Map<String, String> mapping) {}
}

// persistence (Spring beans, mockable)
RouteRepository:   List<CanonicalRoute> findAll(String tenantId); int count(String tenantId);
SyncRunRepository: void start(UUID runId, String tenantId, Instant startedAt); void finish(SyncRunView run);
                   Optional<SyncRunView> latest(String tenantId); Optional<SyncRunView> latestSuccessful(String tenantId);
                   int errorCount(String tenantId); int failInterrupted(Instant now);
NetworkStore:      void apply(SyncRunView run, List<RouteChange> diff);   // one transaction

// sync
public SyncService(TenantRegistry tenants, RouteRepository routes, SyncRunRepository runs, NetworkStore store, Clock clock);
public HealthService(TenantRegistry tenants, SyncRunRepository runs, RouteRepository routes, Clock clock);
public TenantHealth health(String tenantId);
public record TenantHealth(String tenantId, boolean enabled, Instant lastSuccessAt, SyncStatus lastStatus,
                           String lastSourceUsed, String lastError, int errorCount, boolean stale, int routeCount) {}
```
