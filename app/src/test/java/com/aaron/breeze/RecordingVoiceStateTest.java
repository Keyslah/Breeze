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

    @Test public void enabledIdleControllerAllowsFanVoiceButNotFeelingLogging() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(false);
        assertFalse(state.isPending());
        assertFalse(state.isActive());
        assertTrue(state.shouldRunService());
        assertTrue(state.canRecognize());
        assertFalse(state.canLogFeeling());
        assertTrue(state.shouldStart(true, false));
        assertFalse(state.shouldStart(false, false));
    }

    @Test public void clockIdleDuringPendingStartAllowsFanVoiceButNotFeelingLogging() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        // Clock-setting responses can report idle before the start reaches the controller.
        state.onTelemetry(false);
        state.onTelemetry(false);
        assertTrue(state.isPending());
        assertFalse(state.isActive());
        assertTrue(state.shouldRunService());
        assertTrue(state.canRecognize());
        assertFalse(state.canLogFeeling());
        // Passive telemetry still cannot start a service without a visible Activity.
        assertFalse(state.shouldStart(false, false));
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
        assertTrue(state.canLogFeeling());
    }

    @Test public void rejectedRecordingStartLeavesIndependentFanVoiceEnabled() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        state.onTelemetry(false);
        state.onStartFailed();
        assertFalse(state.isPending());
        assertFalse(state.isActive());
        assertTrue(state.shouldRunService());
        assertTrue(state.canRecognize());
        assertFalse(state.canLogFeeling());
        assertTrue(state.shouldStart(true, false));
    }

    @Test public void unconfirmedRecordingTimeoutLeavesIndependentFanVoiceEnabled() {
        RecordingVoiceState state = enabledState();
        state.beginStart();
        state.onStartTimeout();
        assertFalse(state.isPending());
        assertFalse(state.isActive());
        assertTrue(state.shouldRunService());
        assertTrue(state.canRecognize());
        assertFalse(state.canLogFeeling());
    }

    @Test public void disconnectDoesNotInventIdleForPendingOrConfirmedRecording() {
        RecordingVoiceState pending = enabledState();
        pending.beginStart();
        pending.onDisconnected();
        assertTrue(pending.isPending());
        assertTrue(pending.shouldRunService());
        assertTrue(pending.canRecognize());
        assertFalse(pending.canLogFeeling());

        RecordingVoiceState active = enabledState();
        active.onTelemetry(true);
        active.onDisconnected();
        assertTrue(active.isActive());
        assertTrue(active.shouldRunService());
        assertTrue(active.canRecognize());
        active.onTelemetry(true);
        assertTrue(active.canRecognize());
    }

    @Test public void confirmedIdleAfterRecordingStopsFeelingLoggingButKeepsFanVoice() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        state.onDisconnected();
        state.onTelemetry(false);
        assertFalse(state.isPending());
        assertFalse(state.isActive());
        assertTrue(state.shouldRunService());
        assertTrue(state.canRecognize());
        assertFalse(state.canLogFeeling());
        assertTrue(state.shouldStart(true, false));
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
        assertTrue(state.isSuppressed());
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
        // If the service disappeared, only the visible app can restart listening.
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
        assertTrue(state.canRecognize());
        assertTrue(state.shouldRunService());
        assertFalse(state.canLogFeeling());
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
        assertTrue(state.canRecognize());
        assertFalse(state.canLogFeeling());
        state.onTelemetry(true);
        assertTrue(state.canRecognize());
    }

    @Test public void powerPolicyDefaultsOffAndDoesNotChangeExistingBatteryListening() {
        RecordingVoiceState state = enabledState();
        state.setExternalPowerSource(0);
        state.onTelemetry(true);
        assertFalse(state.isOnlyListenWhilePluggedIn());
        assertFalse(state.isPowerBlocked());
        assertTrue(state.shouldCapture());
        assertTrue(state.canRecognize());
        assertTrue(state.shouldStart(true, false));
    }

    @Test public void unplugImmediatelyGatesCaptureCommandsAndStartsButRetainsEligibleSession() {
        RecordingVoiceState state = enabledState();
        state.setOnlyListenWhilePluggedIn(true);
        state.setExternalPowerSource(1);
        state.onTelemetry(true);
        assertTrue(state.shouldCapture());
        state.setExternalPowerSource(0);
        assertTrue(state.isPowerBlocked());
        assertTrue(state.isActive());
        assertTrue(state.shouldRunService()); // Existing foreground service can wait without the microphone.
        assertFalse(state.shouldCapture());
        assertFalse(state.canRecognize());
        assertFalse(state.shouldStart(true, false));
        assertFalse(state.isSuppressed());
        state.onDisconnected();
        state.onTelemetry(true);
        assertFalse(state.canRecognize());
        state.resume();
        assertFalse(state.shouldCapture()); // An explicit resume cannot bypass external power.
    }

    @Test public void replugResumesEligibleCaptureButCannotColdStartFromBackground() {
        RecordingVoiceState state = enabledState();
        state.setOnlyListenWhilePluggedIn(true);
        state.onTelemetry(true);
        assertFalse(state.shouldCapture());
        for (int source : new int[] {1, 2, 4, 8}) {
            state.setExternalPowerSource(source); // AC, USB, wireless, and dock power.
            assertTrue(state.isExternallyPowered());
            assertTrue(state.shouldCapture());
            assertTrue(state.canRecognize());
            assertFalse(state.shouldStart(false, false));
            assertFalse(state.shouldStart(true, true));
            state.setExternalPowerSource(0);
            assertFalse(state.canRecognize());
        }
    }

    @Test public void pluggedSourceRatherThanBatteryChargeStatusDeterminesEligibility() {
        RecordingVoiceState state = enabledState();
        state.setOnlyListenWhilePluggedIn(true);
        state.onTelemetry(true);
        state.setExternalPowerSource(-1); // Missing power report cannot authorize capture.
        assertFalse(state.shouldCapture());
        state.setExternalPowerSource(2); // USB remains external power even when full or charge-limited.
        assertTrue(state.shouldCapture());
        state.setExternalPowerSource(0);
        assertFalse(state.shouldCapture());
    }

    @Test public void pendingRecordingCanProceedOnBatteryWithoutStartingTheMicrophone() {
        RecordingVoiceState state = enabledState();
        state.setOnlyListenWhilePluggedIn(true);
        state.beginStart();
        assertTrue(state.isPending());
        assertFalse(state.shouldCapture());
        assertFalse(state.canRecognize());
        state.onTelemetry(false);
        assertTrue(state.isPending());
        state.onTelemetry(true);
        assertTrue(state.isActive());
        assertFalse(state.canRecognize());
        state.setExternalPowerSource(1);
        assertTrue(state.canRecognize());
        state.onTelemetry(false);
        assertTrue(state.shouldRunService());
        assertTrue(state.shouldCapture());
        assertFalse(state.canLogFeeling());
    }

    @Test public void pauseWhileIdleSurvivesRepeatedIdleTelemetryAndPowerChanges() {
        RecordingVoiceState state = enabledState();
        state.suppress();
        for (int i=0;i<20;i++) {
            state.onTelemetry(false);
            state.onDisconnected();
            state.setExternalPowerSource(i%2);
            assertTrue(state.isSuppressed());
            assertFalse(state.shouldRunService());
            assertFalse(state.canRecognize());
            assertFalse(state.shouldStart(true,false));
        }
        state.resume();
        assertTrue(state.canRecognize());
        assertFalse(state.canLogFeeling());
    }

    @Test public void newlyStartedRecordingMayResumeIdlePauseButDoesNotBypassPowerOrDisable() {
        RecordingVoiceState state = enabledState();
        state.suppress();state.onTelemetry(false);state.onTelemetry(true);
        assertFalse(state.isSuppressed());assertTrue(state.canLogFeeling());
        state.onTelemetry(false);state.suppress();state.setOnlyListenWhilePluggedIn(true);
        state.onTelemetry(true);assertFalse(state.canRecognize());assertFalse(state.canLogFeeling());
        state.onTelemetry(false);state.setListeningDisabled(true);state.setExternalPowerSource(1);
        state.onTelemetry(true);assertFalse(state.canRecognize());assertFalse(state.canLogFeeling());
    }

    @Test public void powerChangesAndDisablingPowerPolicyNeverOverrideDisableOrExplicitPause() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        state.setOnlyListenWhilePluggedIn(true);
        state.suppress();
        state.setExternalPowerSource(1);
        assertFalse(state.shouldCapture());
        state.setOnlyListenWhilePluggedIn(false);
        assertTrue(state.isSuppressed());
        assertFalse(state.canRecognize());
        state.resume();
        assertTrue(state.canRecognize());
        state.setListeningDisabled(true);
        for (int source : new int[] {0, 1, 2, 0, 4}) {
            state.setExternalPowerSource(source);
            state.setOnlyListenWhilePluggedIn(source == 0);
            state.resume();
            assertTrue(state.isListeningDisabled());
            assertFalse(state.shouldRunService());
            assertFalse(state.shouldCapture());
            assertFalse(state.canRecognize());
            assertFalse(state.shouldStart(true, false));
        }
    }

    @Test public void removingPowerRestrictionRestoresBatteryCaptureForAnEligibleRecording() {
        RecordingVoiceState state = enabledState();
        state.onTelemetry(true);
        state.setOnlyListenWhilePluggedIn(true);
        assertFalse(state.canRecognize());
        state.setOnlyListenWhilePluggedIn(false);
        assertTrue(state.shouldCapture());
        assertTrue(state.canRecognize());
        assertFalse(state.shouldStart(false, false));
    }
}
