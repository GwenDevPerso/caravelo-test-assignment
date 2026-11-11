package com.caravelo.routesync.sync.model;

import java.time.Instant;

import com.caravelo.routesync.sync.type.SyncStatus;

public record TenantHealth(String tenantId, boolean enabled, Instant lastSuccessAt, SyncStatus lastStatus,
                           String lastSourceUsed, String lastError, int errorCount, boolean stale, int routeCount) {}
