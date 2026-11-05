package com.caravelo.routesync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class MultiTenantIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("AC15 — a config-only third tenant on the backup adapter syncs its own airline's routes")
    void ac15_configOnlyTenant() throws Exception {
        stubOk(BACKUP_D_PATH, "backup-d.json");

        JsonNode run = sync("tenant-d");

        assertThat(run.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(run.path("sourceUsed").asString()).isEqualTo("generic-backup-provider");
        assertThat(compact(routes("tenant-d"))).isEqualTo("{\"GDL\":[\"TIJ\"],\"TIJ\":[\"GDL\"]}");
    }

    @Test
    @DisplayName("AC18 — tenant-a failing on every source does not prevent tenant-b from syncing")
    void ac18_failingTenantDoesNotBlockOthers() throws Exception {
        stubStatus(TENANT_A_PATH, 500);
        stubStatus(BACKUP_A_PATH, 500);
        stubOk(BACKUP_B_PATH, "backup-b.json");

        JsonNode a = sync("tenant-a");
        JsonNode b = sync("tenant-b");

        assertThat(a.path("status").asString()).isEqualTo("FAILED");
        assertThat(b.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(compact(routes("tenant-b"))).isEqualTo("{\"BOG\":[\"MEX\"],\"CEN\":[\"GDL\"]}");
    }

    @Test
    @DisplayName("§2 rules — tenant data is isolated: routes, changes and health are scoped by tenant")
    void tenantDataIsolated() throws Exception {
        seedTenantA();
        stubOk(BACKUP_B_PATH, "backup-b.json");
        sync("tenant-b");

        assertThat(compact(routes("tenant-a"))).isEqualTo(TENANT_A_V1_ROUTES);
        assertThat(compact(routes("tenant-b"))).isEqualTo("{\"BOG\":[\"MEX\"],\"CEN\":[\"GDL\"]}");
        assertThat(changes("tenant-a").size()).isEqualTo(6);
        assertThat(changes("tenant-b").size()).isEqualTo(2);
        assertThat(health("tenant-b").path("routeCount").asInt()).isEqualTo(2);
        assertThat(compact(routes("tenant-d"))).isEqualTo("{}");
    }

    @Test
    @DisplayName("§6.2 — GET /api/tenants lists configured tenants with their ordered sources")
    void listTenants() throws Exception {
        JsonNode tenants = getJson("/api/tenants");

        JsonNode a = tenant(tenants, "tenant-a");
        assertThat(a.path("airlineIata").asString()).isEqualTo("Y4");
        assertThat(a.path("enabled").asBoolean()).isTrue();
        assertThat(strings(a.path("sources"))).containsExactly("tenant-a-stations", "generic-backup-provider");
        assertThat(strings(tenant(tenants, "tenant-d").path("sources"))).containsExactly("generic-backup-provider");
    }

    @Test
    @DisplayName("§4.4 — unknown adapter type disables only that tenant")
    void unknownAdapterDisablesTenant() throws Exception {
        assertThat(tenant(getJson("/api/tenants"), "tenant-broken").path("enabled").asBoolean(true)).isFalse();
        assertThat(health("tenant-broken").path("enabled").asBoolean(true)).isFalse();
        assertThat(tenant(getJson("/api/tenants"), "tenant-b").path("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("§6.2 — sync of a disabled tenant returns 409 problem+json, its source is never called")
    void disabledTenantSyncReturns409() throws Exception {
        mvc.perform(post("/api/tenants/tenant-broken/sync"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertRequested(0, "/broken");
        assertThat(count("sync_run", "tenant-broken")).isZero();
    }

    @Test
    @DisplayName("AC16 — Tenant C on json-mapping (config only) syncs its invented format: SUCCESS, routes exposed")
    void ac16_jsonMappingTenantSyncs() throws Exception {
        stubOk(TENANT_C_PATH, "tenant-c.json");

        JsonNode run = sync("tenant-c");

        assertThat(run.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(run.path("sourceUsed").asString()).isEqualTo("json-mapping");
        assertThat(run.path("routeCount").asInt()).isEqualTo(3);
        assertThat(compact(routes("tenant-c"))).isEqualTo("{\"CUN\":[\"MEX\"],\"GDL\":[\"TIJ\"],\"MEX\":[\"CUN\"]}");
        assertThat(compact(getJson("/api/tenants/tenant-c/routes?type=DIRECT")))
                .isEqualTo("{\"CUN\":[\"MEX\"],\"GDL\":[\"TIJ\"],\"MEX\":[\"CUN\"]}");
    }

    @Test
    @DisplayName("AC16 — a json-mapping tenant with an invalid mapping is disabled at startup (§6.5), others enabled")
    void ac16_invalidMappingDisablesTenant() throws Exception {
        JsonNode tenants = getJson("/api/tenants");

        assertThat(tenant(tenants, "tenant-c-bad").path("enabled").asBoolean(true)).isFalse();
        assertThat(tenant(tenants, "tenant-c").path("enabled").asBoolean()).isTrue();
    }

    private static JsonNode tenant(JsonNode tenants, String id) {
        for (JsonNode t : tenants) {
            if (id.equals(t.path("tenantId").asString())) {
                return t;
            }
        }
        throw new AssertionError(id + " not listed in " + tenants);
    }
}
