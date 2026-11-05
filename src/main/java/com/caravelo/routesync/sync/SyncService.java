package com.caravelo.routesync.sync;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.caravelo.routesync.core.CanonicalRoute;
import com.caravelo.routesync.core.RouteType;
import com.caravelo.routesync.core.SourceResult;
import com.caravelo.routesync.persistence.NetworkStore;
import com.caravelo.routesync.persistence.RouteRepository;
import com.caravelo.routesync.persistence.SyncRunRepository;
import com.caravelo.routesync.tenant.TenantDefinition;
import com.caravelo.routesync.tenant.TenantRegistry;

/**
 * One sync run: fetch (primary, then fallbacks) → guard → diff → apply in one transaction (SPEC §4).
 * A failure at any step leaves the stored network untouched; every run is recorded with its status.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final TenantRegistry tenants;
    private final RouteRepository routes;
    private final SyncRunRepository runs;
    private final NetworkStore store;
    private final Clock clock;
    // shortcut: in-process lock, single instance only (A7); several instances need a PostgreSQL advisory lock
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SyncService(TenantRegistry tenants, RouteRepository routes, SyncRunRepository runs, NetworkStore store,
                       Clock clock) {
        this.tenants = tenants;
        this.routes = routes;
        this.runs = runs;
        this.store = store;
        this.clock = clock;
    }

    public SyncRunView sync(String tenantId) {
        TenantDefinition tenant = tenants.find(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
        if (!tenant.enabled()) {
            throw new TenantDisabledException(tenantId, tenant.disabledReason());
        }
        ReentrantLock lock = locks.computeIfAbsent(tenantId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new SyncInProgressException(tenantId);
        }
        UUID runId = UUID.randomUUID();
        try (var tenantMdc = MDC.putCloseable("tenantId", tenantId);
             var correlationMdc = MDC.putCloseable("correlationId", runId.toString())) {
            return run(tenant, runId);
        } finally {
            lock.unlock();
        }
    }

    private SyncRunView run(TenantDefinition tenant, UUID runId) {
        Instant startedAt = clock.instant();
        try {
            runs.start(runId, tenant.tenantId(), startedAt);
        } catch (RuntimeException e) { // database unavailable: nothing fetched, nothing touched (SPEC §6.1)
            log.error("Sync FAILED, database unavailable: {}", e.getMessage());
            return failed(tenant, runId, startedAt, null, 0, "database unavailable: " + e.getMessage());
        }
        log.info("Sync started, sources {}", tenant.sourceTypes());

        Fetched fetched = fetch(tenant);
        if (fetched.result == null) {
            String error = "all sources failed: " + String.join("; ", fetched.errors);
            log.error("Sync FAILED, stored network kept: {}", error);
            return finish(failed(tenant, runId, startedAt, null, 0, error));
        }
        SourceResult result = fetched.result;
        result.issues().forEach(issue ->
                log.warn("Mapping issue {} from {}: {}", issue.rule(), result.sourceType(), issue.message()));
        try {
            return apply(tenant, runId, startedAt, fetched);
        } catch (RuntimeException e) {
            log.error("Sync FAILED while applying, transaction rolled back: {}", e.getMessage(), e);
            return finish(failed(tenant, runId, startedAt, result.sourceType(), result.issues().size(),
                    "persistence failed: " + e.getMessage()));
        }
    }

    private SyncRunView apply(TenantDefinition tenant, UUID runId, Instant startedAt, Fetched fetched) {
        SourceResult result = fetched.result;
        Set<RouteType> covered = fetched.coveredTypes;
        Set<CanonicalRoute> snapshot = result.routes().stream()
                .filter(route -> covered.contains(route.type()))
                .collect(Collectors.toSet());
        List<CanonicalRoute> stored = routes.findAll(tenant.tenantId());
        int storedCovered = (int) stored.stream().filter(route -> covered.contains(route.type())).count();

        if (!AntiWipeGuard.accepts(storedCovered, snapshot.size(), tenant.guardThreshold())) {
            String error = "snapshot of %d routes from %s rejected: %d stored routes of types %s, threshold %.0f %%"
                    .formatted(snapshot.size(), result.sourceType(), storedCovered, covered,
                            tenant.guardThreshold() * 100);
            log.error("Sync REJECTED by the anti-wipe guard: {}", error);
            return finish(new SyncRunView(runId, tenant.tenantId(), SyncStatus.REJECTED, result.sourceType(),
                    startedAt, clock.instant(), stored.size(), 0, 0, 0, result.issues().size(), error));
        }

        List<RouteChange> diff = NetworkDiff.compute(stored, snapshot, covered);
        int added = count(diff, ChangeType.ADDED);
        int removed = count(diff, ChangeType.REMOVED);
        SyncStatus status = fetched.fallback ? SyncStatus.DEGRADED : SyncStatus.SUCCESS;
        SyncRunView run = new SyncRunView(runId, tenant.tenantId(), status, result.sourceType(), startedAt,
                clock.instant(), stored.size() + added - removed, added, removed, count(diff, ChangeType.MODIFIED),
                result.issues().size(), null);
        store.apply(run, diff);
        log.info("Sync {} from {}: {} routes, {} added, {} removed, {} modified, {} mapping issues",
                status, run.sourceUsed(), run.routeCount(), added, removed, run.modified(), run.issueCount());
        return run;
    }

    private Fetched fetch(TenantDefinition tenant) {
        List<String> errors = new ArrayList<>();
        List<TenantDefinition.ResolvedSource> sources = tenant.sources();
        for (int i = 0; i < sources.size(); i++) {
            TenantDefinition.ResolvedSource source = sources.get(i);
            String type = source.adapter().type();
            try {
                SourceResult result = source.adapter().fetch(source.config());
                if (i > 0) {
                    log.warn("Using fallback source {}", type);
                }
                return new Fetched(result, source.adapter().coveredTypes(), i > 0, errors);
            } catch (RuntimeException e) { // SourceFetchException, or a bug in one adapter: try the next source
                log.warn("Source {} failed: {}", type, e.getMessage());
                errors.add(type + ": " + e.getMessage());
            }
        }
        return new Fetched(null, Set.of(), false, errors);
    }

    private SyncRunView failed(TenantDefinition tenant, UUID runId, Instant startedAt, String source, int issues,
                               String error) {
        return new SyncRunView(runId, tenant.tenantId(), SyncStatus.FAILED, source, startedAt, clock.instant(),
                0, 0, 0, 0, issues, error);
    }

    private SyncRunView finish(SyncRunView run) {
        try {
            runs.finish(run);
        } catch (RuntimeException e) { // e.g. database down: the run stays RUNNING, the error is in the logs
            log.error("Could not record the result of run {}: {}", run.runId(), e.getMessage());
        }
        return run;
    }

    private static int count(List<RouteChange> diff, ChangeType type) {
        return (int) diff.stream().filter(change -> change.changeType() == type).count();
    }

    private record Fetched(SourceResult result, Set<RouteType> coveredTypes, boolean fallback, List<String> errors) {}
}
