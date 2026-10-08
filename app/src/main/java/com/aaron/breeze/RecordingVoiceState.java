package com.aaron.breeze;

/** Recording facts are separate from BLE connectivity and microphone availability. */
final class RecordingVoiceState {
    private volatile boolean active, pending, suppressed;
    private volatile boolean listeningDisabled = true;

    void beginStart() { pending = true; active = false; suppressed = false; }

    void onTelemetry(boolean recording) {
        active = recording;
        if (recording) pending = false;
        else if (!pending) suppressed = false;
    }

    void onStartFailed() { pending = false; }
    void onStartTimeout() { pending = false; }
    void onDisconnected() { /* The controller continues recording without the phone. */ }
    void suppress() { suppressed = true; }
    void resume() { suppressed = false; }
    void setListeningDisabled(boolean disabled) { listeningDisabled = disabled; }
    boolean isListeningDisabled() { return listeningDisabled; }
    boolean isPending() { return pending; }
    boolean isActive() { return active; }
    boolean isSuppressed() { return suppressed; }
    boolean shouldRunService() { return !listeningDisabled && !suppressed && (pending || active); }
    boolean canRecognize() { return !listeningDisabled && !suppressed && active; }
    boolean shouldStart(boolean visible, boolean serviceRunning) {
        return visible && !serviceRunning && canRecognize();
    }
}
