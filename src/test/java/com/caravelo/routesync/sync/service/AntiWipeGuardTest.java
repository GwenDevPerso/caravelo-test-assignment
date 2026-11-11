package com.caravelo.routesync.sync.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AntiWipeGuardTest {

    @Test
    @DisplayName("AC11 — empty snapshot with routes stored is rejected")
    void emptySnapshotRejected() {
        assertThat(AntiWipeGuard.accepts(10, 0, 0.5)).isFalse();
    }

    @Test
    @DisplayName("AC11 — empty snapshot with nothing stored is rejected (§6.1 first sync)")
    void emptyFirstSyncRejected() {
        assertThat(AntiWipeGuard.accepts(0, 0, 0.5)).isFalse();
    }

    @Test
    @DisplayName("AC11 — snapshot below 50 % of stored covered routes is rejected")
    void drasticallySmallerRejected() {
        assertThat(AntiWipeGuard.accepts(10, 4, 0.5)).isFalse();
    }

    @Test
    @DisplayName("AC11 — snapshot at exactly 50 % is accepted")
    void exactThresholdAccepted() {
        assertThat(AntiWipeGuard.accepts(10, 5, 0.5)).isTrue();
    }

    @Test
    @DisplayName("AC11 — a larger snapshot is accepted")
    void largerAccepted() {
        assertThat(AntiWipeGuard.accepts(10, 11, 0.5)).isTrue();
    }

    @Test
    @DisplayName("§6.1 — first sync with a non-empty snapshot is accepted")
    void nonEmptyFirstSyncAccepted() {
        assertThat(AntiWipeGuard.accepts(0, 1, 0.5)).isTrue();
    }

    @Test
    @DisplayName("§4.3 — threshold is configurable: 0.8 rejects 7/10 and accepts 8/10")
    void configurableThreshold() {
        assertThat(AntiWipeGuard.accepts(10, 7, 0.8)).isFalse();
        assertThat(AntiWipeGuard.accepts(10, 8, 0.8)).isTrue();
    }
}
