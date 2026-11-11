package com.caravelo.routesync.sync.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.caravelo.routesync.persistence.RouteRepository;
import com.caravelo.routesync.persistence.SyncRunRepository;
import com.caravelo.routesync.sync.exception.TenantNotFoundException;
import com.caravelo.routesync.sync.model.SyncRunView;
import com.caravelo.routesync.sync.model.TenantHealth;
import com.caravelo.routesync.tenant.TenantDefinition;
import com.caravelo.routesync.tenant.TenantRegistry;

/** Per-tenant health signals answering the first on-call questions (SPEC §4 brief decision 5, §6.1). */
@Service
public class HealthService {

    private final TenantRegistry tenants;
    private final SyncRunRepository runs;
    private final RouteRepository routes;
    private final Clock clock;

    public HealthService(TenantRegistry tenants, SyncRunRepository runs, RouteRepository routes, Clock clock) {
        this.tenants = tenants;
        this.runs = runs;
        this.routes = routes;
        this.clock = clock;
    }

    public TenantHealth health(String tenantId) {
        TenantDefinition tenant = tenants.find(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
        Optional<SyncRunView> last = runs.latest(tenantId);
        Instant lastSuccessAt = runs.latestSuccessful(tenantId).map(SyncRunView::finishedAt).orElse(null);
        boolean stale = lastSuccessAt == null || lastSuccessAt.plus(tenant.staleAfter()).isBefore(clock.instant());
        String lastError = last.map(SyncRunView::error).orElse(tenant.enabled() ? null : tenant.disabledReason());
        return new TenantHealth(tenantId, tenant.enabled(), lastSuccessAt, last.map(SyncRunView::status).orElse(null),
                last.map(SyncRunView::sourceUsed).orElse(null), lastError, runs.errorCount(tenantId), stale,
                routes.count(tenantId));
    }
}
