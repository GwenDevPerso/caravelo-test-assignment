package com.caravelo.routesync.adapter.jsonmapping;

import static com.caravelo.routesync.core.RouteType.DIRECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.caravelo.routesync.core.CanonicalRoute;
import com.caravelo.routesync.core.MappingIssue;
import com.caravelo.routesync.core.SourceFetchException;
import com.caravelo.routesync.core.SourceHttpClient;
import com.caravelo.routesync.core.SourceResult;
import com.caravelo.routesync.core.TenantSourceConfig;
import com.caravelo.routesync.support.Json;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonMappingAdapterTest {

    private static final String TYPE = "json-mapping";

    /** Tenant C example mapping from SPEC §2. */
    private static final Map<String, String> TENANT_C_MAPPING =
            Map.of("items", "$.data.routes[*]", "origin", "$.from", "destination", "$.to");

    private final JsonMappingAdapter adapter = new JsonMappingAdapter(mock(SourceHttpClient.class));

    private static TenantSourceConfig config(Map<String, String> mapping) {
        return new TenantSourceConfig("tenant-c", TYPE, "http://unused", null, "XX", mapping);
    }

    private SourceResult map(String fixture) {
        return map(fixture, TENANT_C_MAPPING);
    }

    private SourceResult map(String fixture, Map<String, String> mapping) {
        return adapter.map(Json.fixture("fixtures/json-mapping/" + fixture), config(mapping));
    }

    private static CanonicalRoute direct(String origin, String destination) {
        return new CanonicalRoute(origin, destination, DIRECT, Set.of());
    }

    private static List<String> rules(SourceResult result) {
        return result.issues().stream().map(MappingIssue::rule).toList();
    }

    private static Map<String, String> with(String key, String path) {
        Map<String, String> mapping = new HashMap<>(TENANT_C_MAPPING);
        mapping.put(key, path);
        return mapping;
    }

    @Test
    @DisplayName("AC16 — type is json-mapping and covers DIRECT only")
    void typeAndCoveredTypes() {
        assertThat(adapter.type()).isEqualTo(TYPE);
        assertThat(adapter.coveredTypes()).containsExactly(DIRECT);
    }

    @Test
    @DisplayName("AC16 — Tenant C example mapping gives one DIRECT route per record, no issue")
    void tenantCExampleMapping() {
        SourceResult result = map("tenant-c-example.json");

        assertThat(result.sourceType()).isEqualTo(TYPE);
        assertThat(result.routes()).containsExactlyInAnyOrder(
                direct("MEX", "CUN"), direct("CUN", "MEX"), direct("GDL", "TIJ"));
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("AC16 — nested relative paths ($.start.code, $.legs[0].to) are resolved per record")
    void nestedRelativePaths() {
        SourceResult result = map("nested-legs.json",
                Map.of("items", "$.itineraries[*]", "origin", "$.start.code", "destination", "$.legs[0].to"));

        assertThat(result.routes()).containsExactlyInAnyOrder(direct("MEX", "GDL"), direct("CUN", "MTY"));
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("AC16 R1 — the same pair listed twice gives one route; the reverse direction is kept")
    void deduplicates() {
        assertThat(map("duplicate-routes.json").routes())
                .containsExactlyInAnyOrder(direct("MEX", "CUN"), direct("CUN", "MEX"));
    }

    @Test
    @DisplayName("AC16 R2 — lower-case codes with spaces are trimmed and upper-cased")
    void normalisesCodes() {
        SourceResult result = map("lowercase-codes.json");

        assertThat(result.routes()).containsExactly(direct("MEX", "CUN"));
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("AC16 R3 — 2 letters, digit, empty, null or missing code rejects the record with an issue, valid kept")
    void rejectsInvalidCodes() {
        SourceResult result = map("invalid-codes.json");

        assertThat(result.routes()).containsExactly(direct("MEX", "CUN"));
        assertThat(rules(result)).hasSize(6).containsOnly("R3");
    }

    @Test
    @DisplayName("AC16 R4 — origin equal to destination (after R2) is rejected with an issue")
    void rejectsSameOriginDestination() {
        SourceResult result = map("same-origin-destination.json");

        assertThat(result.routes()).containsExactly(direct("MEX", "CUN"));
        assertThat(rules(result)).containsExactly("R4");
    }

    @Test
    @DisplayName("AC16 — items path that is not an array throws SourceFetchException")
    void itemsNotArray() {
        assertThatThrownBy(() -> map("items-not-array.json"))
                .isInstanceOf(SourceFetchException.class);
    }

    @ParameterizedTest(name = "missing key {0}")
    @ValueSource(strings = {"items", "origin", "destination"})
    @DisplayName("AC16 §6.5 — mapping without items, origin or destination throws IllegalArgumentException")
    void missingMappingKey(String key) {
        Map<String, String> mapping = new HashMap<>(TENANT_C_MAPPING);
        mapping.remove(key);

        assertThatThrownBy(() -> map("tenant-c-example.json", mapping))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "items = {0}")
    @ValueSource(strings = {"data.routes[*]", "$..routes[*]", "$.data[*].routes", "$.data.routes[?(@.from)]"})
    @DisplayName("AC16 §6.5 — unsupported items path throws IllegalArgumentException")
    void unsupportedItemsPath(String path) {
        assertThatThrownBy(() -> map("tenant-c-example.json", with("items", path)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "origin = {0}")
    @ValueSource(strings = {"from", "$..from", "$.legs[*].to", "$['from']"})
    @DisplayName("AC16 §6.5 — unsupported record path ([*] outside items included) throws IllegalArgumentException")
    void unsupportedRecordPath(String path) {
        assertThatThrownBy(() -> map("tenant-c-example.json", with("origin", path)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
