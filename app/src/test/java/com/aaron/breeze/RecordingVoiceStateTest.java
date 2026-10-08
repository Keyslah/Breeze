package com.aaron.breeze;

import org.junit.Test;
import static org.junit.Assert.*;

/** Controller telemetry is simulated; these tests never contact a controller. */
public class RecordingVoiceStateTest {
    private static RecordingVoiceState enabledState() {
        RecordingVoiceState state = new RecordingVoiceState();
        state.setListeningDisabled(false);
        return state;
    }

    @Test public void disabledByDefaultStillTracksSensorRecordingWithoutAListener() {
        RecordingVoiceState state = new RecordingVoiceState();
        assertTrue(state.isListeningDisabled());
        state.beginStart();
        assertTrue(state.isPending());
        assertFalse(state.shouldRunService());
        state.onTelemetry(true);
        assertTrue(state.isActive());
        assertFalse(state.isPending());
        assertFalse(state.shouldRunService());
        assertFalse(state.canRecognize());
    }

    @Test public void persistentDisableVetoSurvivesEveryAutomaticAndExplicitVoiceTransition() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        assertTrue(state.canRecognize());
        state.setListeningDisabled(true);
        assertTrue(state.isActive());
        assertFalse(state.canRecognize());
        for (int cycle = 0; cycle < 3; cycle++) {
            state.resume();
            state.onDisconnected();
            state.onTelemetry(true);
            state.onTelemetry(false);
            state.beginStart();
            state.onStartFailed();
            state.onStartTimeout();
            state.beginStart();
            assertTrue(state.isListeningDisabled());
            assertFalse(state.shouldRunService());
            state.onTelemetry(true);
            assertTrue(state.isActive());
            assertFalse(state.canRecognize());
            assertFalse(state.shouldStart(true, false));
        }
    }

    @Test public void reenablingOnlyRemovesPersistentVetoAndPreservesPerRecordingPause() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        state.suppress();
        state.setListeningDisabled(true);
        state.setListeningDisabled(false);
        assertTrue(state.isActive());
        assertTrue(state.isSuppressed());
        assertFalse(state.canRecognize());
        state.resume();
        assertTrue(state.canRecognize());
        assertTrue(state.shouldStart(true, false));
    }

    @Test public void idleControllerDoesNotRequestAListener() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(false);
        assertFalse(state.isPending());
        assertFalse(state.isActive());
        assertFalse(state.shouldRunService());
        assertFalse(state.canRecognize());
        assertFalse(state.shouldStart(true, false));
    }

    @Test public void clockIdleDuringPendingStartKeepsListenerArmedButDoesNotRecognize() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        // Clock-setting responses can report idle before the start reaches the controller.
        state.onTelemetry(false);
        state.onTelemetry(false);
        assertTrue(state.isPending());
        assertFalse(state.isActive());
        assertTrue(state.shouldRunService());
        assertFalse(state.canRecognize());
        // Pending preparation is explicit; passive telemetry cannot start another service.
        assertFalse(state.shouldStart(true, false));
    }

    @Test public void confirmedRecordingEndsPendingAndEnablesRecognition() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        state.onTelemetry(false);
        state.onTelemetry(true);
        assertFalse(state.isPending());
        assertTrue(state.isActive());
        assertTrue(state.shouldRunService());
        assertTrue(state.canRecognize());
    }

    @Test public void rejectedStartCancelsThePendingListener() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        state.onTelemetry(false);
        state.onStartFailed();
        assertFalse(state.isPending());
        assertFalse(state.isActive());
        assertFalse(state.shouldRunService());
        assertFalse(state.canRecognize());
        assertFalse(state.shouldStart(true, false));
    }

    @Test public void unconfirmedStartTimeoutCancelsThePendingListener() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        state.onStartTimeout();
        assertFalse(state.isPending());
        assertFalse(state.isActive());
        assertFalse(state.shouldRunService());
        assertFalse(state.canRecognize());
    }

    @Test public void disconnectDoesNotInventIdleForPendingOrConfirmedRecording() {
        RecordingVoiceState pending = enabledState();
        pending.beginStart();
        pending.onDisconnected();
        assertTrue(pending.isPending());
        assertTrue(pending.shouldRunService());
        assertFalse(pending.canRecognize());

        RecordingVoiceState active = enabledState();
        active.onTelemetry(true);
        active.onDisconnected();
        assertTrue(active.isActive());
        assertTrue(active.shouldRunService());
        assertTrue(active.canRecognize());
        active.onTelemetry(true);
        assertTrue(active.canRecognize());
    }

    @Test public void confirmedIdleAfterRecordingStopsTheListener() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        state.onDisconnected();
        state.onTelemetry(false);
        assertFalse(state.isPending());
        assertFalse(state.isActive());
        assertFalse(state.shouldRunService());
        assertFalse(state.canRecognize());
        assertFalse(state.shouldStart(true, false));
    }

    @Test public void explicitVoiceStopSurvivesTelemetryAndReconnectUntilTheNextRecording() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        state.suppress();
        assertTrue(state.isSuppressed());
        assertTrue(state.isActive());
        assertFalse(state.shouldRunService());
        assertFalse(state.canRecognize());

        state.onDisconnected();
        for (int repeat = 0; repeat < 5; repeat++) {
            state.onTelemetry(true);
            assertTrue(state.isSuppressed());
            assertFalse(state.shouldStart(true, false));
            assertFalse(state.canRecognize());
        }

        state.onTelemetry(false);
        assertFalse(state.isSuppressed());
        assertFalse(state.shouldRunService());
        state.onTelemetry(true);
        assertTrue(state.shouldStart(true, false));
        assertTrue(state.canRecognize());
    }

    @Test public void duplicateActiveTelemetryDoesNotRestartAnAlreadyRunningService() {
        RecordingVoiceState state = enabledState();
        boolean serviceRunning = false;
        int startRequests = 0;
        for (int repeat = 0; repeat < 20; repeat++) {
            state.onTelemetry(true);
            if (state.shouldStart(true, serviceRunning)) {
                startRequests++;
                serviceRunning = true;
            }
        }
        assertEquals(1, startRequests);
        assertTrue(state.canRecognize());
    }

    @Test public void automaticRejoinWaitsForAVisibleActivityAndNeverDuplicatesService() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        assertFalse(state.shouldStart(false, false));
        assertTrue(state.shouldStart(true, false));
        assertFalse(state.shouldStart(true, true));
        assertFalse(state.shouldStart(false, true));
        // If the service disappeared, only the visible app can rejoin this recording.
        state.onDisconnected();
        state.onTelemetry(true);
        assertFalse(state.shouldStart(false, false));
        assertTrue(state.shouldStart(true, false));
        state.suppress();
        assertFalse(state.shouldStart(true, false));
    }

    @Test public void lateStartFailureAndTimeoutCannotCutOffAConfirmedRecording() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        state.onTelemetry(true);
        // A delayed start callback is no longer authority over an active recording.
        state.onStartFailed();
        state.onStartTimeout();
        state.onDisconnected();
        state.onStartTimeout();
        assertTrue(state.isActive());
        assertFalse(state.isPending());
        assertTrue(state.shouldRunService());
        assertTrue(state.canRecognize());
    }

    @Test public void explicitResumeCanReenableVoiceDuringTheSameRecording() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        state.suppress();
        state.resume();
        assertFalse(state.isSuppressed());
        assertTrue(state.canRecognize());
        assertTrue(state.shouldStart(true, false));
        state.onTelemetry(false);
        state.resume();
        assertFalse(state.canRecognize());
        assertFalse(state.shouldRunService());
    }

    @Test public void stopDuringPendingStartSurvivesClockIdleAndLateActiveTelemetry() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        state.suppress();
        state.onTelemetry(false);
        assertTrue(state.isPending());
        assertTrue(state.isSuppressed());
        assertFalse(state.shouldRunService());
        state.onStartTimeout();
        assertFalse(state.isPending());
        assertTrue(state.isSuppressed());
        // The controller may have started even though its acknowledgement was late.
        state.onTelemetry(true);
        assertTrue(state.isActive());
        assertTrue(state.isSuppressed());
        assertFalse(state.canRecognize());
        assertFalse(state.shouldStart(true, false));
        state.onTelemetry(false);
        state.onTelemetry(true);
        assertFalse(state.isSuppressed());
        assertTrue(state.shouldStart(true, false));
    }

    @Test public void aFreshExplicitStartClearsSuppressionAfterAnAbandonedAttempt() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        state.suppress();
        state.onStartFailed();
        assertTrue(state.isSuppressed());
        assertFalse(state.shouldRunService());
        state.beginStart();
        assertTrue(state.isPending());
        assertFalse(state.isSuppressed());
        assertTrue(state.shouldRunService());
        assertFalse(state.canRecognize());
        state.onTelemetry(true);
        assertTrue(state.canRecognize());
    }
}
