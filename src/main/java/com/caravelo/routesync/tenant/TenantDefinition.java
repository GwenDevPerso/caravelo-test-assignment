package com.caravelo.routesync.tenant;

import java.time.Duration;
import java.util.List;

import com.caravelo.routesync.core.source.TenantSourceConfig;
import com.caravelo.routesync.core.source.RouteSourceAdapter;

/** A configured tenant with its sources resolved to adapters, primary first. */
public record TenantDefinition(String tenantId, String airlineIata, String syncCron, double guardThreshold,
                               Duration staleAfter, List<String> sourceTypes, List<ResolvedSource> sources,
                               String disabledReason) {

    public record ResolvedSource(RouteSourceAdapter adapter, TenantSourceConfig config) {}

    public boolean enabled() {
        return disabledReason == null;
    }
}
