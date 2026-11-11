package com.caravelo.routesync.persistence;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.caravelo.routesync.sync.RouteChange;
import com.caravelo.routesync.sync.SyncRunView;

/** Applies a sync atomically: routes, change log and run result commit together or not at all. */
@Component
public class NetworkPersistenceService {

    private final RouteRepository routes;
    private final ChangeRepository changes;
    private final SyncRunRepository runs;

    public NetworkPersistenceService(RouteRepository routes, ChangeRepository changes, SyncRunRepository runs) {
        this.routes = routes;
        this.changes = changes;
        this.runs = runs;
    }

    @Transactional
    public void apply(SyncRunView run, List<RouteChange> diff) {
        routes.apply(run.tenantId(), diff, run.finishedAt());
        changes.insert(run.tenantId(), run.runId(), diff, run.finishedAt());
        runs.finish(run);
    }
}
