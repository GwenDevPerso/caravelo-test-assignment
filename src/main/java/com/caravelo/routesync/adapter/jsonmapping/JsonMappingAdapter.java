package com.caravelo.routesync.adapter.jsonmapping;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

/** Declarative adapter for simple flat JSON formats: field paths come from configuration (SPEC §2, §6.5). */
@Component
public class JsonMappingAdapter implements RouteSourceAdapter {

    public static final String TYPE = "json-mapping";

    private final SourceHttpClient http;

    public JsonMappingAdapter(SourceHttpClient http) {
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
    public void validate(TenantSourceConfig config) {
        Mapping.of(config);
    }

    @Override
    public SourceResult fetch(TenantSourceConfig config) {
        Mapping mapping = Mapping.of(config);
        return map(http.getJson(URI.create(config.url())), mapping);
    }

    public SourceResult map(JsonNode payload, TenantSourceConfig config) {
        return map(payload, Mapping.of(config));
    }

    private SourceResult map(JsonNode payload, Mapping mapping) {
        JsonNode items = mapping.items.resolve(payload);
        if (items == null || !items.isArray()) {
            throw new SourceFetchException("'%s' is not an array in the payload".formatted(mapping.items.expression()),
                    false, null);
        }
        List<MappingIssue> issues = new ArrayList<>();
        Set<CanonicalRoute> routes = new LinkedHashSet<>();
        for (JsonNode item : items) {
            String rawOrigin = text(mapping.origin.resolve(item));
            String rawDestination = text(mapping.destination.resolve(item));
            String origin = IataCodeUtils.normalise(rawOrigin);
            String destination = IataCodeUtils.normalise(rawDestination);
            if (origin == null || destination == null) {
                issues.add(new MappingIssue("R3", "record rejected, invalid origin/destination: %s/%s"
                        .formatted(rawOrigin, rawDestination)));
            } else if (origin.equals(destination)) {
                issues.add(new MappingIssue("R4", "record rejected, origin equals destination " + origin));
            } else {
                routes.add(new CanonicalRoute(origin, destination, RouteType.DIRECT, Set.of()));
            }
        }
        return new SourceResult(TYPE, routes, issues);
    }

    private static String text(JsonNode node) {
        return node == null || !node.isValueNode() ? null : node.asString();
    }

    private record Mapping(JsonPath items, JsonPath origin, JsonPath destination) {

        static Mapping of(TenantSourceConfig config) {
            return new Mapping(path(config, "items", true), path(config, "origin", false),
                    path(config, "destination", false));
        }

        private static JsonPath path(TenantSourceConfig config, String key, boolean allowWildcard) {
            String expression = config.mapping().get(key);
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("mapping." + key + " is required");
            }
            return JsonPath.parse(expression, allowWildcard);
        }
    }
}
