package com.caravelo.routesync.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.caravelo.routesync.core.route.RouteType;
import com.caravelo.routesync.sync.RouteChange;
import com.caravelo.routesync.sync.ChangeType;

/** Persistent change log (SPEC §1 brief decision 6). */
@Repository
public class ChangeRepository {

    public record StoredChange(UUID runId, RouteChange change, Instant detectedAt) {}

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ChangeRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    void insert(String tenantId, UUID runId, List<RouteChange> changes, Instant now) {
        Timestamp at = Timestamp.from(now);
        template.batchUpdate("""
                INSERT INTO route_change (tenant_id, run_id, change_type, origin, destination,
                                          old_type, new_type, old_via, new_via, detected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", changes.stream().map(change -> new Object[] {
                tenantId, runId, change.changeType().name(), change.origin(), change.destination(),
                name(change.oldType()), name(change.newType()),
                RouteRepository.toArray(change.oldVia()), RouteRepository.toArray(change.newVia()), at}).toList());
    }

    public List<StoredChange> latest(String tenantId, int limit) {
        return jdbc.sql("SELECT * FROM route_change WHERE tenant_id = ? ORDER BY id DESC LIMIT ?")
                .params(tenantId, limit)
                .query((rs, row) -> new StoredChange(rs.getObject("run_id", UUID.class), new RouteChange(
                        ChangeType.valueOf(rs.getString("change_type")), rs.getString("origin"),
                        rs.getString("destination"), type(rs.getString("old_type")), type(rs.getString("new_type")),
                        RouteRepository.toSet(rs.getArray("old_via")), RouteRepository.toSet(rs.getArray("new_via"))),
                        rs.getTimestamp("detected_at").toInstant()))
                .list();
    }

    private static String name(RouteType type) {
        return type == null ? null : type.name();
    }

    private static RouteType type(String value) {
        return value == null ? null : RouteType.valueOf(value);
    }
}
