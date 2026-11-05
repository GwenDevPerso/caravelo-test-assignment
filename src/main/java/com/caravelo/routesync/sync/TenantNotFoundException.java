package com.caravelo.routesync.sync;

public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String tenantId) {
        super("Unknown tenant: " + tenantId);
    }
}
