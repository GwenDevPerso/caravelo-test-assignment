package com.caravelo.routesync.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caravelo.routesync.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC19 / §6.4: with {@code caravelo.sync.on-startup=true}, every enabled tenant is synced once at startup, in the
 * background. Own Spring context (and database): stubs are registered before the context starts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StartupSyncIntegrationTest {

    private static final Set<String> FINISHED = Set.of("SUCCESS", "DEGRADED", "REJECTED", "FAILED");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    static {
        WM.start();
        // registered first so it is (likely) synced first: its failure must not stop the others (AC18)
        WM.stubFor(get(urlPathEqualTo("/down/routes")).willReturn(aResponse().withStatus(500)));
        WM.stubFor(get(urlPathEqualTo("/tenant-a/stations")).willReturn(json("tenant-a-v1.json")));
        WM.stubFor(get(urlPathEqualTo("/backup-b/routes")).willReturn(json("backup-b.json")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String fixture) {
        try {
            return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(Files.readString(Path.of("src/test/resources/fixtures/it", fixture), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("caravelo.sync.on-startup", () -> "true");
        r.add("caravelo.sync.scheduler-enabled", () -> "false");
        r.add("caravelo.sync.connect-timeout", () -> "1s");
        r.add("caravelo.sync.read-timeout", () -> "1s");
        r.add("caravelo.sync.retry.max-attempts", () -> "3");
        r.add("caravelo.sync.retry.initial-backoff", () -> "10ms");

        r.add("caravelo.tenants.tenant-down.airline-iata", () -> "Y4");
        r.add("caravelo.tenants.tenant-down.sources[0].adapter", () -> "generic-backup-provider");
        r.add("caravelo.tenants.tenant-down.sources[0].url", () -> WM.baseUrl() + "/down/routes");
        r.add("caravelo.tenants.tenant-down.sources[0].api-key", () -> "k");

        r.add("caravelo.tenants.tenant-a.airline-iata", () -> "Y4");
        r.add("caravelo.tenants.tenant-a.sources[0].adapter", () -> "tenant-a-stations");
        r.add("caravelo.tenants.tenant-a.sources[0].url", () -> WM.baseUrl() + "/tenant-a/stations");

        r.add("caravelo.tenants.tenant-b.airline-iata", () -> "Y4");
        r.add("caravelo.tenants.tenant-b.sources[0].adapter", () -> "generic-backup-provider");
        r.add("caravelo.tenants.tenant-b.sources[0].url", () -> WM.baseUrl() + "/backup-b/routes");
        r.add("caravelo.tenants.tenant-b.sources[0].api-key", () -> "k");
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @Autowired
    MockMvc mvc;

    private JsonNode health(String tenantId) throws Exception {
        return JSON.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/tenants/{id}/health", tenantId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Polls the health of each tenant until its last run is finished, or fails after 30 s. */
    private Map<String, JsonNode> awaitStartupRuns(List<String> tenantIds) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        Map<String, JsonNode> result = new HashMap<>();
        while (result.size() < tenantIds.size()) {
            for (String id : tenantIds) {
                JsonNode h = health(id);
                if (FINISHED.contains(h.path("lastStatus").asString(""))) {
                    result.put(id, h);
                }
            }
            if (result.size() < tenantIds.size()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("startup sync not finished for all of " + tenantIds + ", got " + result);
                }
                Thread.sleep(100);
            }
        }
        return result;
    }

    @Test
    @DisplayName("AC19 — at startup every enabled tenant is synced once; a failing tenant does not prevent the others (AC18)")
    void ac19_everyEnabledTenantSyncedAtStartup() throws Exception {
        Map<String, JsonNode> health = awaitStartupRuns(List.of("tenant-a", "tenant-b", "tenant-down"));

        for (String id : List.of("tenant-a", "tenant-b")) {
            JsonNode h = health.get(id);
            assertThat(h.path("lastStatus").asString()).as("%s last status", id).isEqualTo("SUCCESS");
            assertThat(h.path("lastSuccessAt").isNull() || h.path("lastSuccessAt").isMissingNode())
                    .as("%s lastSuccessAt set", id).isFalse();
        }
        assertThat(health.get("tenant-a").path("routeCount").asInt()).isEqualTo(6);
        assertThat(health.get("tenant-b").path("routeCount").asInt()).isEqualTo(2);
        assertThat(health.get("tenant-down").path("lastStatus").asString()).isEqualTo("FAILED");
    }
}
