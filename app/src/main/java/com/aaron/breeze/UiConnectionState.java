package com.aaron.breeze;

import java.util.function.LongSupplier;

/** Main-thread Bluetooth policy with a bounded grace period after leaving the Activity. */
final class UiConnectionState {
    static final long BACKGROUND_GRACE_MS = 120000;
    private final LongSupplier clock;
    private boolean visible, disconnected, synchronizing, listening;
    private long generation;
    private long backgroundDeadlineMs = -1;

    UiConnectionState() { this(() -> System.nanoTime() / 1000000L); }
    UiConnectionState(LongSupplier clock) { this.clock = clock; }

    void setVisible(boolean value) {
        if (visible == value) return;
        if (value) {
            // A brief Camera/app switch keeps the same connection and pending commands.
            if (disconnected || (!synchronizing && !listening && backgroundRemainingMs() == 0)) generation++;
            visible = true;
            disconnected = false;
            backgroundDeadlineMs = -1;
        } else {
            visible = false;
            backgroundDeadlineMs = disconnected ? -1 : clock.getAsLong() + BACKGROUND_GRACE_MS;
        }
    }

    void disconnect() { disconnected = true; synchronizing = false; listening = false; generation++; backgroundDeadlineMs = -1; }

    /** A configured Dreo session may continue after lock, but cannot start a hidden session. */
    void setSynchronizing(boolean value) {
        synchronizing = value && !disconnected && (visible || synchronizing || backgroundRemainingMs() > 0);
    }
    /** An already-started recording listener may retain its connection after lock, never create a hidden session. */
    void setListening(boolean value) {
        listening = value && !disconnected && (visible || listening || backgroundRemainingMs() > 0);
    }

    boolean requestStart(boolean userRequested) {
        if (!visible) return false;
        if (userRequested) disconnected = false;
        return !disconnected;
    }

    boolean allows(long session) { return !disconnected && session == generation && (visible || synchronizing || listening || backgroundRemainingMs() > 0); }
    long backgroundRemainingMs() {
        return visible || disconnected || backgroundDeadlineMs < 0 ? 0 : Math.max(0, backgroundDeadlineMs - clock.getAsLong());
    }
    long generation() { return generation; }
}
