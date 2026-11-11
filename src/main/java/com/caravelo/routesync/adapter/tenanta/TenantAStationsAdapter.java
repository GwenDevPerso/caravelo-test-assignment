package com.caravelo.routesync.adapter.tenanta;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.caravelo.routesync.core.source.SourceFetchException;
import com.caravelo.routesync.core.route.CanonicalRoute;
import com.caravelo.routesync.core.source.MappingIssue;
import com.caravelo.routesync.core.source.SourceResult;
import com.caravelo.routesync.core.source.TenantSourceConfig;
import com.caravelo.routesync.core.source.RouteSourceAdapter;
import com.caravelo.routesync.core.source.SourceHttpClient;
import com.caravelo.routesync.core.route.RouteType;
import com.caravelo.routesync.core.route.IataCodeUtils;

import tools.jackson.databind.JsonNode;

/** Tenant A's own Routes API: one item per departure station with nested routes (SPEC §3.2). */
@Component
public class TenantAStationsAdapter implements RouteSourceAdapter {

    public static final String TYPE = "tenant-a-stations";

    private final SourceHttpClient http;

    public TenantAStationsAdapter(SourceHttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<RouteType> coveredTypes() {
        return Set.of(RouteType.DIRECT, RouteType.CONNECTING);
    }

    @Override
    public SourceResult fetch(TenantSourceConfig config) {
        return map(http.getJson(URI.create(config.url())), config);
    }

    public SourceResult map(JsonNode stations, TenantSourceConfig config) {
        if (stations == null || !stations.isArray()) {
            throw new SourceFetchException("Tenant A payload is not an array of stations", false, null);
        }
        List<MappingIssue> issues = new ArrayList<>();
        Set<CanonicalRoute> routes = new LinkedHashSet<>();
        for (JsonNode station : stations) {
            String origin = IataCodeUtils.normalise(text(station, "stationCode"));
            if (origin == null) {
                issues.add(new MappingIssue("R3", "station rejected, invalid stationCode: " + text(station, "stationCode")));
                continue;
            }
            routes.addAll(new StationMapper(origin, issues).map(station.path("route")));
        }
        return new SourceResult(TYPE, routes, issues);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** Maps the routes of one departure station. */
    private static final class StationMapper {

        private final String origin;
        private final List<MappingIssue> issues;
        private final Map<String, Set<String>> connecting = new LinkedHashMap<>();
        private final Set<String> directFromDirectList = new LinkedHashSet<>();
        private final Set<String> directFromConnectionList = new LinkedHashSet<>();

        StationMapper(String origin, List<MappingIssue> issues) {
            this.origin = origin;
            this.issues = issues;
        }

        List<CanonicalRoute> map(JsonNode route) {
            for (JsonNode direct : route.path("direct")) {
                String destination = destination(direct);
                if (destination != null && !directFromDirectList.add(destination)) {
                    issues.add(new MappingIssue("R1", "duplicate direct route " + origin + destination));
                }
            }
            for (JsonNode connection : route.path("connection")) {
                String destination = destination(connection);
                if (destination == null) {
                    continue;
                }
                if (connection.path("routeDirect").asInt(0) == 1) {
                    if (!directFromConnectionList.add(destination)) {
                        issues.add(new MappingIssue("R1", "duplicate direct route " + origin + destination
                                + " in connection[]"));
                    }
                } else {
                    Set<String> via = via(connection, destination);
                    Set<String> previous = connecting.putIfAbsent(destination, via);
                    if (previous != null) {
                        previous.addAll(via);
                        issues.add(new MappingIssue("R1", "duplicate connecting route " + origin + destination));
                    }
                }
            }
            reportDisagreement();

            Set<String> direct = new LinkedHashSet<>(directFromDirectList);
            direct.addAll(directFromConnectionList); // R9: union
            List<CanonicalRoute> routes = new ArrayList<>();
            direct.forEach(destination -> routes.add(new CanonicalRoute(origin, destination, RouteType.DIRECT, Set.of())));
            connecting.forEach((destination, via) -> {
                if (!direct.contains(destination)) { // §3.1: direct wins over connecting
                    routes.add(new CanonicalRoute(origin, destination, RouteType.CONNECTING, via));
                }
            });
            return routes;
        }

        /** Valid, distinct destination of a route element, or null when the element is rejected (R3, R4, R10). */
        private String destination(JsonNode element) {
            String raw = text(element, "arrivalStation");
            String destination = IataCodeUtils.normalise(raw);
            if (destination == null) {
                issues.add(new MappingIssue("R3", "route from " + origin + " rejected, invalid arrivalStation: " + raw));
                return null;
            }
            if (destination.equals(origin)) {
                issues.add(new MappingIssue("R4", "route " + origin + destination + " rejected, origin equals destination"));
                return null;
            }
            String routeName = text(element, "routeName");
            if (routeName != null && !routeName.trim().equalsIgnoreCase(origin + destination)) {
                issues.add(new MappingIssue("R10", "routeName " + routeName + " does not match " + origin + destination));
            }
            return destination;
        }

        private Set<String> via(JsonNode connection, String destination) {
            String raw = text(connection, "connectionStation");
            Set<String> via = new LinkedHashSet<>();
            List<String> dropped = new ArrayList<>();
            for (String part : raw == null ? new String[0] : raw.split(",")) {
                String code = IataCodeUtils.normalise(part);
                if (code == null || code.equals(origin) || code.equals(destination)) {
                    if (!part.isBlank()) {
                        dropped.add(part.trim());
                    }
                } else {
                    via.add(code);
                }
            }
            if (!dropped.isEmpty() || via.isEmpty()) {
                issues.add(new MappingIssue("R11", "connectionStation '%s' of %s%s: dropped %s"
                        .formatted(raw, origin, destination, dropped)));
            }
            return via;
        }

        private void reportDisagreement() {
            if (directFromConnectionList.isEmpty()) {
                return; // the station only uses direct[]: nothing to compare
            }
            Set<String> onlyInOne = new HashSet<>(directFromDirectList);
            onlyInOne.addAll(directFromConnectionList);
            Set<String> inBoth = new HashSet<>(directFromDirectList);
            inBoth.retainAll(directFromConnectionList);
            onlyInOne.removeAll(inBoth);
            if (!onlyInOne.isEmpty()) {
                issues.add(new MappingIssue("R9", "direct[] and connection[routeDirect=1] of %s disagree on %s"
                        .formatted(origin, onlyInOne)));
            }
        }
    }
}
