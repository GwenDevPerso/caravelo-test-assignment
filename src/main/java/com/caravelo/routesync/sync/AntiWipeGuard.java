package com.caravelo.routesync.sync;

/** Refuses snapshots that would wipe most of a network: far more likely a broken response (SPEC §4.3). */
final class AntiWipeGuard {

    private AntiWipeGuard() {
    }

    /** @param storedCoveredCount stored routes of the types the source covers (R12) */
    public static boolean accepts(int storedCoveredCount, int snapshotSize, double threshold) {
        if (snapshotSize == 0) {
            return false;
        }
        return storedCoveredCount == 0 || snapshotSize >= threshold * storedCoveredCount;
    }
}
