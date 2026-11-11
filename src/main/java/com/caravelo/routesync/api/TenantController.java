package com.caravelo.routesync.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.caravelo.routesync.core.type.RouteType;
import com.caravelo.routesync.core.util.IataCodes;
import com.caravelo.routesync.persistence.ChangeRepository;
import com.caravelo.routesync.persistence.RouteRepository;
import com.caravelo.routesync.sync.exception.TenantNotFoundException;
import com.caravelo.routesync.sync.model.SyncRunView;
import com.caravelo.routesync.sync.model.TenantHealth;
import com.caravelo.routesync.sync.service.HealthService;
import com.caravelo.routesync.sync.service.SyncService;
import com.caravelo.routesync.sync.type.ChangeType;
import com.caravelo.routesync.tenant.TenantRegistry;

/** REST API of SPEC §6.2. */
@RestController
@RequestMapping("/api/tenants")
class TenantController {

    record TenantView(String tenantId, String airlineIata, boolean enabled, List<String> sources) {}

    record ChangeView(UUID runId, ChangeType changeType, String origin, String destination, RouteType oldType,
                      RouteType newType, List<String> oldVia, List<String> newVia, Instant detectedAt) {}

    private final TenantRegistry tenants;
    private final RouteRepository routes;
    private final ChangeRepository changes;
    private final SyncService sync;
    private final HealthService health;

    TenantController(TenantRegistry tenants, RouteRepository routes, ChangeRepository changes, SyncService sync,
                     HealthService health) {
        this.tenants = tenants;
        this.routes = routes;
        this.changes = changes;
        this.sync = sync;
        this.health = health;
    }

    @GetMapping
    List<TenantView> tenants() {
        return tenants.all().stream()
                .map(t -> new TenantView(t.tenantId(), t.airlineIata(), t.enabled(), t.sourceTypes()))
                .toList();
    }

    /** Destinations grouped by origin, both sorted: {@code {"ACA": ["GDL", "MEX"]}}. */
    @GetMapping("/{tenantId}/routes")
    Map<String, List<String>> routes(@PathVariable String tenantId,
                                     @RequestParam(required = false) String origin,
                                     @RequestParam(required = false) String destination,
                                     @RequestParam(required = false) String type) {
        requireTenant(tenantId);
        return routes.find(tenantId, station("origin", origin), station("destination", destination), routeType(type))
                .stream()
                .collect(Collectors.groupingBy(route -> route.origin(), TreeMap::new,
                        Collectors.mapping(route -> route.destination(), Collectors.toList())));
    }

    @GetMapping("/{tenantId}/changes")
    List<ChangeView> changes(@PathVariable String tenantId, @RequestParam(defaultValue = "100") int limit) {
        requireTenant(tenantId);
        if (limit < 1 || limit > 1000) {
            throw new InvalidParameterException("limit", "limit must be between 1 and 1000");
        }
        return changes.latest(tenantId, limit).stream()
                .map(stored -> new ChangeView(stored.runId(), stored.change().changeType(), stored.change().origin(),
                        stored.change().destination(), stored.change().oldType(), stored.change().newType(),
                        sorted(stored.change().oldVia()), sorted(stored.change().newVia()), stored.detectedAt()))
                .toList();
    }

    @PostMapping("/{tenantId}/sync")
    SyncRunView sync(@PathVariable String tenantId) {
        return sync.sync(tenantId);
    }

    @GetMapping("/{tenantId}/health")
    TenantHealth health(@PathVariable String tenantId) {
        return health.health(tenantId);
    }

    private void requireTenant(String tenantId) {
        if (tenants.find(tenantId).isEmpty()) {
            throw new TenantNotFoundException(tenantId);
        }
    }

    private static String station(String parameter, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String code = IataCodes.normalise(value);
        if (code == null) {
            throw new InvalidParameterException(parameter, "Invalid IATA code for " + parameter + ": " + value);
        }
        return code;
    }

    private static RouteType routeType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RouteType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("type", "type must be DIRECT or CONNECTING, got: " + value);
        }
    }

    private static List<String> sorted(Set<String> via) {
        return via == null ? null : via.stream().sorted().toList();
    }
}
