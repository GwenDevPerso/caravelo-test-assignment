package com.caravelo.routesync.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.caravelo.routesync.sync.model.SyncRunView;
import com.caravelo.routesync.sync.type.SyncStatus;

/** Every sync attempt, used for health and as the change log's correlation id. */
@Repository
public class SyncRunRepository {

    private final JdbcClient jdbc;

    public SyncRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void start(UUID runId, String tenantId, Instant startedAt) {
        jdbc.sql("INSERT INTO sync_run (id, tenant_id, status, started_at) VALUES (?, ?, 'RUNNING', ?)")
                .params(runId, tenantId, Timestamp.from(startedAt))
                .update();
    }

    public void finish(SyncRunView run) {
        jdbc.sql("""
                        UPDATE sync_run SET status = ?, source_used = ?, finished_at = ?, route_count = ?, added = ?,
                            removed = ?, modified = ?, issue_count = ?, error = ?
                        WHERE id = ? AND tenant_id = ?""")
                .params(run.status().name(), run.sourceUsed(), Timestamp.from(run.finishedAt()), run.routeCount(),
                        run.added(), run.removed(), run.modified(), run.issueCount(), run.error(), run.runId(),
                        run.tenantId())
                .update();
    }

    /** Runs left RUNNING by a crash or a lost database become FAILED, so they count as errors (SPEC §6.1). */
    public int failInterrupted(Instant now) {
        return jdbc.sql("""
                        UPDATE sync_run SET status = 'FAILED', finished_at = ?,
                            error = 'interrupted: the service stopped or lost the database during the run'
                        WHERE status = 'RUNNING'""")
                .param(Timestamp.from(now))
                .update();
    }

    public Optional<SyncRunView> latest(String tenantId) {
        return jdbc.sql("SELECT * FROM sync_run WHERE tenant_id = ? ORDER BY started_at DESC LIMIT 1")
                .param(tenantId).query((rs, row) -> toView(rs)).optional();
    }

    public Optional<SyncRunView> latestSuccessful(String tenantId) {
        return jdbc.sql("""
                        SELECT * FROM sync_run WHERE tenant_id = ? AND status IN ('SUCCESS', 'DEGRADED')
                        ORDER BY started_at DESC LIMIT 1""")
                .param(tenantId).query((rs, row) -> toView(rs)).optional();
    }

    public int errorCount(String tenantId) {
        return jdbc.sql("SELECT count(*) FROM sync_run WHERE tenant_id = ? AND status IN ('FAILED', 'REJECTED')")
                .param(tenantId).query(Integer.class).single();
    }

    private static SyncRunView toView(ResultSet rs) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new SyncRunView(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                SyncStatus.valueOf(rs.getString("status")), rs.getString("source_used"),
                rs.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant(),
                rs.getInt("route_count"), rs.getInt("added"), rs.getInt("removed"), rs.getInt("modified"),
                rs.getInt("issue_count"), rs.getString("error"));
    }
}
