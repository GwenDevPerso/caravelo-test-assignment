package com.caravelo.routesync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

class ObservabilityIntegrationTest extends IntegrationTestBase {

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("AC13 — every service log line emitted during a sync carries tenantId and correlationId (= run id)")
    void ac13_everySyncLogLineHasTenantAndCorrelationId(CapturedOutput output) throws Exception {
        // a retry-free failure plus a fallback: logs from the HTTP client, the adapters and the sync service
        stubStatus(TENANT_A_PATH, 401);
        stubOk(BACKUP_A_PATH, "backup-y4.json");
        int start = output.getOut().length();

        JsonNode run = sync("tenant-a");

        String runId = run.path("runId").asString();
        List<JsonNode> serviceLines = serviceLines(output.getOut().substring(start));
        assertThat(serviceLines).as("JSON log lines from com.caravelo.routesync during the sync").isNotEmpty();
        for (JsonNode line : serviceLines) {
            assertThat(line.path("tenantId").asString()).as("tenantId in %s", line).isEqualTo("tenant-a");
            assertThat(line.path("correlationId").asString()).as("correlationId in %s", line).isEqualTo(runId);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("AC13 — on the retry path (primary 503 x3 then fallback) every service log line carries tenantId and correlationId, no api_key")
    void ac13_retryPathLogLinesHaveTenantAndCorrelationId(CapturedOutput output) throws Exception {
        stubStatus(TENANT_A_PATH, 503);
        stubOk(BACKUP_A_PATH, "backup-y4.json");
        int start = output.getOut().length();

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("DEGRADED");
        String runId = run.path("runId").asString();
        String captured = output.getOut().substring(start);
        List<JsonNode> serviceLines = serviceLines(captured);
        assertThat(serviceLines).as("log lines from the HTTP client (retries) in core")
                .anySatisfy(line -> assertThat(logger(line)).startsWith("com.caravelo.routesync.core"));
        for (JsonNode line : serviceLines) {
            assertThat(line.path("tenantId").asString()).as("tenantId in %s", line).isEqualTo("tenant-a");
            assertThat(line.path("correlationId").asString()).as("correlationId in %s", line).isEqualTo(runId);
        }
        assertThat(captured).as("backup api_key never logged").doesNotContain(API_KEY);
    }

    /** JSON log lines whose logger is under com.caravelo.routesync. */
    private static List<JsonNode> serviceLines(String captured) {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : captured.split("\\R")) {
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode json = JSON.readTree(line);
            if (logger(json).startsWith("com.caravelo.routesync")) {
                lines.add(json);
            }
        }
        return lines;
    }

    /** ECS logger name, flat ({@code "log.logger"}) or nested ({@code "log": {"logger": ...}}). */
    private static String logger(JsonNode json) {
        String flat = json.path("log.logger").asString("");
        return flat.isEmpty() ? json.path("log").path("logger").asString("") : flat;
    }

    @Test
    @DisplayName("AC14 — health after a successful sync: last success, status, source used, error count")
    void ac14_healthAfterSuccess() throws Exception {
        seedTenantA();

        JsonNode health = health("tenant-a");

        assertThat(health.path("tenantId").asString()).isEqualTo("tenant-a");
        assertThat(health.path("enabled").asBoolean()).isTrue();
        assertThat(health.path("lastSuccessAt").isNull()).isFalse();
        assertThat(health.path("lastSuccessAt").isMissingNode()).isFalse();
        assertThat(health.path("lastStatus").asString()).isEqualTo("SUCCESS");
        assertThat(health.path("lastSourceUsed").asString()).isEqualTo("tenant-a-stations");
        assertThat(health.path("lastError").isNull()).isTrue();
        assertThat(health.path("errorCount").asInt()).isZero();
        assertThat(health.path("stale").asBoolean()).isFalse();
        assertThat(health.path("routeCount").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("AC14 — health after a later FAILED run: last success kept, last status FAILED, error recorded")
    void ac14_healthAfterFailure() throws Exception {
        seedTenantA();
        String lastSuccessAt = health("tenant-a").path("lastSuccessAt").asString();
        stubStatus(TENANT_A_PATH, 500);
        stubStatus(BACKUP_A_PATH, 500);

        sync("tenant-a");
        JsonNode health = health("tenant-a");

        assertThat(health.path("lastSuccessAt").asString()).isEqualTo(lastSuccessAt);
        assertThat(health.path("lastStatus").asString()).isEqualTo("FAILED");
        assertThat(health.path("lastError").isNull()).isFalse();
        assertThat(health.path("lastError").asString()).isNotBlank();
        assertThat(health.path("errorCount").asInt()).isEqualTo(1);
        assertThat(health.path("routeCount").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("AC14 — health before any sync: no last success, stale (§6.1)")
    void ac14_healthBeforeAnySync() throws Exception {
        JsonNode health = health("tenant-a");

        assertThat(health.path("lastSuccessAt").isNull()).isTrue();
        assertThat(health.path("stale").asBoolean()).isTrue();
        assertThat(health.path("errorCount").asInt()).isZero();
    }
}
