package com.caravelo.routesync.sync;

public class TenantDisabledException extends RuntimeException {

    public TenantDisabledException(String tenantId, String reason) {
        super("Tenant " + tenantId + " is disabled: " + reason);
    }
}
