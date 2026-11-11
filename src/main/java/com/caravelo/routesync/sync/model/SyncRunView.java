package com.caravelo.routesync.sync.model;

import java.time.Instant;
import java.util.UUID;

import com.caravelo.routesync.sync.type.SyncStatus;

public record SyncRunView(UUID runId, String tenantId, SyncStatus status, String sourceUsed,
                          Instant startedAt, Instant finishedAt, int routeCount,
                          int added, int removed, int modified, int issueCount, String error) {}
