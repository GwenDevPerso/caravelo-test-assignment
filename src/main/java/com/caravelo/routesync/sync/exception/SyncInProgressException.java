package com.caravelo.routesync.sync.exception;

public class SyncInProgressException extends RuntimeException {

    public SyncInProgressException(String tenantId) {
        super("A sync of tenant " + tenantId + " is already running");
    }
}
