package com.caravelo.routesync.adapter.tenanta;

import static com.caravelo.routesync.core.type.RouteType.CONNECTING;
import static com.caravelo.routesync.core.type.RouteType.DIRECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.caravelo.routesync.core.model.CanonicalRoute;
import com.caravelo.routesync.core.model.MappingIssue;
import com.caravelo.routesync.core.model.SourceResult;
import com.caravelo.routesync.core.service.SourceHttpClient;
import com.caravelo.routesync.core.type.RouteType;
import com.caravelo.routesync.support.Json;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantAStationsAdapterTest {

    private final TenantAStationsAdapter adapter = new TenantAStationsAdapter(mock(SourceHttpClient.class));

    private SourceResult map(String fixture) {
        return adapter.map(Json.fixture("fixtures/tenant-a/" + fixture), Json.config("tenant-a-stations", "Y4"));
    }

    private static CanonicalRoute route(String origin, String destination, RouteType type, String... via) {
        return new CanonicalRoute(origin, destination, type, Set.of(via));
    }

    private static List<String> rules(SourceResult result) {
        return result.issues().stream().map(MappingIssue::rule).toList();
    }

    @Test
    @DisplayName("§2 — type is tenant-a-stations and covers DIRECT and CONNECTING")
    void typeAndCoveredTypes() {
        assertThat(adapter.type()).isEqualTo("tenant-a-stations");
        assertThat(adapter.coveredTypes()).containsExactlyInAnyOrder(DIRECT, CONNECTING);
    }

    @Test
    @DisplayName("§3.2 — direct[] gives DIRECT with empty via, connection routeDirect 0 gives CONNECTING with split via")
    void mapsDirectAndConnecting() {
        SourceResult result = map("direct-and-connecting.json");

        assertThat(result.sourceType()).isEqualTo("tenant-a-stations");
        assertThat(result.routes()).containsExactlyInAnyOrder(
                route("ACA", "GDL", DIRECT),
                route("ACA", "AGU", CONNECTING, "TIJ", "MEX"));
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("R1 — same pair listed twice gives one route and a mapping issue")
    void deduplicatesPairs() {
        SourceResult result = map("duplicate-route.json");

        assertThat(result.routes()).containsExactlyInAnyOrder(
                route("ACA", "GDL", DIRECT),
                route("ACA", "AGU", CONNECTING, "TIJ"));
        assertThat(rules(result)).contains("R1");
    }

    @Test
    @DisplayName("R1 — duplicate inside connection[] with routeDirect 1: one DIRECT route, exactly one R1 issue")
    void duplicateDirectInConnectionList() {
        SourceResult result = map("duplicate-connection-direct.json");

        assertThat(result.routes()).containsExactly(route("ACA", "GDL", DIRECT));
        assertThat(rules(result).stream().filter("R1"::equals)).hasSize(1);
        assertThat(rules(result)).doesNotContain("R9");
    }

    @Test
    @DisplayName("R1 — two CONNECTING duplicates with different connectionStation: via is the union, one R1 issue")
    void duplicateConnectingUnionsVia() {
        SourceResult result = map("duplicate-connecting-different-via.json");

        assertThat(result.routes()).containsExactly(route("ACA", "AGU", CONNECTING, "TIJ", "MEX"));
        assertThat(rules(result).stream().filter("R1"::equals)).hasSize(1);
    }

    @Test
    @DisplayName("R2 — lower-case codes with spaces are trimmed and upper-cased, via included")
    void normalisesCodes() {
        SourceResult result = map("lowercase-codes.json");

        assertThat(result.routes()).containsExactlyInAnyOrder(
                route("ACA", "GDL", DIRECT),
                route("ACA", "CUN", CONNECTING, "MEX", "TIJ"));
    }

    @Test
    @DisplayName("R3 — invalid or null stationCode rejects the station with an issue, valid station still mapped")
    void rejectsInvalidStationCode() {
        SourceResult result = map("invalid-station-code.json");

        assertThat(result.routes()).containsExactly(route("MEX", "GDL", DIRECT));
        assertThat(rules(result)).contains("R3");
    }

    @Test
    @DisplayName("R3 — invalid, empty or null arrivalStation rejects the route with an issue, others kept")
    void rejectsInvalidArrivalCode() {
        SourceResult result = map("invalid-arrival-code.json");

        assertThat(result.routes()).containsExactly(route("ACA", "GDL", DIRECT));
        assertThat(rules(result)).contains("R3");
    }

    @Test
    @DisplayName("R4 — origin equal to destination is rejected with an issue")
    void rejectsSameOriginAndDestination() {
        SourceResult result = map("origin-equals-destination.json");

        assertThat(result.routes()).containsExactly(route("ACA", "GDL", DIRECT));
        assertThat(rules(result)).contains("R4");
    }

    @Test
    @DisplayName("R6 — a route in one direction is kept, the return route is never inferred")
    void neverInfersReturnRoute() {
        SourceResult result = map("one-direction.json");

        assertThat(result.routes()).containsExactly(route("ACA", "GDL", DIRECT));
    }

    @Test
    @DisplayName("R8 — stations with null, empty or missing route lists give no route")
    void stationWithoutRoutes() {
        SourceResult result = map("station-without-routes.json");

        assertThat(result.routes()).isEmpty();
    }

    @Test
    @DisplayName("R9 — direct[] and connection[] routeDirect 1 disagree: union, all DIRECT with empty via, issue logged")
    void unionOfDisagreeingDirectLists() {
        SourceResult result = map("direct-lists-disagree.json");

        assertThat(result.routes()).containsExactlyInAnyOrder(
                route("ACA", "GDL", DIRECT),
                route("ACA", "MEX", DIRECT));
        assertThat(rules(result)).contains("R9");
    }

    @Test
    @DisplayName("§3.1 — a pair both direct and connecting is DIRECT")
    void directWinsOverConnecting() {
        SourceResult result = map("direct-and-connecting-same-pair.json");

        assertThat(result.routes()).containsExactly(route("ACA", "GDL", DIRECT));
    }

    @Test
    @DisplayName("R10 — routeName not matching the codes: codes win, issue logged")
    void routeNameMismatch() {
        SourceResult result = map("route-name-mismatch.json");

        assertThat(result.routes()).containsExactly(route("ACA", "GDL", DIRECT));
        assertThat(rules(result)).contains("R10");
    }

    @Test
    @DisplayName("R11 — empty, invalid, origin or destination codes dropped from via, route kept, issue per route")
    void badConnectionStations() {
        SourceResult result = map("bad-connection-station.json");

        assertThat(result.routes()).containsExactlyInAnyOrder(
                route("ACA", "AGU", CONNECTING),
                route("ACA", "BJX", CONNECTING, "GDL"),
                route("ACA", "CEN", CONNECTING, "MEX"),
                route("ACA", "CUN", CONNECTING, "MEX"));
        assertThat(rules(result).stream().filter("R11"::equals)).hasSize(4);
    }

    @Test
    @DisplayName("AC4 — duplicates, invalid code and bad routeName: only valid unique routes, each problem an issue")
    void ac4MixedPayload() {
        SourceResult result = map("ac4-mixed.json");

        assertThat(result.routes()).containsExactlyInAnyOrder(
                route("ACA", "GDL", DIRECT),
                route("ACA", "TIJ", DIRECT));
        assertThat(rules(result)).contains("R1", "R3", "R10");
    }

    @Test
    @DisplayName("§3.2 — real sample: ACA has 3 DIRECT (GDL, MEX, TIJ) with empty via and 55 CONNECTING")
    void realSampleAca() {
        SourceResult result = adapter.map(Json.file(Path.of("tenant1.json")), Json.config("tenant-a-stations", "Y4"));

        List<CanonicalRoute> aca = result.routes().stream().filter(r -> r.origin().equals("ACA")).toList();
        assertThat(aca).hasSize(58);
        assertThat(aca.stream().filter(r -> r.type() == DIRECT))
                .containsExactlyInAnyOrder(
                        route("ACA", "GDL", DIRECT),
                        route("ACA", "MEX", DIRECT),
                        route("ACA", "TIJ", DIRECT));
        assertThat(aca.stream().filter(r -> r.type() == CONNECTING)).hasSize(55);
    }

    @Test
    @DisplayName("§3.1 — real sample: every route has valid codes, origin ≠ destination, DIRECT has empty via")
    void realSampleCanonicalRules() {
        SourceResult result = adapter.map(Json.file(Path.of("tenant1.json")), Json.config("tenant-a-stations", "Y4"));

        assertThat(result.routes()).isNotEmpty().allSatisfy(r -> {
            assertThat(r.origin()).matches("^[A-Z]{3}$");
            assertThat(r.destination()).matches("^[A-Z]{3}$").isNotEqualTo(r.origin());
            assertThat(r.via()).allSatisfy(v -> assertThat(v).matches("^[A-Z]{3}$")
                    .isNotEqualTo(r.origin()).isNotEqualTo(r.destination()));
            if (r.type() == DIRECT) {
                assertThat(r.via()).isEmpty();
            }
        });
    }
}
