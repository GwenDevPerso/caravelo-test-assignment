package com.caravelo.routesync.sync;

import java.time.Instant;

public record TenantHealth(String tenantId, boolean enabled, Instant lastSuccessAt, SyncStatus lastStatus,
                           String lastSourceUsed, String lastError, int errorCount, boolean stale, int routeCount) {}
