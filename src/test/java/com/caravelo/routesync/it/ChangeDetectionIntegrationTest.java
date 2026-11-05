package com.caravelo.routesync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class ChangeDetectionIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("§6.1 — first sync: every route recorded as ADDED, old fields null")
    void firstSyncRecordsEveryRouteAsAdded() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-v1.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("added").asInt()).isEqualTo(6);
        assertThat(run.path("removed").asInt()).isZero();
        assertThat(run.path("modified").asInt()).isZero();
        JsonNode changes = changes("tenant-a");
        assertThat(changes.size()).isEqualTo(6);
        for (JsonNode c : changes) {
            assertThat(c.path("changeType").asString()).isEqualTo("ADDED");
            assertThat(c.path("runId").asString()).isEqualTo(run.path("runId").asString());
            assertThat(c.path("oldType").isNull()).as("oldType of ADDED is null").isTrue();
            assertThat(c.path("newType").isNull()).isFalse();
        }
    }

    @Test
    @DisplayName("AC5 — one ADDED, one REMOVED, one MODIFIED change recorded in the run")
    void ac5_runCountsOneOfEach() throws Exception {
        seedTenantA();
        stubOk(TENANT_A_PATH, "tenant-a-v2.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(run.path("added").asInt()).isEqualTo(1);
        assertThat(run.path("removed").asInt()).isEqualTo(1);
        assertThat(run.path("modified").asInt()).isEqualTo(1);
        assertThat(compact(routes("tenant-a")))
                .isEqualTo("{\"ACA\":[\"BOG\",\"CUN\",\"GDL\",\"MEX\",\"TIJ\"],\"MEX\":[\"ACA\"]}");
    }

    @Test
    @DisplayName("AC5 — the changes endpoint exposes the three changes newest first, with old and new values")
    void ac5_changesEndpointExposesTheThreeChanges() throws Exception {
        seedTenantA();
        stubOk(TENANT_A_PATH, "tenant-a-v2.json");
        String runId = sync("tenant-a").path("runId").asString();

        JsonNode changes = changes("tenant-a");

        assertThat(changes.size()).isEqualTo(9);
        List<JsonNode> latest = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            assertThat(changes.get(i).path("runId").asString()).as("newest first").isEqualTo(runId);
            latest.add(changes.get(i));
        }
        assertThat(latest).extracting(c -> c.path("changeType").asString())
                .containsExactlyInAnyOrder("ADDED", "REMOVED", "MODIFIED");

        JsonNode added = findChange(changes, "ACA", "TIJ");
        assertThat(added.path("changeType").asString()).isEqualTo("ADDED");
        assertThat(added.path("oldType").isNull()).isTrue();
        assertThat(added.path("newType").asString()).isEqualTo("DIRECT");

        JsonNode removed = findChange(changes, "MEX", "BOG");
        assertThat(removed.path("changeType").asString()).isEqualTo("REMOVED");
        assertThat(removed.path("oldType").asString()).isEqualTo("DIRECT");
        assertThat(removed.path("newType").isNull()).isTrue();

        JsonNode modified = latest.stream().filter(c -> "MODIFIED".equals(c.path("changeType").asString()))
                .findFirst().orElseThrow();
        assertThat(modified.path("origin").asString()).isEqualTo("ACA");
        assertThat(modified.path("destination").asString()).isEqualTo("CUN");
        assertThat(modified.path("oldType").asString()).isEqualTo("CONNECTING");
        assertThat(modified.path("newType").asString()).isEqualTo("DIRECT");
        assertThat(strings(modified.path("oldVia"))).containsExactlyInAnyOrder("GDL", "MEX");
        assertThat(strings(modified.path("newVia"))).isEmpty();
        assertThat(modified.path("detectedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("AC6 — same source data synced twice: second run records 0 changes")
    void ac6_secondRunRecordsNoChange() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-v1.json");
        sync("tenant-a");

        JsonNode second = sync("tenant-a");

        assertThat(second.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(second.path("added").asInt()).isZero();
        assertThat(second.path("removed").asInt()).isZero();
        assertThat(second.path("modified").asInt()).isZero();
        assertThat(changes("tenant-a").size()).isEqualTo(6);
        assertThat(count("route_change", "tenant-a")).isEqualTo(6);
    }

    @Test
    @DisplayName("AC6 — same source data synced twice: stored network identical, no duplicate rows")
    void ac6_storedNetworkIdentical() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-v1.json");
        sync("tenant-a");
        String before = compact(routes("tenant-a"));

        sync("tenant-a");

        assertThat(compact(routes("tenant-a"))).isEqualTo(before).isEqualTo(TENANT_A_V1_ROUTES);
        assertThat(count("route", "tenant-a")).isEqualTo(6);
    }

    @Test
    @DisplayName("§6.2 — changes?limit=2 returns the 2 newest changes")
    void changesLimit() throws Exception {
        seedTenantA();

        assertThat(getJson("/api/tenants/tenant-a/changes?limit=2").size()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "abc"})
    @DisplayName("§6.2 — changes?limit outside 1–1000 returns 400 problem+json naming the parameter")
    void changesLimitOutOfRangeReturns400(String limit) throws Exception {
        mvc.perform(get("/api/tenants/tenant-a/changes").param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.parameter").value("limit"));
    }
}
