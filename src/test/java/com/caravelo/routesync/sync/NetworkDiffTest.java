package com.caravelo.routesync.sync;

import static com.caravelo.routesync.core.RouteType.CONNECTING;
import static com.caravelo.routesync.core.RouteType.DIRECT;
import static org.assertj.core.api.Assertions.assertThat;

import com.caravelo.routesync.core.CanonicalRoute;
import com.caravelo.routesync.core.RouteType;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetworkDiffTest {

    private static final Set<RouteType> ALL = Set.of(DIRECT, CONNECTING);

    private static CanonicalRoute route(String origin, String destination, RouteType type, String... via) {
        return new CanonicalRoute(origin, destination, type, Set.of(via));
    }

    @Test
    @DisplayName("AC5 — one ADDED, one REMOVED, one MODIFIED (CONNECTING to DIRECT) change")
    void addedRemovedModified() {
        List<CanonicalRoute> stored = List.of(
                route("ACA", "GDL", DIRECT),
                route("ACA", "MEX", DIRECT),
                route("ACA", "CUN", CONNECTING, "MEX"));
        Set<CanonicalRoute> snapshot = Set.of(
                route("ACA", "GDL", DIRECT),
                route("ACA", "TIJ", DIRECT),
                route("ACA", "CUN", DIRECT));

        List<RouteChange> changes = NetworkDiff.compute(stored, snapshot, ALL);

        assertThat(changes).containsExactlyInAnyOrder(
                new RouteChange(ChangeType.ADDED, "ACA", "TIJ", null, DIRECT, null, Set.of()),
                new RouteChange(ChangeType.REMOVED, "ACA", "MEX", DIRECT, null, Set.of(), null),
                new RouteChange(ChangeType.MODIFIED, "ACA", "CUN", CONNECTING, DIRECT, Set.of("MEX"), Set.of()));
    }

    @Test
    @DisplayName("§6.1 — same type with a different via set is MODIFIED with old and new via")
    void viaChangeIsModified() {
        List<RouteChange> changes = NetworkDiff.compute(
                List.of(route("ACA", "AGU", CONNECTING, "MEX")),
                Set.of(route("ACA", "AGU", CONNECTING, "GDL")),
                ALL);

        assertThat(changes).containsExactly(new RouteChange(ChangeType.MODIFIED, "ACA", "AGU",
                CONNECTING, CONNECTING, Set.of("MEX"), Set.of("GDL")));
    }

    @Test
    @DisplayName("AC6 — identical stored network and snapshot give no change")
    void identicalGivesNoChange() {
        Set<CanonicalRoute> network = Set.of(route("ACA", "GDL", DIRECT), route("ACA", "AGU", CONNECTING, "MEX", "TIJ"));

        assertThat(NetworkDiff.compute(List.copyOf(network), network, ALL)).isEmpty();
    }

    @Test
    @DisplayName("AC6 — equal content in other Set instances and order gives no change")
    void equalContentGivesNoChange() {
        List<CanonicalRoute> stored = List.of(
                new CanonicalRoute("ACA", "AGU", CONNECTING, new LinkedHashSet<>(List.of("TIJ", "MEX"))),
                route("ACA", "GDL", DIRECT));
        Set<CanonicalRoute> snapshot = new HashSet<>(List.of(
                route("ACA", "GDL", DIRECT),
                new CanonicalRoute("ACA", "AGU", CONNECTING, new HashSet<>(List.of("MEX", "TIJ")))));

        assertThat(NetworkDiff.compute(stored, snapshot, ALL)).isEmpty();
    }

    @Test
    @DisplayName("§6.1 — first sync with nothing stored: every route ADDED")
    void firstSyncAllAdded() {
        List<RouteChange> changes = NetworkDiff.compute(List.of(),
                Set.of(route("ACA", "GDL", DIRECT), route("ACA", "AGU", CONNECTING, "MEX")), ALL);

        assertThat(changes).containsExactlyInAnyOrder(
                new RouteChange(ChangeType.ADDED, "ACA", "GDL", null, DIRECT, null, Set.of()),
                new RouteChange(ChangeType.ADDED, "ACA", "AGU", null, CONNECTING, null, Set.of("MEX")));
    }

    @Test
    @DisplayName("AC12 — DIRECT-only source: stored CONNECTING missing from snapshot is not REMOVED, stored DIRECT is")
    void uncoveredTypesNeverRemoved() {
        List<RouteChange> changes = NetworkDiff.compute(
                List.of(route("ACA", "GDL", DIRECT), route("ACA", "MEX", DIRECT), route("ACA", "AGU", CONNECTING, "MEX")),
                Set.of(route("ACA", "GDL", DIRECT)),
                Set.of(DIRECT));

        assertThat(changes).containsExactly(
                new RouteChange(ChangeType.REMOVED, "ACA", "MEX", DIRECT, null, Set.of(), null));
    }

    @Test
    @DisplayName("AC12 — DIRECT-only source seeing a stored CONNECTING pair as DIRECT records MODIFIED")
    void connectingSeenAsDirectIsModified() {
        List<RouteChange> changes = NetworkDiff.compute(
                List.of(route("ACA", "CUN", CONNECTING, "MEX")),
                Set.of(route("ACA", "CUN", DIRECT)),
                Set.of(DIRECT));

        assertThat(changes).containsExactly(new RouteChange(ChangeType.MODIFIED, "ACA", "CUN",
                CONNECTING, DIRECT, Set.of("MEX"), Set.of()));
    }
}
