package com.caravelo.routesync.sync.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import com.caravelo.routesync.core.config.SyncProperties;
import com.caravelo.routesync.sync.exception.SyncInProgressException;
import com.caravelo.routesync.sync.service.SyncService;
import com.caravelo.routesync.tenant.TenantDefinition;
import com.caravelo.routesync.tenant.TenantRegistry;

/** Phase 2: one cron task per enabled tenant on its {@code sync-cron} (SPEC §2, §6.5). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class TenantSyncScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TenantSyncScheduler.class);

    private final TenantRegistry tenants;
    private final SyncService sync;
    private final SyncProperties properties;

    TenantSyncScheduler(TenantRegistry tenants, SyncService sync, SyncProperties properties) {
        this.tenants = tenants;
        this.sync = sync;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.schedulerEnabled()) {
            return;
        }
        tenants.all().stream().filter(TenantDefinition::enabled).forEach(tenant -> {
            registrar.addCronTask(() -> run(tenant.tenantId()), tenant.syncCron());
            log.info("Tenant {} scheduled with cron '{}'", tenant.tenantId(), tenant.syncCron());
        });
    }

    private void run(String tenantId) {
        try {
            sync.sync(tenantId);
        } catch (SyncInProgressException e) {
            log.info("Scheduled sync of tenant {} skipped: {}", tenantId, e.getMessage());
        } catch (RuntimeException e) { // a failing tenant never stops the scheduler
            log.error("Scheduled sync of tenant {} failed: {}", tenantId, e.getMessage(), e);
        }
    }
}
