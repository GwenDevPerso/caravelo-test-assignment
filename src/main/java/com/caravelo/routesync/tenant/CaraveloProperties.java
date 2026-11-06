package com.caravelo.routesync.tenant;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tenants as configuration ({@code caravelo.tenants.<id>}, SPEC §2). */
@ConfigurationProperties("caravelo")
public record CaraveloProperties(Map<String, Tenant> tenants) {

    public CaraveloProperties {
        tenants = tenants == null ? Map.of() : tenants;
    }

    public record Tenant(
            String airlineIata,
            @DefaultValue("0 0 3 * * *") String syncCron,
            @DefaultValue("0.5") double guardThreshold,
            @DefaultValue("48h") Duration staleAfter,
            List<Source> sources) {

        public Tenant {
            sources = sources == null ? List.of() : sources;
        }
    }

    public record Source(String adapter, String url, String apiKey, Map<String, String> mapping) {}
}
