package com.caravelo.routesync.core.route;

import java.util.Set;


/** A directed route of a tenant's network, independent of any source format (SPEC §3.1). */
public record CanonicalRoute(String origin, String destination, RouteType type, Set<String> via) {

    public CanonicalRoute {
        via = via == null ? Set.of() : Set.copyOf(via);
    }
}
