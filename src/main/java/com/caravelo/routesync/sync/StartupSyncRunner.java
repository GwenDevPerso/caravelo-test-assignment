package com.caravelo.routesync.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

import com.caravelo.routesync.core.SyncProperties;
import com.caravelo.routesync.persistence.SyncRunRepository;
import com.caravelo.routesync.tenant.TenantDefinition;
import com.caravelo.routesync.tenant.TenantRegistry;

/** First sync of every enabled tenant at startup, in the background, one tenant after the other (SPEC §6.4). */
@Component
public class StartupSyncRunner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StartupSyncRunner.class);

    private final TenantRegistry tenants;
    private final SyncService sync;
    private final SyncProperties properties;
    private final SyncRunRepository runs;
    private final Clock clock;

    public StartupSyncRunner(TenantRegistry tenants, SyncService sync, SyncProperties properties, SyncRunRepository runs,
                       Clock clock) {
        this.tenants = tenants;
        this.sync = sync;
        this.properties = properties;
        this.runs = runs;
        this.clock = clock;
    }

    /** Before the web server accepts requests, so no live run can be caught (single instance, A7). */
    @Override
    public void afterSingletonsInstantiated() {
        int interrupted = runs.failInterrupted(clock.instant());
        if (interrupted > 0) {
            log.warn("{} run(s) left RUNNING by a previous stop marked FAILED", interrupted);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncAll() {
        if (!properties.onStartup()) {
            return;
        }
        Thread.ofVirtual().name("startup-sync").start(() -> tenants.all().stream()
                .filter(TenantDefinition::enabled)
                .forEach(tenant -> {
                    try {
                        sync.sync(tenant.tenantId());
                    } catch (RuntimeException e) { // one tenant never blocks the others (AC18)
                        log.error("Startup sync of tenant {} failed: {}", tenant.tenantId(), e.getMessage());
                    }
                }));
    }
}
