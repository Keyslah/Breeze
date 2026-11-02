package com.aaron.breeze;

/** Reconcile hardware with user intent, without letting hardware change that intent. */
final class RecordingReconciler {
    static final long RETRY_MS = 10000;
    enum Action { NONE, START, STOP }
    private long attempt, inFlight, retryAt, revision = -1;

    Action next(RecordingIntent intent, boolean available, boolean recording, boolean storageReady, long now) {
        if (!intent.known || !available || inFlight != 0) return Action.NONE;
        if (revision != intent.revision) { revision = intent.revision; retryAt = 0; }
        if (recording == intent.active || now < retryAt) return Action.NONE;
        if (intent.active && !storageReady) return Action.NONE;
        inFlight = ++attempt;
        return intent.active ? Action.START : Action.STOP;
    }
    long attempt() { return inFlight; }
    boolean current(long ticket) { return ticket != 0 && ticket == inFlight; }
    void completed(long ticket, long now) {
        if (!current(ticket)) return;
        inFlight = 0; retryAt = now + RETRY_MS;
    }
    void disconnected() { inFlight = 0; retryAt = 0; }
    static boolean mayStart(RecordingIntent current, long revision) {
        return current.known && current.active && current.revision == revision;
    }
}
