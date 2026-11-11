package com.caravelo.routesync.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.caravelo.routesync.core.model.CanonicalRoute;
import com.caravelo.routesync.core.model.SourceResult;
import com.caravelo.routesync.core.service.RouteSourceAdapter;
import com.caravelo.routesync.core.type.RouteType;
import com.caravelo.routesync.persistence.NetworkStore;
import com.caravelo.routesync.persistence.RouteRepository;
import com.caravelo.routesync.persistence.SyncRunRepository;
import com.caravelo.routesync.sync.model.SyncRunView;
import com.caravelo.routesync.sync.type.SyncStatus;
import com.caravelo.routesync.tenant.CaraveloProperties;
import com.caravelo.routesync.tenant.TenantRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** SPEC §4.4 "Database unavailable" and §6.1: the run is reported FAILED, nothing escapes, stored network untouched. */
class SyncServiceTest {

    private static final String TENANT = "tenant-x";

    private final RouteSourceAdapter adapter = mock(RouteSourceAdapter.class);
    private final RouteRepository routes = mock(RouteRepository.class);
    private final SyncRunRepository runs = mock(SyncRunRepository.class);
    private final NetworkStore store = mock(NetworkStore.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);

    private SyncService service;

    @BeforeEach
    void setUp() {
        when(adapter.type()).thenReturn("fake");
        when(adapter.coveredTypes()).thenReturn(Set.of(RouteType.DIRECT));
        when(adapter.fetch(any())).thenReturn(new SourceResult("fake",
                Set.of(new CanonicalRoute("MEX", "CUN", RouteType.DIRECT, Set.of())), List.of()));
        when(routes.findAll(anyString())).thenReturn(List.of());
        when(routes.count(anyString())).thenReturn(0);

        CaraveloProperties properties = new CaraveloProperties(Map.of(TENANT, new CaraveloProperties.Tenant(
                "Y4", null, 0.5, Duration.ofHours(12),
                List.of(new CaraveloProperties.Source("fake", "http://unused", null, Map.of())))));
        TenantRegistry registry = new TenantRegistry(properties, List.of(adapter));
        service = new SyncService(registry, routes, runs, store, clock);
    }

    @Test
    @DisplayName("§4.4 — database unavailable when the run is started: run FAILED with an error, no exception escapes")
    void databaseDownAtStart() {
        doThrow(new DataAccessResourceFailureException("connection refused")).when(runs).start(any(), anyString(), any());

        SyncRunView run = service.sync(TENANT);

        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.tenantId()).isEqualTo(TENANT);
        assertThat(run.error()).isNotBlank();
    }

    @Test
    @DisplayName("§4.4 — database unavailable when the run is started: stored network not touched")
    void databaseDownAtStartDoesNotApply() {
        doThrow(new DataAccessResourceFailureException("connection refused")).when(runs).start(any(), anyString(), any());

        service.sync(TENANT);

        verify(store, never()).apply(any(), anyList());
    }

    @Test
    @DisplayName("§4.4 — database unavailable when applying the snapshot: run FAILED with an error, no exception escapes")
    void databaseDownAtApply() {
        doThrow(new DataAccessResourceFailureException("connection lost")).when(store).apply(any(), anyList());

        SyncRunView run = service.sync(TENANT);

        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.tenantId()).isEqualTo(TENANT);
        assertThat(run.error()).isNotBlank();
    }
}
