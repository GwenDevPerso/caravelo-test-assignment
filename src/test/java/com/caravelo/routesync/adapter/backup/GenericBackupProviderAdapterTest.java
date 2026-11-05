package com.caravelo.routesync.adapter.backup;

import static com.caravelo.routesync.core.RouteType.DIRECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.caravelo.routesync.core.CanonicalRoute;
import com.caravelo.routesync.core.MappingIssue;
import com.caravelo.routesync.core.SourceHttpClient;
import com.caravelo.routesync.core.SourceResult;
import com.caravelo.routesync.support.Json;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GenericBackupProviderAdapterTest {

    private static final String TYPE = "generic-backup-provider";

    private final GenericBackupProviderAdapter adapter = new GenericBackupProviderAdapter(mock(SourceHttpClient.class));

    private SourceResult map(String fixture) {
        return adapter.map(Json.fixture("fixtures/backup/" + fixture), Json.config(TYPE, "Y4"));
    }

    private static CanonicalRoute direct(String origin, String destination) {
        return new CanonicalRoute(origin, destination, DIRECT, Set.of());
    }

    private static List<String> rules(SourceResult result) {
        return result.issues().stream().map(MappingIssue::rule).toList();
    }

    @Test
    @DisplayName("§2 — type is generic-backup-provider and covers DIRECT only")
    void typeAndCoveredTypes() {
        assertThat(adapter.type()).isEqualTo(TYPE);
        assertThat(adapter.coveredTypes()).containsExactly(DIRECT);
    }

    @Test
    @DisplayName("§3.3 — one flight gives one DIRECT route with empty via")
    void singleFlight() {
        SourceResult result = map("single-flight.json");

        assertThat(result.sourceType()).isEqualTo(TYPE);
        assertThat(result.routes()).containsExactly(direct("BOG", "MEX"));
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("R1 — several flights on the same pair give one route per direction")
    void deduplicatesFlights() {
        assertThat(map("duplicate-flights.json").routes())
                .containsExactlyInAnyOrder(direct("BOG", "MEX"), direct("MEX", "BOG"));
    }

    @Test
    @DisplayName("R2 — lower-case codes with spaces are trimmed and upper-cased")
    void normalisesCodes() {
        assertThat(map("lowercase-codes.json").routes()).containsExactly(direct("BOG", "MEX"));
    }

    @Test
    @DisplayName("R3 — codes of 2 letters, empty, null or with a digit reject the record with an issue, valid kept")
    void rejectsInvalidCodes() {
        SourceResult result = map("invalid-codes.json");

        assertThat(result.routes()).containsExactly(direct("CUN", "MEX"));
        assertThat(rules(result)).contains("R3");
    }

    @Test
    @DisplayName("R3 — *_iata null or missing with only *_icao set: record rejected with an issue")
    void rejectsIcaoOnly() {
        SourceResult result = map("icao-only.json");

        assertThat(result.routes()).containsExactly(direct("CUN", "MEX"));
        assertThat(rules(result)).contains("R3");
    }

    @Test
    @DisplayName("R4 — origin equal to destination is rejected with an issue")
    void rejectsSameOriginAndDestination() {
        SourceResult result = map("same-origin-destination.json");

        assertThat(result.routes()).containsExactly(direct("BOG", "MEX"));
        assertThat(rules(result)).contains("R4");
    }

    @Test
    @DisplayName("R5 — a flight of another airline is ignored")
    void ignoresOtherAirline() {
        assertThat(map("other-airline.json").routes()).containsExactly(direct("BOG", "MEX"));
    }

    @Test
    @DisplayName("§3.3 — airline_iata is compared after trim and upper-case")
    void airlineComparedCaseInsensitively() {
        assertThat(map("airline-case.json").routes()).containsExactly(direct("BOG", "MEX"));
    }

    @Test
    @DisplayName("R13 — a record with any of cs_airline_iata, cs_flight_iata, cs_flight_number set is ignored")
    void ignoresCodeshares() {
        assertThat(map("codeshare.json").routes()).containsExactly(direct("BOG", "MEX"));
    }

    @Test
    @DisplayName("§3.3 — days empty, missing or null: flight not operating, ignored")
    void ignoresNonOperatingFlights() {
        assertThat(map("days-empty-or-missing.json").routes()).containsExactly(direct("BOG", "MEX"));
    }

    @Test
    @DisplayName("§6.4 — an object with a response array maps like a plain array")
    void wrappedResponse() {
        assertThat(map("wrapped-response.json").routes()).containsExactly(direct("BOG", "MEX"));
    }

    @Test
    @DisplayName("AC2 — only Y4, non-codeshare, operating flights become routes, de-duplicated by pair")
    void ac2MixedPayload() {
        assertThat(map("ac2-mixed.json").routes())
                .containsExactlyInAnyOrder(direct("BOG", "MEX"), direct("MEX", "BOG"));
    }

    @Test
    @DisplayName("AC2 — real sample: 205 unique DIRECT routes for Y4")
    void realSample() {
        SourceResult result = adapter.map(Json.file(Path.of("tenant2.json")), Json.config(TYPE, "Y4"));

        assertThat(result.routes()).hasSize(205).allSatisfy(r -> {
            assertThat(r.type()).isEqualTo(DIRECT);
            assertThat(r.via()).isEmpty();
        });
    }

    @Test
    @DisplayName("R5 — real sample filtered on another airline gives no route")
    void realSampleOtherAirline() {
        SourceResult result = adapter.map(Json.file(Path.of("tenant2.json")), Json.config(TYPE, "XX"));

        assertThat(result.routes()).isEmpty();
    }
}
