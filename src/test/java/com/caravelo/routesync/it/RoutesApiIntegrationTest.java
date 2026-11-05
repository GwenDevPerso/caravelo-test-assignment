package com.caravelo.routesync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class RoutesApiIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("AC1 — Tenant A sync stores direct and connecting routes, returned grouped by origin and sorted")
    void ac1_tenantASyncStoresRoutesGroupedByOrigin() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-v1.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(run.path("sourceUsed").asString()).isEqualTo("tenant-a-stations");
        assertThat(run.path("tenantId").asString()).isEqualTo("tenant-a");
        assertThat(run.path("routeCount").asInt()).isEqualTo(6);
        assertThat(compact(routes("tenant-a"))).isEqualTo(TENANT_A_V1_ROUTES);
    }

    @Test
    @DisplayName("AC1 — connecting route stored with its via stations (\"MEX,GDL\" split)")
    void ac1_connectingRouteStoredWithVia() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-v1.json");
        sync("tenant-a");

        List<String> types = jdbc.sql("SELECT type FROM route WHERE tenant_id = 'tenant-a' ORDER BY origin, destination")
                .query(String.class).list();
        assertThat(types).containsExactly("CONNECTING", "CONNECTING", "DIRECT", "DIRECT", "DIRECT", "DIRECT");

        JsonNode cun = findChange(changes("tenant-a"), "ACA", "CUN");
        assertThat(cun.path("newType").asString()).isEqualTo("CONNECTING");
        assertThat(strings(cun.path("newVia"))).containsExactlyInAnyOrder("GDL", "MEX");
    }

    @Test
    @DisplayName("AC4 — partial payload (duplicate, invalid code, origin = destination, bad routeName): SUCCESS, valid routes stored, issueCount 4")
    void ac4_partialPayloadEndToEnd() throws Exception {
        // ACA: GDL twice (R1), MEXX (R3), ACA (R4), TIJ with routeName ACAXXX (R10); MEX: ACA valid
        stubOk(TENANT_A_PATH, "tenant-a-partial.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(run.path("routeCount").asInt()).isEqualTo(3);
        assertThat(run.path("issueCount").asInt()).isEqualTo(4);
        assertThat(compact(routes("tenant-a"))).isEqualTo("{\"ACA\":[\"GDL\",\"TIJ\"],\"MEX\":[\"ACA\"]}");
        assertThat(count("route", "tenant-a")).isEqualTo(3);
    }

    @Test
    @DisplayName("AC3 — ?origin=ACA returns only destinations from ACA")
    void ac3_originFilter() throws Exception {
        seedTenantA();

        assertThat(compact(getJson("/api/tenants/tenant-a/routes?origin=ACA")))
                .isEqualTo("{\"ACA\":[\"BOG\",\"CUN\",\"GDL\",\"MEX\"]}");
    }

    @Test
    @DisplayName("AC3 — ?type=DIRECT returns only non-stop routes")
    void ac3_typeDirectFilter() throws Exception {
        seedTenantA();

        assertThat(compact(getJson("/api/tenants/tenant-a/routes?type=DIRECT")))
                .isEqualTo("{\"ACA\":[\"GDL\",\"MEX\"],\"MEX\":[\"ACA\",\"BOG\"]}");
    }

    @Test
    @DisplayName("AC3 — ?destination=BOG returns only routes arriving at BOG")
    void ac3_destinationFilter() throws Exception {
        seedTenantA();

        assertThat(compact(getJson("/api/tenants/tenant-a/routes?destination=BOG")))
                .isEqualTo("{\"ACA\":[\"BOG\"],\"MEX\":[\"BOG\"]}");
    }

    @Test
    @DisplayName("AC3 — combined origin + type filters")
    void ac3_originAndTypeFilters() throws Exception {
        seedTenantA();

        assertThat(compact(getJson("/api/tenants/tenant-a/routes?origin=ACA&type=CONNECTING")))
                .isEqualTo("{\"ACA\":[\"BOG\",\"CUN\"]}");
    }

    @Test
    @DisplayName("AC3 — lower-case codes in query parameters are normalised (R2)")
    void ac3_lowerCaseCodesNormalised() throws Exception {
        seedTenantA();

        assertThat(compact(getJson("/api/tenants/tenant-a/routes?origin=aca")))
                .isEqualTo("{\"ACA\":[\"BOG\",\"CUN\",\"GDL\",\"MEX\"]}");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "origin, AC",
            "origin, A1C",
            "origin, ACAX",
            "destination, 12",
            "destination, M-X",
            "type, NONSTOP"
    })
    @DisplayName("AC3 — an invalid code or type returns 400 problem+json naming the parameter")
    void ac3_invalidParameterReturns400(String parameter, String value) throws Exception {
        mvc.perform(get("/api/tenants/tenant-a/routes").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.parameter").value(parameter));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/routes", "/changes", "/health"})
    @DisplayName("§6.2 — unknown tenant returns 404 problem+json on GET endpoints")
    void unknownTenantReturns404OnGet(String suffix) throws Exception {
        mvc.perform(get("/api/tenants/no-such-tenant" + suffix))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("§6.2 — unknown tenant returns 404 problem+json on sync")
    void unknownTenantReturns404OnSync() throws Exception {
        mvc.perform(post("/api/tenants/no-such-tenant/sync"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
