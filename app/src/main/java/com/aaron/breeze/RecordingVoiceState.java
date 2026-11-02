package com.aaron.breeze;

/** Recording facts are separate from BLE connectivity and microphone availability. */
final class RecordingVoiceState {
    private volatile boolean active, pending, suppressed, pausedPendingStart;
    private volatile boolean listeningDisabled = true;
    private volatile boolean onlyListenWhilePluggedIn, externallyPowered;

    void beginStart() { pending = true; active = false; suppressed = false; pausedPendingStart = false; }

    void onTelemetry(boolean recording) {
        if (recording && !active && !pending && !pausedPendingStart) suppressed = false;
        active = recording;
        if (recording) { pending = false; pausedPendingStart = false; }
    }

    void onStartFailed() { pending = false; }
    void onStartTimeout() { pending = false; }
    void onDisconnected() { /* The controller continues recording without the phone. */ }
    void suppress() { suppressed = true; pausedPendingStart = pending; }
    void resume() { suppressed = false; pausedPendingStart = false; }
    void setListeningDisabled(boolean disabled) { listeningDisabled = disabled; }
    boolean isListeningDisabled() { return listeningDisabled; }
    void setOnlyListenWhilePluggedIn(boolean required) { onlyListenWhilePluggedIn = required; }
    boolean isOnlyListenWhilePluggedIn() { return onlyListenWhilePluggedIn; }
    void setExternalPowerSource(int plugged) { externallyPowered = plugged > 0; }
    boolean isExternallyPowered() { return externallyPowered; }
    boolean isPowerBlocked() { return onlyListenWhilePluggedIn && !externallyPowered; }
    boolean isPending() { return pending; }
    boolean isActive() { return active; }
    boolean isSuppressed() { return suppressed; }
    boolean shouldRunService() { return !listeningDisabled && !suppressed; }
    boolean shouldCapture() { return shouldRunService() && !isPowerBlocked(); }
    boolean canRecognize() { return shouldCapture(); }
    boolean canLogFeeling() { return active && canRecognize(); }
    boolean shouldStart(boolean visible, boolean serviceRunning) {
        return visible && !serviceRunning && canRecognize();
    }
}
