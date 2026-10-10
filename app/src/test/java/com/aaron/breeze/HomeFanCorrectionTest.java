package com.aaron.breeze;

import org.junit.Test;
import static org.junit.Assert.*;

public class HomeFanCorrectionTest {
    @Test public void authoritativeDraftMovesWithKnownManualCommands(){
        assertEquals(Integer.valueOf(4),HomeFanCorrection.afterManual(1,3,-1));
        assertEquals(Integer.valueOf(2),HomeFanCorrection.afterManual(2,3,-1));
        assertEquals(Integer.valueOf(0),HomeFanCorrection.afterManual(0,3,-1));
        assertEquals(Integer.valueOf(3),HomeFanCorrection.afterManual(4,3,-1));
        assertEquals(Integer.valueOf(3),HomeFanCorrection.afterManual(5,3,-1));
        assertEquals(Integer.valueOf(3),HomeFanCorrection.afterManual(0,0,3));
    }
    @Test public void allTenOnLevelsWrapAndPowerRetainsHighest(){
        for(int setting=1;setting<=10;setting++){
            assertEquals(Integer.valueOf(setting==10?1:setting+1),HomeFanCorrection.afterManual(1,setting,-1));
            assertEquals(Integer.valueOf(setting==1?10:setting-1),HomeFanCorrection.afterManual(2,setting,-1));
        }
        assertEquals(Integer.valueOf(0),HomeFanCorrection.afterManual(0,10,-1));
        assertEquals(Integer.valueOf(10),HomeFanCorrection.afterManual(0,0,10));
        assertEquals(Integer.valueOf(10),HomeFanCorrection.afterManual(4,10,-1));
        assertEquals(Integer.valueOf(10),HomeFanCorrection.afterManual(5,10,-1));
    }
    @Test public void unknownModeAndRestoreNeverInventASetting(){
        assertNull(HomeFanCorrection.afterManual(3,3,3));
        assertNull(HomeFanCorrection.afterManual(6,3,3));
        assertNull(HomeFanCorrection.afterManual(1,0,3));
        assertNull(HomeFanCorrection.afterManual(2,0,3));
        assertNull(HomeFanCorrection.afterManual(0,0,-1));
        assertNull(HomeFanCorrection.afterManual(0,0,0));
        assertNull(HomeFanCorrection.afterManual(0,0,11));
        assertNull(HomeFanCorrection.afterManual(4,-1,3));
        assertNull(HomeFanCorrection.afterManual(5,11,3));
    }
}
