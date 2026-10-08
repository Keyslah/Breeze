package com.aaron.breeze;

import java.util.function.LongSupplier;

/** Main-thread Bluetooth policy with a bounded grace period after leaving the Activity. */
final class UiConnectionState {
    static final long BACKGROUND_GRACE_MS = 120000;
    private final LongSupplier clock;
    private boolean visible, disconnected;
    private long generation;
    private long backgroundDeadlineMs = -1;

    UiConnectionState() { this(() -> System.nanoTime() / 1000000L); }
    UiConnectionState(LongSupplier clock) { this.clock = clock; }

    void setVisible(boolean value) {
        if (visible == value) return;
        if (value) {
            // A brief Camera/app switch keeps the same connection and pending commands.
            if (disconnected || backgroundRemainingMs() == 0) generation++;
            visible = true;
            disconnected = false;
            backgroundDeadlineMs = -1;
        } else {
            visible = false;
            backgroundDeadlineMs = disconnected ? -1 : clock.getAsLong() + BACKGROUND_GRACE_MS;
        }
    }

    void disconnect() { disconnected = true; generation++; backgroundDeadlineMs = -1; }

    boolean requestStart(boolean userRequested) {
        if (!visible) return false;
        if (userRequested) disconnected = false;
        return !disconnected;
    }

    boolean allows(long session) { return !disconnected && session == generation && (visible || backgroundRemainingMs() > 0); }
    long backgroundRemainingMs() {
        return visible || disconnected || backgroundDeadlineMs < 0 ? 0 : Math.max(0, backgroundDeadlineMs - clock.getAsLong());
    }
    long generation() { return generation; }
}
