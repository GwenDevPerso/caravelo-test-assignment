package com.caravelo.routesync.sync.exception;

public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String tenantId) {
        super("Unknown tenant: " + tenantId);
    }
}
