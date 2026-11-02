package com.aaron.breeze;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class UiConnectionStateTest {
    @Test public void recordingRetainsConnectionWithoutVoiceDreoOrSensors(){
        AtomicLong clock=new AtomicLong(1000);UiConnectionState state=new UiConnectionState(clock::get);
        state.setVisible(true);long session=state.generation();state.setRecording(true);state.setVisible(false);
        clock.addAndGet(28800000);assertTrue(state.allows(session));
        state.setVisible(true);assertEquals(session,state.generation());state.setVisible(false);
        clock.addAndGet(120001);state.setRecording(false);assertFalse(state.allows(session));
    }
    @Test public void recordingDoesNotOverrideExplicitBluetoothDisconnect(){
        AtomicLong clock=new AtomicLong(1000);UiConnectionState state=new UiConnectionState(clock::get);
        state.setRecording(true);assertFalse(state.allows(state.generation()));
        state.setVisible(true);state.setRecording(true);long session=state.generation();state.disconnect();
        state.setRecording(true);assertFalse(state.allows(session));assertFalse(state.allows(state.generation()));
    }
    @Test public void existingRecordingListenerRetainsLockedConnectionWithoutDreo(){
        AtomicLong clock=new AtomicLong(1000);UiConnectionState state=new UiConnectionState(clock::get);
        state.setVisible(true);long session=state.generation();state.setListening(true);state.setVisible(false);
        clock.addAndGet(3600000);assertTrue(state.allows(session));assertFalse(state.requestStart(true));
        state.setVisible(true);assertEquals(session,state.generation());state.setVisible(false);
        clock.addAndGet(120001);state.setListening(false);assertFalse(state.allows(session));
    }
    @Test public void listeningCannotCreateHiddenSessionOrOverrideExplicitDisconnect(){
        AtomicLong clock=new AtomicLong(1000);UiConnectionState state=new UiConnectionState(clock::get);
        state.setListening(true);assertFalse(state.allows(state.generation()));
        state.setVisible(true);state.setListening(true);long old=state.generation();state.setVisible(false);state.disconnect();
        state.setListening(true);assertFalse(state.allows(old));assertFalse(state.allows(state.generation()));
    }
    @Test public void expiredBackgroundSessionCannotBeAdoptedByListener(){
        AtomicLong clock=new AtomicLong(1000);UiConnectionState state=new UiConnectionState(clock::get);
        state.setVisible(true);long session=state.generation();state.setVisible(false);clock.addAndGet(120001);
        state.setListening(true);assertFalse(state.allows(session));
    }
    @Test public void configuredSynchronizationRetainsVisibleSessionAfterLock() {
        AtomicLong clock=new AtomicLong(1000);UiConnectionState state=new UiConnectionState(clock::get);
        state.setVisible(true);long session=state.generation();state.setSynchronizing(true);state.setVisible(false);
        clock.addAndGet(3600000);assertTrue(state.allows(session));assertFalse(state.requestStart(true));
        state.setVisible(true);assertEquals(session,state.generation());
    }

    @Test public void synchronizationCannotStartHiddenOrOverrideDisconnect() {
        AtomicLong clock=new AtomicLong(1000);UiConnectionState state=new UiConnectionState(clock::get);
        state.setSynchronizing(true);assertFalse(state.allows(state.generation()));
        state.setVisible(true);state.setSynchronizing(true);long session=state.generation();
        state.setVisible(false);state.disconnect();state.setSynchronizing(true);
        assertFalse(state.allows(session));assertFalse(state.allows(state.generation()));
    }

    @Test public void disablingSynchronizationRestoresExistingGraceDeadline() {
        AtomicLong clock=new AtomicLong(1000);UiConnectionState state=new UiConnectionState(clock::get);
        state.setVisible(true);long session=state.generation();state.setSynchronizing(true);state.setVisible(false);
        clock.addAndGet(120001);state.setSynchronizing(false);assertFalse(state.allows(session));
    }

    @Test public void hiddenStartsAreRejectedEvenWhenExplicit() {
        AtomicLong clock = new AtomicLong(1000);
        UiConnectionState state = new UiConnectionState(clock::get);
        assertFalse(state.requestStart(false));
        assertFalse(state.requestStart(true));
        assertFalse(state.allows(state.generation()));
        state.setVisible(false);
        assertEquals(0, state.backgroundRemainingMs());
        assertFalse(state.allows(state.generation()));
    }

    @Test public void existingConnectionRemainsAllowedForExactlyTwoMinutes() {
        AtomicLong clock = new AtomicLong(1000);
        UiConnectionState state = new UiConnectionState(clock::get);
        assertEquals(120000, UiConnectionState.BACKGROUND_GRACE_MS);
        state.setVisible(true);
        long session = state.generation();
        assertTrue(state.requestStart(false));
        state.setVisible(false);
        assertTrue(state.allows(session));
        assertEquals(120000, state.backgroundRemainingMs());
        assertFalse(state.requestStart(false));
        assertFalse(state.requestStart(true));
        clock.addAndGet(119999);
        assertTrue(state.allows(session));
        assertEquals(1, state.backgroundRemainingMs());
        clock.incrementAndGet();
        assertFalse(state.allows(session));
        assertEquals(0, state.backgroundRemainingMs());
    }

    @Test public void repeatedHiddenEventsDoNotExtendTheDeadline() {
        AtomicLong clock = new AtomicLong(1000);
        UiConnectionState state = new UiConnectionState(clock::get);
        state.setVisible(true);
        long session = state.generation();
        state.setVisible(false);
        clock.addAndGet(90000);
        state.setVisible(false);
        assertEquals(30000, state.backgroundRemainingMs());
        clock.addAndGet(30000);
        assertFalse(state.allows(session));
        state.setVisible(false);
        assertFalse(state.allows(session));
        assertEquals(0, state.backgroundRemainingMs());
    }

    @Test public void quickReturnKeepsTheSessionAndCancelsTheOldDeadline() {
        AtomicLong clock = new AtomicLong(1000);
        UiConnectionState state = new UiConnectionState(clock::get);
        state.setVisible(true);
        long session = state.generation();
        state.setVisible(false);
        clock.addAndGet(90000);
        state.setVisible(true);
        assertEquals(session, state.generation());
        assertEquals(0, state.backgroundRemainingMs());
        clock.addAndGet(180000);
        assertTrue(state.allows(session));
        state.setVisible(false);
        assertEquals(120000, state.backgroundRemainingMs());
        assertTrue(state.allows(session));
    }

    @Test public void returnAfterExpiryInvalidatesTheOldSession() {
        AtomicLong clock = new AtomicLong(1000);
        UiConnectionState state = new UiConnectionState(clock::get);
        state.setVisible(true);
        long oldSession = state.generation();
        state.setVisible(false);
        clock.addAndGet(120000);
        state.setVisible(true);
        assertFalse(state.allows(oldSession));
        assertTrue(state.requestStart(false));
        assertTrue(state.allows(state.generation()));
    }

    @Test public void manualDisconnectSurvivesRefreshButResetsOnNextVisibleSession() {
        AtomicLong clock = new AtomicLong(1000);
        UiConnectionState state = new UiConnectionState(clock::get);
        state.setVisible(true);
        long pendingStart = state.generation();
        state.disconnect();
        state.setVisible(true);
        assertFalse(state.requestStart(false));
        assertFalse(state.allows(pendingStart));
        state.setVisible(false);
        state.setVisible(true);
        assertTrue(state.requestStart(false));
        assertTrue(state.allows(state.generation()));
    }

    @Test public void explicitConnectCanUndoManualDisconnectWithoutLeavingTheApp() {
        AtomicLong clock = new AtomicLong(1000);
        UiConnectionState state = new UiConnectionState(clock::get);
        state.setVisible(true);
        long staleSession = state.generation();
        state.disconnect();
        assertTrue(state.requestStart(true));
        assertTrue(state.allows(state.generation()));
        assertFalse(state.allows(staleSession));
    }

    @Test public void manualDisconnectDuringGraceEndsTheConnectionImmediately() {
        AtomicLong clock = new AtomicLong(1000);
        UiConnectionState state = new UiConnectionState(clock::get);
        state.setVisible(true);
        long session = state.generation();
        state.setVisible(false);
        clock.addAndGet(1000);
        state.disconnect();
        assertFalse(state.allows(session));
        assertEquals(0, state.backgroundRemainingMs());
        assertFalse(state.requestStart(true));
        state.setVisible(false);
        assertFalse(state.allows(state.generation()));
        state.setVisible(true);
        assertTrue(state.requestStart(false));
        assertFalse(state.allows(session));
        assertTrue(state.allows(state.generation()));
    }
}
