package com.caravelo.routesync.core;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Global sync settings ({@code caravelo.sync.*}); defaults are the initial assumptions of SPEC §2. */
@ConfigurationProperties("caravelo.sync")
public record SyncProperties(
        @DefaultValue("true") boolean onStartup,
        @DefaultValue("true") boolean schedulerEnabled,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue Retry retry) {

    public record Retry(@DefaultValue("3") int maxAttempts, @DefaultValue("1s") Duration initialBackoff) {}
}
