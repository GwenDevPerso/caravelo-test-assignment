package com.caravelo.routesync.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caravelo.routesync.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared Spring context for all integration tests: real PostgreSQL (Testcontainers), sources simulated by one
 * WireMock server. Tenants are configured here, from SPEC §2 / docs/implementation-contract.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class IntegrationTestBase {

    static final String API_KEY = "it-backup-key";
    static final String TENANT_A_PATH = "/tenant-a/stations";
    static final String BACKUP_A_PATH = "/backup/routes";
    static final String BACKUP_B_PATH = "/backup-b/routes";
    static final String BACKUP_D_PATH = "/backup-d/routes";
    static final String TENANT_C_PATH = "/tenant-c/routes";

    static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    static {
        WM.start();
    }

    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("caravelo.sync.on-startup", () -> "false");
        r.add("caravelo.sync.scheduler-enabled", () -> "false");
        r.add("caravelo.sync.connect-timeout", () -> "1s");
        r.add("caravelo.sync.read-timeout", () -> "1s");
        r.add("caravelo.sync.retry.max-attempts", () -> "3");
        r.add("caravelo.sync.retry.initial-backoff", () -> "10ms");

        r.add("caravelo.tenants.tenant-a.airline-iata", () -> "Y4");
        r.add("caravelo.tenants.tenant-a.sources[0].adapter", () -> "tenant-a-stations");
        r.add("caravelo.tenants.tenant-a.sources[0].url", () -> WM.baseUrl() + TENANT_A_PATH);
        r.add("caravelo.tenants.tenant-a.sources[1].adapter", () -> "generic-backup-provider");
        r.add("caravelo.tenants.tenant-a.sources[1].url", () -> WM.baseUrl() + BACKUP_A_PATH);
        r.add("caravelo.tenants.tenant-a.sources[1].api-key", () -> API_KEY);

        r.add("caravelo.tenants.tenant-b.airline-iata", () -> "Y4");
        r.add("caravelo.tenants.tenant-b.sources[0].adapter", () -> "generic-backup-provider");
        r.add("caravelo.tenants.tenant-b.sources[0].url", () -> WM.baseUrl() + BACKUP_B_PATH);
        r.add("caravelo.tenants.tenant-b.sources[0].api-key", () -> API_KEY);

        // AC15: a third tenant, configuration only, reusing the backup adapter for another airline
        r.add("caravelo.tenants.tenant-d.airline-iata", () -> "XY");
        r.add("caravelo.tenants.tenant-d.sources[0].adapter", () -> "generic-backup-provider");
        r.add("caravelo.tenants.tenant-d.sources[0].url", () -> WM.baseUrl() + BACKUP_D_PATH);
        r.add("caravelo.tenants.tenant-d.sources[0].api-key", () -> API_KEY);

        // AC16 / §6.5: Tenant C, invented JSON format, configuration only through json-mapping
        r.add("caravelo.tenants.tenant-c.airline-iata", () -> "XX");
        r.add("caravelo.tenants.tenant-c.sources[0].adapter", () -> "json-mapping");
        r.add("caravelo.tenants.tenant-c.sources[0].url", () -> WM.baseUrl() + TENANT_C_PATH);
        r.add("caravelo.tenants.tenant-c.sources[0].mapping.items", () -> "$.data.routes[*]");
        r.add("caravelo.tenants.tenant-c.sources[0].mapping.origin", () -> "$.from");
        r.add("caravelo.tenants.tenant-c.sources[0].mapping.destination", () -> "$.to");

        // §6.5: invalid json-mapping (no destination path) -> this tenant disabled at startup
        r.add("caravelo.tenants.tenant-c-bad.airline-iata", () -> "XX");
        r.add("caravelo.tenants.tenant-c-bad.sources[0].adapter", () -> "json-mapping");
        r.add("caravelo.tenants.tenant-c-bad.sources[0].url", () -> WM.baseUrl() + TENANT_C_PATH);
        r.add("caravelo.tenants.tenant-c-bad.sources[0].mapping.items", () -> "$.data.routes[*]");
        r.add("caravelo.tenants.tenant-c-bad.sources[0].mapping.origin", () -> "$.from");

        // SPEC §4.4: unknown adapter type -> this tenant disabled, others run
        r.add("caravelo.tenants.tenant-broken.airline-iata", () -> "Y4");
        r.add("caravelo.tenants.tenant-broken.sources[0].adapter", () -> "no-such-adapter");
        r.add("caravelo.tenants.tenant-broken.sources[0].url", () -> WM.baseUrl() + "/broken");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void resetState() {
        WM.resetAll();
        jdbc.sql("TRUNCATE route, route_change, sync_run").update();
    }

    static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/it", name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isBackupPath(String path) {
        return path.startsWith("/backup");
    }

    /** Stubs a GET on the path; backup paths only match when the key is sent as {@code api_key} (SPEC §6.4). */
    static void stub(String path, ResponseDefinitionBuilder response) {
        MappingBuilder mapping = get(urlPathEqualTo(path));
        if (isBackupPath(path)) {
            mapping = mapping.withQueryParam("api_key", equalTo(API_KEY));
        }
        WM.stubFor(mapping.willReturn(response));
    }

    static void stubOk(String path, String fixtureName) {
        stub(path, aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(fixture(fixtureName)));
    }

    static void stubStatus(String path, int status) {
        stub(path, aResponse().withStatus(status));
    }

    static void assertRequested(int times, String path) {
        WM.verify(exactly(times), getRequestedFor(urlPathEqualTo(path)));
    }

    /** POST /api/tenants/{id}/sync, expecting 200 (the finished run, whatever its status). */
    JsonNode sync(String tenantId) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/tenants/{id}/sync", tenantId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    JsonNode getJson(String uri, Object... vars) throws Exception {
        return JSON.readTree(mvc.perform(MockMvcRequestBuilders.get(uri, vars))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    JsonNode routes(String tenantId) throws Exception {
        return getJson("/api/tenants/{id}/routes", tenantId);
    }

    JsonNode changes(String tenantId) throws Exception {
        return getJson("/api/tenants/{id}/changes", tenantId);
    }

    JsonNode health(String tenantId) throws Exception {
        return getJson("/api/tenants/{id}/health", tenantId);
    }

    /** Compact JSON, keeping key order: lets tests assert the sorted grouping of §6.3 literally. */
    static String compact(JsonNode node) {
        return JSON.writeValueAsString(node);
    }

    long count(String table, String tenantId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?").param(tenantId).query(Long.class).single();
    }

    /** Stores tenant-a-v1 (6 routes: 4 DIRECT, 2 CONNECTING) through a real sync, then clears WireMock. */
    void seedTenantA() throws Exception {
        stubOk(TENANT_A_PATH, "tenant-a-v1.json");
        JsonNode run = sync("tenant-a");
        if (!"SUCCESS".equals(run.path("status").asString())) {
            throw new IllegalStateException("seed sync of tenant-a did not succeed: " + run);
        }
        WM.resetAll();
    }

    static final String TENANT_A_V1_ROUTES = "{\"ACA\":[\"BOG\",\"CUN\",\"GDL\",\"MEX\"],\"MEX\":[\"ACA\",\"BOG\"]}";

    static JsonNode findChange(JsonNode changes, String origin, String destination) {
        for (JsonNode c : changes) {
            if (origin.equals(c.path("origin").asString()) && destination.equals(c.path("destination").asString())) {
                return c;
            }
        }
        throw new AssertionError("no change for " + origin + "->" + destination + " in " + changes);
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
