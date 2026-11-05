package com.caravelo.routesync.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.caravelo.routesync.core.RouteSourceAdapter;
import com.caravelo.routesync.core.RouteType;
import com.caravelo.routesync.persistence.RouteRepository;
import com.caravelo.routesync.persistence.SyncRunRepository;
import com.caravelo.routesync.tenant.CaraveloProperties;
import com.caravelo.routesync.tenant.TenantRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SPEC §6.1 "Stale": no SUCCESS/DEGRADED run for longer than the stale threshold, or none at all. */
class HealthServiceTest {

    private static final String TENANT = "tenant-x";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private final RouteRepository routes = mock(RouteRepository.class);
    private final SyncRunRepository runs = mock(SyncRunRepository.class);

    private HealthService service;

    @BeforeEach
    void setUp() {
        RouteSourceAdapter adapter = mock(RouteSourceAdapter.class);
        when(adapter.type()).thenReturn("fake");
        when(adapter.coveredTypes()).thenReturn(Set.of(RouteType.DIRECT));
        CaraveloProperties properties = new CaraveloProperties(Map.of(TENANT, new CaraveloProperties.Tenant(
                "Y4", null, 0.5, Duration.ofHours(12),
                List.of(new CaraveloProperties.Source("fake", "http://unused", null, Map.of())))));
        when(routes.count(TENANT)).thenReturn(3);
        when(runs.errorCount(TENANT)).thenReturn(0);
        service = new HealthService(new TenantRegistry(properties, List.of(adapter)), runs, routes,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void lastSuccessAt(Instant at) {
        SyncRunView run = new SyncRunView(UUID.randomUUID(), TENANT, SyncStatus.SUCCESS, "fake",
                at.minusSeconds(1), at, 3, 3, 0, 0, 0, null);
        when(runs.latest(TENANT)).thenReturn(Optional.of(run));
        when(runs.latestSuccessful(TENANT)).thenReturn(Optional.of(run));
    }

    @Test
    @DisplayName("§6.1 — last success older than stale-after (12 h): stale")
    void staleWhenLastSuccessTooOld() {
        lastSuccessAt(NOW.minus(Duration.ofHours(13)));

        assertThat(service.health(TENANT).stale()).isTrue();
    }

    @Test
    @DisplayName("§6.1 — last success within stale-after (12 h): not stale")
    void notStaleWhenLastSuccessRecent() {
        lastSuccessAt(NOW.minus(Duration.ofHours(1)));

        TenantHealth health = service.health(TENANT);

        assertThat(health.stale()).isFalse();
        assertThat(health.lastSuccessAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("§6.1 — no successful run at all: stale")
    void staleWhenNoSuccess() {
        when(runs.latest(TENANT)).thenReturn(Optional.empty());
        when(runs.latestSuccessful(TENANT)).thenReturn(Optional.empty());

        TenantHealth health = service.health(TENANT);

        assertThat(health.stale()).isTrue();
        assertThat(health.lastSuccessAt()).isNull();
    }
}
