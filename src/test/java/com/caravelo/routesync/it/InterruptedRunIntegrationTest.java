package com.caravelo.routesync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * §6.1: a run left {@code RUNNING} (crash, lost database) is marked {@code FAILED} ("interrupted") at the next startup
 * and counts in the error count. Own database and context: the schema is migrated with the project's Flyway scripts
 * and the RUNNING row inserted <em>before</em> the Spring context starts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InterruptedRunIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final PostgreSQLContainer DB = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        DB.start();
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
                PreparedStatement ps = c.prepareStatement("INSERT INTO sync_run (id, tenant_id, status, started_at, "
                        + "route_count, added, removed, modified, issue_count) VALUES (?, 'tenant-a', 'RUNNING', ?, 0, 0, 0, 0, 0)")) {
            ps.setObject(1, RUN_ID);
            ps.setTimestamp(2, Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("seeding the RUNNING run failed", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("caravelo.sync.on-startup", () -> "false"); // no new run may hide the interrupted one
        r.add("caravelo.sync.scheduler-enabled", () -> "false");
        r.add("caravelo.tenants.tenant-a.airline-iata", () -> "Y4");
        r.add("caravelo.tenants.tenant-a.sources[0].adapter", () -> "tenant-a-stations");
        r.add("caravelo.tenants.tenant-a.sources[0].url", () -> "http://localhost:1/never-called");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private JsonNode health() throws Exception {
        return JSON.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/tenants/{id}/health", "tenant-a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** The interrupted run may be closed by a startup listener; give it up to 10 s, then read the health. */
    private JsonNode awaitNotRunning() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode h = health();
        while ("RUNNING".equals(h.path("lastStatus").asString("")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            h = health();
        }
        return h;
    }

    @Test
    @DisplayName("§6.1 — a run left RUNNING is FAILED (\"interrupted\") after startup, with a finish time")
    void s61_runningRunMarkedFailedAtStartup() throws Exception {
        awaitNotRunning();

        var row = jdbc.sql("SELECT status, error, finished_at FROM sync_run WHERE id = ?").param(RUN_ID)
                .query((rs, n) -> new Object[] {rs.getString("status"), rs.getString("error"), rs.getTimestamp("finished_at")})
                .single();
        assertThat(row[0]).isEqualTo("FAILED");
        assertThat((String) row[1]).containsIgnoringCase("interrupted");
        assertThat(row[2]).as("finished_at set").isNotNull();
    }

    @Test
    @DisplayName("§6.1 — the interrupted run counts in the tenant health: lastStatus FAILED, errorCount 1, lastError interrupted")
    void s61_interruptedRunCountedInHealth() throws Exception {
        JsonNode h = awaitNotRunning();

        assertThat(h.path("lastStatus").asString()).isEqualTo("FAILED");
        assertThat(h.path("errorCount").asInt()).isEqualTo(1);
        assertThat(h.path("lastError").asString("")).containsIgnoringCase("interrupted");
    }
}
