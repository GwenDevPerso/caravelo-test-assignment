package com.caravelo.routesync.adapter.backup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.caravelo.routesync.core.exception.SourceFetchException;
import com.caravelo.routesync.core.model.CanonicalRoute;
import com.caravelo.routesync.core.model.MappingIssue;
import com.caravelo.routesync.core.model.SourceResult;
import com.caravelo.routesync.core.model.TenantSourceConfig;
import com.caravelo.routesync.core.service.RouteSourceAdapter;
import com.caravelo.routesync.core.service.SourceHttpClient;
import com.caravelo.routesync.core.type.RouteType;
import com.caravelo.routesync.core.util.IataCodes;

import tools.jackson.databind.JsonNode;

/** Shared AirLabs-like provider: one item per scheduled flight, filtered by airline (SPEC §3.3). */
@Component
public class GenericBackupProviderAdapter implements RouteSourceAdapter {

    public static final String TYPE = "generic-backup-provider";

    private final SourceHttpClient http;

    public GenericBackupProviderAdapter(SourceHttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<RouteType> coveredTypes() {
        return Set.of(RouteType.DIRECT);
    }

    @Override
    public SourceResult fetch(TenantSourceConfig config) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(config.url());
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            uri.queryParam("api_key", config.apiKey());
        }
        return map(http.getJson(uri.encode().build().toUri()), config);
    }

    public SourceResult map(JsonNode payload, TenantSourceConfig config) {
        JsonNode flights = payload != null && payload.isObject() ? payload.get("response") : payload;
        if (flights == null || !flights.isArray()) {
            throw new SourceFetchException("Backup provider payload is not an array of flights", false, null);
        }
        String airline = config.airlineIata() == null ? null : config.airlineIata().trim().toUpperCase(Locale.ROOT);
        List<MappingIssue> issues = new ArrayList<>();
        Set<CanonicalRoute> routes = new LinkedHashSet<>(); // R1/R13: a set collapses duplicate pairs
        for (JsonNode flight : flights) {
            if (!airlineMatches(flight, airline) || isCodeshare(flight) || !operates(flight)) {
                continue; // R5, R13, not operating: ignored, not an issue
            }
            String origin = IataCodes.normalise(text(flight, "dep_iata"));
            String destination = IataCodes.normalise(text(flight, "arr_iata"));
            if (origin == null || destination == null) {
                issues.add(new MappingIssue("R3", "flight %s rejected, invalid dep_iata/arr_iata: %s/%s"
                        .formatted(text(flight, "flight_iata"), text(flight, "dep_iata"), text(flight, "arr_iata"))));
            } else if (origin.equals(destination)) {
                issues.add(new MappingIssue("R4", "flight %s rejected, origin equals destination %s"
                        .formatted(text(flight, "flight_iata"), origin)));
            } else {
                routes.add(new CanonicalRoute(origin, destination, RouteType.DIRECT, Set.of()));
            }
        }
        return new SourceResult(TYPE, routes, issues);
    }

    private static boolean airlineMatches(JsonNode flight, String airline) {
        String code = text(flight, "airline_iata");
        return code != null && code.trim().toUpperCase(Locale.ROOT).equals(airline);
    }

    private static boolean isCodeshare(JsonNode flight) {
        return hasText(flight, "cs_airline_iata") || hasText(flight, "cs_flight_iata") || hasText(flight, "cs_flight_number");
    }

    private static boolean operates(JsonNode flight) {
        JsonNode days = flight.get("days");
        return days != null && days.isArray() && !days.isEmpty();
    }

    private static boolean hasText(JsonNode node, String field) {
        String value = text(node, field);
        return value != null && !value.isBlank();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
