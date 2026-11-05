package com.caravelo.routesync.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.caravelo.routesync.core.CanonicalRoute;
import com.caravelo.routesync.core.RouteType;

/** Set-based diff between the stored network and a new snapshot: same input, no change (SPEC §4.3). */
public final class NetworkDiff {

    private NetworkDiff() {
    }

    /** Stored routes whose type the source cannot see are never REMOVED (R12). */
    public static List<RouteChange> compute(Collection<CanonicalRoute> stored, Set<CanonicalRoute> snapshot,
                                            Set<RouteType> coveredTypes) {
        Map<String, CanonicalRoute> before = byPair(stored);
        Map<String, CanonicalRoute> after = byPair(snapshot);
        List<RouteChange> changes = new ArrayList<>();
        after.forEach((pair, route) -> {
            CanonicalRoute old = before.get(pair);
            if (old == null) {
                changes.add(new RouteChange(ChangeType.ADDED, route.origin(), route.destination(),
                        null, route.type(), null, route.via()));
            } else if (old.type() != route.type() || !old.via().equals(route.via())) {
                changes.add(new RouteChange(ChangeType.MODIFIED, route.origin(), route.destination(),
                        old.type(), route.type(), old.via(), route.via()));
            }
        });
        before.forEach((pair, old) -> {
            if (!after.containsKey(pair) && coveredTypes.contains(old.type())) {
                changes.add(new RouteChange(ChangeType.REMOVED, old.origin(), old.destination(),
                        old.type(), null, old.via(), null));
            }
        });
        return changes;
    }

    private static Map<String, CanonicalRoute> byPair(Collection<CanonicalRoute> routes) {
        Map<String, CanonicalRoute> map = new LinkedHashMap<>();
        routes.forEach(route -> map.put(route.origin() + route.destination(), route));
        return map;
    }
}
