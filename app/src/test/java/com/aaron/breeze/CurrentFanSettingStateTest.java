package com.aaron.breeze;

import org.junit.Test;
import static org.junit.Assert.*;

public class CurrentFanSettingStateTest {
    private static Protocol.FanState status(int speed,boolean synced){
        Protocol.FanState status=new Protocol.FanState();status.speed=speed;status.flags=synced?4:0;return status;
    }

    @Test public void followsFreshSpeedsIncludingOffInsteadOfSavedAnchor(){
        MainActivity.CurrentFanSettingState state=new MainActivity.CurrentFanSettingState();
        assertEquals("4",state.display(false,1,status(4,true),true));
        assertEquals("3",state.display(false,1,status(3,true),true));
        assertEquals("0",state.display(false,1,status(0,true),true));
    }

    @Test public void followsHighestAndWraparoundWithoutStickingAtNine(){
        MainActivity.CurrentFanSettingState state=new MainActivity.CurrentFanSettingState();
        for(int speed:new int[]{1,2,3,4,5,6,7,8,9,10,1,10,9,0})
            assertEquals(FanSetting.label(speed),state.display(false,1,status(speed,true),true));
        assertEquals("H",state.display(false,10,status(0,false),true));
        assertEquals("H",state.display(false,10,null,false));
    }

    @Test public void highestCorrectionSurvivesAckThenFollowsLiveWrap(){
        MainActivity.CurrentFanSettingState state=new MainActivity.CurrentFanSettingState();
        Protocol.FanState before=status(9,true);
        state.correction(10,before);
        assertEquals("H",state.display(true,10,before,true));
        assertEquals("H",state.display(false,10,before,true));
        assertEquals("H",state.display(false,10,status(10,true),true));
        assertEquals("1",state.display(false,10,status(1,true),true));
        assertFalse(MainActivity.CurrentFanSettingState.edited("h","H"));
        assertFalse(MainActivity.CurrentFanSettingState.edited("10","H"));
        assertTrue(MainActivity.CurrentFanSettingState.edited("9","H"));
    }

    @Test public void unknownAndStaleTelemetryKeepTheLastSettingOrDefaultOne(){
        MainActivity.CurrentFanSettingState state=new MainActivity.CurrentFanSettingState();
        assertEquals("1",state.display(false,1,status(1,false),true));
        assertEquals("1",state.display(false,1,status(4,true),false));
        assertEquals("1",state.display(false,1,null,false));
        assertEquals("4",state.display(false,4,status(0,false),true));
        assertEquals("0",state.display(false,0,null,false));
        assertFalse(MainActivity.CurrentFanSettingState.edited("",""));
    }

    @Test public void onlyChangedInputCountsAsEditing(){
        assertFalse(MainActivity.CurrentFanSettingState.edited("1","1"));
        assertTrue(MainActivity.CurrentFanSettingState.edited("4","1"));
        assertTrue(MainActivity.CurrentFanSettingState.edited("","1"));
        assertTrue(MainActivity.CurrentFanSettingState.edited("4",""));
    }

    @Test public void correctionSurvivesAckUntilFreshStatusCatchesUp(){
        MainActivity.CurrentFanSettingState state=new MainActivity.CurrentFanSettingState();
        Protocol.FanState old=status(1,true);
        state.correction(6,old);
        assertEquals("6",state.display(true,6,old,true));
        assertEquals("6",state.display(false,6,old,true));
        assertEquals("6",state.display(false,6,status(1,true),false));
        assertEquals("6",state.display(false,6,status(6,true),true));
        assertEquals("7",state.display(false,6,status(7,true),true));
    }

    @Test public void newerStatusBeforeAckDoesNotEraseCorrection(){
        MainActivity.CurrentFanSettingState state=new MainActivity.CurrentFanSettingState();
        assertEquals("0",state.display(true,0,status(3,true),true));
        Protocol.FanState beforeAck=status(3,true);
        assertEquals("0",state.display(true,0,beforeAck,true));
        assertEquals("0",state.display(false,0,beforeAck,true));
        assertEquals("0",state.display(false,0,status(0,false),true));
    }

    @Test public void offlineCorrectionRemainsPendingUntilFreshControllerStatus(){
        MainActivity.CurrentFanSettingState state=new MainActivity.CurrentFanSettingState();
        assertEquals("5",state.display(true,5,null,false));
        assertEquals("5",state.display(false,5,null,false));
        assertEquals("5",state.display(false,5,status(5,true),true));
        assertEquals("0",state.display(false,5,status(0,true),true));
    }
}
