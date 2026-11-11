package com.caravelo.routesync.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.caravelo.routesync.core.route.CanonicalRoute;
import com.caravelo.routesync.core.route.RouteType;
import com.caravelo.routesync.sync.RouteChange;
import com.caravelo.routesync.sync.ChangeType;

/** The stored network, always scoped by tenant. */
@Repository
public class RouteRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public RouteRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public List<CanonicalRoute> findAll(String tenantId) {
        return jdbc.sql("SELECT origin, destination, type, via FROM route WHERE tenant_id = ?")
                .param(tenantId)
                .query((rs, row) -> toRoute(rs))
                .list();
    }

    /** Routes matching the optional filters, ordered by origin then destination. */
    public List<CanonicalRoute> find(String tenantId, String origin, String destination, RouteType type) {
        return jdbc.sql("""
                        SELECT origin, destination, type, via FROM route
                        WHERE tenant_id = :tenant
                          AND (CAST(:origin AS TEXT) IS NULL OR origin = :origin)
                          AND (CAST(:destination AS TEXT) IS NULL OR destination = :destination)
                          AND (CAST(:type AS TEXT) IS NULL OR type = :type)
                        ORDER BY origin, destination""")
                .param("tenant", tenantId)
                .param("origin", origin)
                .param("destination", destination)
                .param("type", type == null ? null : type.name())
                .query((rs, row) -> toRoute(rs))
                .list();
    }

    public int count(String tenantId) {
        return jdbc.sql("SELECT count(*) FROM route WHERE tenant_id = ?").param(tenantId).query(Integer.class).single();
    }

    /** Applies the diff; must run inside the sync transaction. */
    void apply(String tenantId, List<RouteChange> changes, Instant now) {
        Timestamp at = Timestamp.from(now);
        List<Object[]> upserts = changes.stream()
                .filter(change -> change.changeType() != ChangeType.REMOVED)
                .map(change -> new Object[] {tenantId, change.origin(), change.destination(), change.newType().name(),
                        toArray(change.newVia()), at})
                .toList();
        template.batchUpdate("""
                INSERT INTO route (tenant_id, origin, destination, type, via, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, origin, destination)
                DO UPDATE SET type = EXCLUDED.type, via = EXCLUDED.via, updated_at = EXCLUDED.updated_at""", upserts);
        List<Object[]> deletes = changes.stream()
                .filter(change -> change.changeType() == ChangeType.REMOVED)
                .map(change -> new Object[] {tenantId, change.origin(), change.destination()})
                .toList();
        template.batchUpdate("DELETE FROM route WHERE tenant_id = ? AND origin = ? AND destination = ?", deletes);
    }

    static String[] toArray(Set<String> values) {
        return values == null ? null : values.stream().sorted().toArray(String[]::new);
    }

    static Set<String> toSet(Array array) throws SQLException {
        return array == null ? null : new LinkedHashSet<>(Arrays.asList((String[]) array.getArray()));
    }

    private static CanonicalRoute toRoute(ResultSet rs) throws SQLException {
        return new CanonicalRoute(rs.getString("origin"), rs.getString("destination"),
                RouteType.valueOf(rs.getString("type")), toSet(rs.getArray("via")));
    }
}
