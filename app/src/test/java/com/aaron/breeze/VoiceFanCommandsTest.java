package com.aaron.breeze;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VoiceFanCommandsTest {
    private static final class Target implements VoiceFanCommands.Target {
        boolean eligible=true, ready=true, logicReady=true, logicEnabled, accept=true;
        int remoteCalls, logicCalls, synchronizeCalls, lastSlot=-1;
        boolean requestedLogic;
        Consumer<String> pending;
        @Override public boolean eligible() { return eligible; }
        @Override public boolean ready() { return ready; }
        @Override public boolean logicReady() { return logicReady; }
        @Override public boolean logicEnabled() { return logicEnabled; }
        @Override public boolean remote(int slot, Consumer<String> result) {
            remoteCalls++; lastSlot=slot; if(accept)pending=result; return accept;
        }
        @Override public boolean logic(boolean enabled, Consumer<String> result) {
            logicCalls++; requestedLogic=enabled; if(accept)pending=result; return accept;
        }
        @Override public boolean synchronize(Consumer<String> result) {
            synchronizeCalls++; if(accept)pending=result; return accept;
        }
        @Override public String failure() { return "Not sent · the controller refused the command."; }
        int calls() { return remoteCalls+logicCalls+synchronizeCalls; }
    }

    @Test public void powerAndOscillationUseOnlyTheirExistingRemoteSlots() {
        VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();List<String> results=new ArrayList<>();
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.POWER,0,target,results::add));
        assertEquals(0,target.lastSlot);assertEquals(1,target.remoteCalls);
        target.pending.accept("Power sent");assertEquals("Power sent",results.get(0));
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.OSCILLATE,2500,target,results::add));
        assertEquals(4,target.lastSlot);assertEquals(2,target.remoteCalls);
        assertEquals(0,target.logicCalls);assertEquals(0,target.synchronizeCalls);
    }

    @Test public void logicToggleReadsCurrentControllerStateEachTime() {
        VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();
        target.logicEnabled=false;
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.LOGIC,100,target,value->{}));
        assertTrue(target.requestedLogic);
        // A physical/other UI change is authoritative, even if it differs from our last request.
        target.logicEnabled=false;
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.LOGIC,2600,target,value->{}));
        assertTrue(target.requestedLogic);
        target.logicEnabled=true;
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.LOGIC,5100,target,value->{}));
        assertFalse(target.requestedLogic);assertEquals(3,target.logicCalls);assertEquals(0,target.remoteCalls);
    }

    @Test public void fixFanUsesDedicatedSynchronizationWithoutSendingARemoteButton() {
        VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();List<String> results=new ArrayList<>();
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.FIX_FAN,0,target,results::add));
        assertEquals(1,target.synchronizeCalls);assertEquals(0,target.remoteCalls);assertEquals(0,target.logicCalls);
        target.pending.accept("Dreo state synchronized");assertEquals("Dreo state synchronized",results.get(0));
    }

    @Test public void disabledPausedOrPowerBlockedEligibilityRejectsEveryHardwareAction() {
        for(BedtimeVoiceCommand.Kind kind:hardwareKinds()){
            VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();List<String> results=new ArrayList<>();
            target.eligible=false;
            assertFalse(dispatcher.dispatch(kind,0,target,results::add));
            assertEquals(0,target.calls());assertTrue(results.get(0).contains("paused or disabled"));
            target.eligible=true;
            assertEquals(0,target.calls()); // Reenabling does not replay a rejected command.
            assertTrue(dispatcher.dispatch(kind,1,target,results::add));assertEquals(1,target.calls());
        }
    }

    @Test public void busyOfflineAndStaleConnectionsRejectWithoutRetainingCommands() {
        for(BedtimeVoiceCommand.Kind kind:hardwareKinds()){
            VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();List<String> results=new ArrayList<>();
            target.ready=false;
            assertFalse(dispatcher.dispatch(kind,0,target,results::add));
            assertEquals(0,target.calls());assertTrue(results.get(0).contains("fresh, idle"));
            target.ready=true;assertEquals(0,target.calls());
            assertTrue(dispatcher.dispatch(kind,1,target,results::add));assertEquals(1,target.calls());
        }
    }

    @Test public void logicRequiresItsOwnFreshReadinessWithoutBlockingIndependentActions() {
        VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();target.logicReady=false;
        assertFalse(dispatcher.dispatch(BedtimeVoiceCommand.Kind.LOGIC,0,target,value->{}));
        assertEquals(0,target.calls());
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.OSCILLATE,1,target,value->{}));
        assertEquals(4,target.lastSlot);
    }

    @Test public void repeatGuardCoversAllHardwareKindsAndAllowsExactBoundary() {
        VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();List<String> results=new ArrayList<>();
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.POWER,1000,target,results::add));
        for(BedtimeVoiceCommand.Kind kind:hardwareKinds())assertFalse(dispatcher.dispatch(kind,3499,target,results::add));
        assertEquals(1,target.calls());assertTrue(results.get(0).contains("wait a moment"));
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.OSCILLATE,3500,target,results::add));
        assertEquals(2,target.calls());
    }

    @Test public void rejectedTransportDoesNotConsumeCooldownOrReplayLater() {
        VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();List<String> results=new ArrayList<>();
        target.accept=false;
        assertFalse(dispatcher.dispatch(BedtimeVoiceCommand.Kind.POWER,0,target,results::add));
        assertEquals(1,target.calls());assertEquals(target.failure(),results.get(0));assertNull(target.pending);
        target.accept=true;assertEquals(1,target.calls());
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.LOGIC,1,target,results::add));
        assertEquals(2,target.calls());assertEquals(1,target.remoteCalls);
    }

    @Test public void asynchronousCommandFailureNeverAutomaticallyRetriesOrSkipsRepeatGuard() {
        VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();List<String> results=new ArrayList<>();
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.POWER,0,target,results::add));
        target.pending.accept("Controller acknowledgement timed out");
        assertEquals(1,target.calls());assertEquals(1,results.size());
        assertFalse(dispatcher.dispatch(BedtimeVoiceCommand.Kind.POWER,2499,target,results::add));
        assertEquals(1,target.calls());
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.POWER,2500,target,results::add));
        assertEquals(2,target.calls());
    }

    @Test public void nonHardwareCommandsNeverReachControllerOrConsumeRepeatGuard() {
        VoiceFanCommands dispatcher=new VoiceFanCommands();Target target=new Target();List<String> results=new ArrayList<>();
        for(BedtimeVoiceCommand.Kind kind:BedtimeVoiceCommand.Kind.values()){
            if(kind==BedtimeVoiceCommand.Kind.POWER||kind==BedtimeVoiceCommand.Kind.LOGIC
                    ||kind==BedtimeVoiceCommand.Kind.OSCILLATE||kind==BedtimeVoiceCommand.Kind.FIX_FAN)continue;
            assertFalse(dispatcher.dispatch(kind,0,target,results::add));
        }
        assertEquals(0,target.calls());assertTrue(results.isEmpty());
        assertTrue(dispatcher.dispatch(BedtimeVoiceCommand.Kind.POWER,1,target,results::add));
    }

    private static BedtimeVoiceCommand.Kind[] hardwareKinds() {
        return new BedtimeVoiceCommand.Kind[]{BedtimeVoiceCommand.Kind.POWER,BedtimeVoiceCommand.Kind.LOGIC,
                BedtimeVoiceCommand.Kind.OSCILLATE,BedtimeVoiceCommand.Kind.FIX_FAN};
    }
}
