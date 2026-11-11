package com.caravelo.routesync.core.source;

import java.util.Map;

/** One configured source of a tenant, as handed to its adapter. */
public record TenantSourceConfig(String tenantId, String adapter, String url, String apiKey,
                                 String airlineIata, Map<String, String> mapping) {

    public TenantSourceConfig {
        mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
    }
}
