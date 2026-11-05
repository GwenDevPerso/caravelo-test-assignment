package com.caravelo.routesync.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * §4.3 one transaction / §4.4 "Database unavailable → rollback, FAILED". A temporary PostgreSQL trigger makes every
 * INSERT into {@code route_change} fail, so a sync that changes the network fails while it is being applied.
 */
class PersistenceFailureIntegrationTest extends IntegrationTestBase {

    void breakChangeLogWrites() {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION it_fail_route_change() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'it: route_change insert refused'; END $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("CREATE TRIGGER it_fail_route_change BEFORE INSERT ON route_change "
                + "FOR EACH ROW EXECUTE FUNCTION it_fail_route_change()").update();
    }

    @AfterEach
    void dropTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS it_fail_route_change ON route_change").update();
        jdbc.sql("DROP FUNCTION IF EXISTS it_fail_route_change()").update();
    }

    @Test
    @DisplayName("§4.3 — a write failing while a sync is applied rolls everything back: run FAILED, routes and changes unchanged")
    void s43_persistenceFailureRollsBackWholeSync() throws Exception {
        seedTenantA();
        long changesBefore = count("route_change", "tenant-a");
        stubOk(TENANT_A_PATH, "tenant-a-v2.json"); // 1 ADDED, 1 REMOVED, 1 MODIFIED vs v1
        breakChangeLogWrites();

        JsonNode run = sync("tenant-a");

        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(run.path("error").isNull() || run.path("error").isMissingNode()).as("error reported").isFalse();
        assertThat(compact(routes("tenant-a"))).isEqualTo(TENANT_A_V1_ROUTES);
        assertThat(count("route_change", "tenant-a")).isEqualTo(changesBefore);
    }

    @Test
    @DisplayName("§4.3 — a sync failing on persistence is counted: errorCount +1, lastStatus FAILED")
    void s43_persistenceFailureCountedInHealth() throws Exception {
        seedTenantA();
        int before = health("tenant-a").path("errorCount").asInt();
        stubOk(TENANT_A_PATH, "tenant-a-v2.json");
        breakChangeLogWrites();

        sync("tenant-a");

        JsonNode h = health("tenant-a");
        assertThat(h.path("errorCount").asInt()).isEqualTo(before + 1);
        assertThat(h.path("lastStatus").asString()).isEqualTo("FAILED");
    }
}
