package com.aaron.breeze;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aaron.breeze.RecordingReconciler.Action.*;

public class RecordingReconcilerTest {
    private RecordingIntent intent(boolean active,long revision){return new RecordingIntent(true,active,42,revision);}
    @Test public void startOfflineThenReconnectStartsWithoutChangingIntent(){
        RecordingReconciler r=new RecordingReconciler();RecordingIntent desired=intent(true,1);
        assertEquals(NONE,r.next(desired,false,false,false,0));
        assertEquals(START,r.next(desired,true,false,true,1));
        assertTrue(desired.active);assertEquals(42,desired.session);
    }
    @Test public void storageAndSensorAvailabilityCannotStopSession(){
        RecordingReconciler r=new RecordingReconciler();RecordingIntent desired=intent(true,1);
        assertEquals(NONE,r.next(desired,true,false,false,0));
        assertEquals(NONE,r.next(desired,false,false,false,100));
        assertEquals(START,r.next(desired,true,false,true,200));
    }
    @Test public void controllerStopAndRebootResumeSameUserIntent(){
        RecordingReconciler r=new RecordingReconciler();RecordingIntent desired=intent(true,1);
        assertEquals(NONE,r.next(desired,true,true,true,0));
        r.disconnected();assertEquals(START,r.next(desired,true,false,true,1));
        long old=r.attempt();r.disconnected();assertFalse(r.current(old));
        assertEquals(START,r.next(desired,true,false,true,2));assertNotEquals(old,r.attempt());
    }
    @Test public void explicitStopOfflineStopsHardwareAfterReconnect(){
        RecordingReconciler r=new RecordingReconciler();RecordingIntent desired=intent(false,2);
        assertEquals(NONE,r.next(desired,false,true,false,0));
        assertEquals(STOP,r.next(desired,true,true,false,1));
    }
    @Test public void stopDuringClockHandshakeInvalidatesStart(){
        RecordingReconciler r=new RecordingReconciler();assertEquals(START,r.next(intent(true,1),true,false,true,0));
        assertFalse(RecordingReconciler.mayStart(intent(false,2),1));
        r.completed(r.attempt(),1);
        assertEquals(STOP,r.next(intent(false,2),true,true,true,2));
    }
    @Test public void newerStartAlsoInvalidatesOldStart(){
        assertFalse(RecordingReconciler.mayStart(intent(true,3),1));
        assertTrue(RecordingReconciler.mayStart(intent(true,3),3));
    }
    @Test public void retriesAreSerializedAndBounded(){
        RecordingReconciler r=new RecordingReconciler();RecordingIntent desired=intent(true,1);
        assertEquals(START,r.next(desired,true,false,true,0));
        assertEquals(NONE,r.next(desired,true,false,true,20000));
        r.completed(r.attempt(),20000);
        assertEquals(NONE,r.next(desired,true,false,true,29999));
        assertEquals(START,r.next(desired,true,false,true,30000));
    }
    @Test public void installationDoesNotStartOrStopLegacyController(){
        RecordingReconciler r=new RecordingReconciler();
        assertEquals(NONE,r.next(RecordingIntent.UNKNOWN,true,true,true,0));
        assertEquals(NONE,r.next(RecordingIntent.UNKNOWN,true,false,true,0));
    }
}
