package com.caravelo.routesync.tenant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.caravelo.routesync.core.source.TenantSourceConfig;
import com.caravelo.routesync.core.source.RouteSourceAdapter;

/**
 * Resolves each configured tenant's sources to adapters by {@code type()}. A tenant whose config is invalid
 * (unknown adapter, no source) is disabled with a clear error; the others are unaffected (SPEC §4.4).
 */
@Component
public class TenantRegistry {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistry.class);

    private final Map<String, TenantDefinition> tenants = new TreeMap<>();

    public TenantRegistry(CaraveloProperties properties, List<RouteSourceAdapter> adapters) {
        Map<String, RouteSourceAdapter> byType = adapters.stream()
                .collect(Collectors.toMap(RouteSourceAdapter::type, Function.identity()));
        properties.tenants().forEach((id, tenant) -> tenants.put(id, resolve(id, tenant, byType)));
    }

    private static TenantDefinition resolve(String id, CaraveloProperties.Tenant tenant,
                                            Map<String, RouteSourceAdapter> byType) {
        List<TenantDefinition.ResolvedSource> sources = new ArrayList<>();
        String disabledReason = tenant.sources().isEmpty() ? "no source configured" : null;
        for (CaraveloProperties.Source source : tenant.sources()) {
            RouteSourceAdapter adapter = byType.get(source.adapter());
            if (adapter == null) {
                disabledReason = "unknown adapter type '%s' (known: %s)".formatted(source.adapter(), byType.keySet());
                break;
            }
            TenantSourceConfig config = new TenantSourceConfig(
                    id, source.adapter(), source.url(), source.apiKey(), tenant.airlineIata(), source.mapping());
            try {
                adapter.validate(config);
            } catch (RuntimeException e) {
                disabledReason = "invalid config for adapter '%s': %s".formatted(source.adapter(), e.getMessage());
                break;
            }
            sources.add(new TenantDefinition.ResolvedSource(adapter, config));
        }
        if (disabledReason != null) {
            log.error("Tenant {} disabled: {}", id, disabledReason);
        }
        return new TenantDefinition(id, tenant.airlineIata(), tenant.syncCron(), tenant.guardThreshold(),
                tenant.staleAfter(),
                tenant.sources().stream().map(CaraveloProperties.Source::adapter).toList(),
                disabledReason == null ? List.copyOf(sources) : List.of(), disabledReason);
    }

    public Collection<TenantDefinition> all() {
        return tenants.values();
    }

    public Optional<TenantDefinition> find(String tenantId) {
        return Optional.ofNullable(tenants.get(tenantId));
    }
}
