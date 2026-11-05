package com.caravelo.routesync.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.http.Fault;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class ResilienceIntegrationTest extends IntegrationTestBase {

    // --- AC7 timeout ---

    @Test
    @DisplayName("AC7 — primary times out: called 3 times, fallback used, run DEGRADED")
    void ac7_timeoutRetriedThenFallback() throws Exception {
        stub(TENANT_A_PATH, aResponse().withStatus(200).withFixedDelay(3000).withBody(fixture("tenant-a-v1.json")));
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(3, TENANT_A_PATH);
        assertRequested(1, BACKUP_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
        assertThat(run.path("sourceUsed").asString()).isEqualTo("generic-backup-provider");
    }

    // --- AC8 auth ---

    @Test
    @DisplayName("AC8 — primary returns 401: called once, fallback used, run DEGRADED")
    void ac8_unauthorizedNotRetried() throws Exception {
        stubStatus(TENANT_A_PATH, 401);
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(1, TENANT_A_PATH);
        assertRequested(1, BACKUP_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
        assertThat(run.path("sourceUsed").asString()).isEqualTo("generic-backup-provider");
    }

    @Test
    @DisplayName("AC8 — primary returns 403: called once, fallback used")
    void ac8_forbiddenNotRetried() throws Exception {
        stubStatus(TENANT_A_PATH, 403);
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(1, TENANT_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
    }

    // --- AC9 malformed ---

    @Test
    @DisplayName("AC9 — truncated JSON from primary: not retried, nothing from it persisted, fallback used")
    void ac9_malformedJsonNothingPersisted() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-malformed.json");
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(1, TENANT_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
        assertThat(jdbc.sql("SELECT count(*) FROM route WHERE tenant_id = 'tenant-a' AND (origin = 'PVR' OR destination = 'PVR')")
                .query(Long.class).single()).isZero();
        assertThat(compact(routes("tenant-a")))
                .isEqualTo("{\"ACA\":[\"GDL\",\"MEX\"],\"GDL\":[\"TIJ\"],\"MEX\":[\"ACA\",\"BOG\"]}");
    }

    @Test
    @DisplayName("AC9 — unexpected shape (object instead of station array): not retried, fallback used")
    void ac9_unexpectedShape() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-unexpected-shape.json");
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(1, TENANT_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
        assertThat(run.path("sourceUsed").asString()).isEqualTo("generic-backup-provider");
    }

    @Test
    @DisplayName("AC9 — HTML error page with status 200 (A10): not retried, run FAILED, stored network unchanged")
    void ac9_htmlBodyWithStatus200() throws Exception {
        stubOk(BACKUP_B_PATH, "backup-b.json");
        sync("tenant-b");
        String before = compact(routes("tenant-b"));
        WM.resetAll();
        stub(BACKUP_B_PATH, aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                .withBody(fixture("error-page.html")));

        JsonNode run = sync("tenant-b");

        assertRequested(1, BACKUP_B_PATH);
        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(compact(routes("tenant-b"))).isEqualTo(before);
    }

    // --- AC10 all sources fail ---

    @Test
    @DisplayName("AC10 — all sources fail: run FAILED, stored network and change log unchanged")
    void ac10_allSourcesFailKeepsStoredData() throws Exception {
        seedTenantA();
        stubStatus(TENANT_A_PATH, 500);
        stubStatus(BACKUP_A_PATH, 500);

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(run.path("error").isNull()).as("error message recorded").isFalse();
        assertThat(compact(routes("tenant-a"))).isEqualTo(TENANT_A_V1_ROUTES);
        assertThat(changes("tenant-a").size()).isEqualTo(6);
    }

    @Test
    @DisplayName("AC10 — all sources fail: tenant error count increases by one")
    void ac10_allSourcesFailIncrementsErrorCount() throws Exception {
        seedTenantA();
        int before = health("tenant-a").path("errorCount").asInt();
        stubStatus(TENANT_A_PATH, 500);
        stubStatus(BACKUP_A_PATH, 500);

        sync("tenant-a");

        assertThat(health("tenant-a").path("errorCount").asInt()).isEqualTo(before + 1);
    }

    // --- AC11 guard ---

    @Test
    @DisplayName("AC11 — empty snapshot over a stored network: REJECTED, nothing persisted, no fallback")
    void ac11_emptySnapshotRejected() throws Exception {
        seedTenantA();
        stubOk(TENANT_A_PATH, "empty-array.json");
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("REJECTED");
        assertRequested(0, BACKUP_A_PATH);
        assertThat(compact(routes("tenant-a"))).isEqualTo(TENANT_A_V1_ROUTES);
        assertThat(count("route_change", "tenant-a")).isEqualTo(6);
    }

    @Test
    @DisplayName("AC11 — empty snapshot on first sync: REJECTED, nothing stored (§6.1)")
    void ac11_emptyFirstSyncRejected() throws Exception {
        stubOk(TENANT_A_PATH, "empty-array.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("REJECTED");
        assertThat(count("route", "tenant-a")).isZero();
        assertThat(count("route_change", "tenant-a")).isZero();
    }

    @Test
    @DisplayName("AC11 — snapshot below 50 % of stored routes: REJECTED, nothing persisted, error count +1")
    void ac11_truncatedSnapshotRejected() throws Exception {
        seedTenantA();
        stubOk(TENANT_A_PATH, "tenant-a-truncated.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("REJECTED");
        assertThat(compact(routes("tenant-a"))).isEqualTo(TENANT_A_V1_ROUTES);
        assertThat(count("route_change", "tenant-a")).isEqualTo(6);
        assertThat(health("tenant-a").path("errorCount").asInt()).isEqualTo(1);
    }

    // --- AC12 fallback covers fewer types ---

    @Test
    @DisplayName("AC12 — Tenant A falls back to Source B: no CONNECTING route removed")
    void ac12_fallbackKeepsConnectingRoutes() throws Exception {
        seedTenantA();
        stubStatus(TENANT_A_PATH, 500);
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
        assertThat(run.path("removed").asInt()).isZero();
        assertThat(compact(getJson("/api/tenants/tenant-a/routes?type=CONNECTING")))
                .isEqualTo("{\"ACA\":[\"BOG\",\"CUN\"]}");
        JsonNode changes = changes("tenant-a");
        for (JsonNode c : changes) {
            if ("REMOVED".equals(c.path("changeType").asString())) {
                throw new AssertionError("unexpected REMOVED change after fallback: " + c);
            }
        }
        // the direct route only Source B sees is still added
        assertThat(findChange(changes, "GDL", "TIJ").path("changeType").asString()).isEqualTo("ADDED");
    }

    // --- §4.3 guard counts only the types the source covers ---

    @Test
    @DisplayName("§4.3 — fallback to direct-only source compared with stored DIRECT only: 2 of 2 DIRECT (of 8 stored) is DEGRADED, not REJECTED")
    void guardCountsCoveredTypesOnlyAccepts() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-few-direct.json");
        assertThat(sync("tenant-a").path("status").asString()).isEqualTo("SUCCESS");
        WM.resetAll();
        stubStatus(TENANT_A_PATH, 500);
        stubOk(BACKUP_A_PATH, "backup-y4-aca-gdl-mex.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
        assertThat(run.path("removed").asInt()).isZero();
        assertThat(compact(routes("tenant-a")))
                .isEqualTo("{\"ACA\":[\"AGU\",\"BJX\",\"BOG\",\"CEN\",\"CUN\",\"GDL\",\"MEX\",\"TIJ\"]}");
        assertThat(health("tenant-a").path("errorCount").asInt()).isZero();
    }

    @Test
    @DisplayName("§4.3 — fallback to direct-only source returning < 50 % of stored DIRECT (1 of 4): REJECTED, nothing persisted")
    void guardCountsCoveredTypesOnlyRejects() throws Exception {
        seedTenantA();
        stubStatus(TENANT_A_PATH, 500);
        stubOk(BACKUP_A_PATH, "backup-y4-one-direct.json");

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("REJECTED");
        assertThat(compact(routes("tenant-a"))).isEqualTo(TENANT_A_V1_ROUTES);
        assertThat(count("route_change", "tenant-a")).isEqualTo(6);
    }

    // --- §4.1 retry policy ---

    @Test
    @DisplayName("§4.1 — 429 with Retry-After then 200: retried, primary used, run SUCCESS")
    void rateLimitedThenOk() throws Exception {
        WM.stubFor(get(urlPathEqualTo(TENANT_A_PATH)).inScenario("rate-limit").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("ok"));
        WM.stubFor(get(urlPathEqualTo(TENANT_A_PATH)).inScenario("rate-limit").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(fixture("tenant-a-v1.json"))));
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(2, TENANT_A_PATH);
        assertRequested(0, BACKUP_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("SUCCESS");
        List<Long> times = WM.getAllServeEvents().stream()
                .map(e -> e.getRequest())
                .filter(r -> r.getUrl().startsWith(TENANT_A_PATH))
                .map(r -> r.getLoggedDate().getTime())
                .sorted()
                .toList();
        assertThat(times.get(1) - times.get(0)).as("gap honours Retry-After: 1 (not the 10 ms backoff)")
                .isGreaterThanOrEqualTo(950);
    }

    @Test
    @DisplayName("§4.1 — primary 503: retried 3 times, then fallback, run DEGRADED")
    void serviceUnavailableRetriedThenFallback() throws Exception {
        stubStatus(TENANT_A_PATH, 503);
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(3, TENANT_A_PATH);
        assertRequested(1, BACKUP_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("§4.1 — connection reset: retried 3 times, then fallback, run DEGRADED")
    void connectionResetRetriedThenFallback() throws Exception {
        stub(TENANT_A_PATH, aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(3, TENANT_A_PATH);
        assertRequested(1, BACKUP_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("§4.1 — primary 404: not retried, fallback used")
    void notFoundNotRetried() throws Exception {
        stubStatus(TENANT_A_PATH, 404);
        stubOk(BACKUP_A_PATH, "backup-y4.json");

        JsonNode run = sync("tenant-a");

        assertRequested(1, TENANT_A_PATH);
        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
    }

    // --- §4.3 one sync per tenant ---

    @Test
    @DisplayName("§4.3 — a second sync of a tenant while one is running returns 409")
    void concurrentSyncRefused() throws Exception {
        stub(BACKUP_B_PATH, aResponse().withStatus(200).withFixedDelay(500)
                .withHeader("Content-Type", "application/json").withBody(fixture("backup-b.json")));

        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> {
            try {
                return sync("tenant-b");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (WM.findAll(getRequestedFor(urlPathEqualTo(BACKUP_B_PATH)).withQueryParam("api_key", equalTo(API_KEY))).isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("first sync never reached the source");
            }
            Thread.sleep(5);
        }

        mvc.perform(post("/api/tenants/tenant-b/sync"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(first.get(10, TimeUnit.SECONDS).path("status").asString()).isEqualTo("SUCCESS");
    }
}
