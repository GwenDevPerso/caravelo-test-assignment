package com.caravelo.routesync.core.source;

public class SourceFetchException extends RuntimeException {

    private final boolean retryable;

    public SourceFetchException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
