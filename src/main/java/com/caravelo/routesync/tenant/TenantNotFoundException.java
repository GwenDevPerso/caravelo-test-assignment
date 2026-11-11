package com.caravelo.routesync.tenant;

public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String tenantId) {
        super("Unknown tenant: " + tenantId);
    }
}
