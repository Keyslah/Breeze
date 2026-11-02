package com.aaron.breeze;

/** Durable user intent. Controller telemetry is deliberately not an input. */
final class RecordingIntent {
    static final RecordingIntent UNKNOWN = new RecordingIntent(false, false, -1, 0);
    final boolean known, active;
    final long session, revision;
    RecordingIntent(boolean known, boolean active, long session, long revision) {
        this.known = known; this.active = active; this.session = session; this.revision = revision;
    }
}
