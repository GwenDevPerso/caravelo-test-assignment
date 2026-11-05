package com.caravelo.routesync.core;

import java.util.Set;

/** Plugin contract (SPEC §2): fetches one source format and maps it to canonical routes. */
public interface RouteSourceAdapter {

    String type();

    Set<RouteType> coveredTypes();

    /** @throws SourceFetchException when the source cannot be fetched or its payload is unusable */
    SourceResult fetch(TenantSourceConfig config);

    /** Checks a tenant's source config at startup; an exception disables that tenant only (SPEC §4.4, §6.5). */
    default void validate(TenantSourceConfig config) {
    }
}
