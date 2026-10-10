package com.aaron.breeze;

import org.junit.Test;
import static org.junit.Assert.*;

public class FanSettingTest {
    @Test public void offThroughHighestRoundTrip(){
        for(int setting=0;setting<=10;setting++){
            String label=setting==10?"H":Integer.toString(setting);
            assertEquals(label,FanSetting.label(setting));
            assertEquals(setting,FanSetting.parse(label));
            assertEquals(setting,FanSetting.parse(Integer.toString(setting)));
            assertTrue(FanSetting.valid(setting));
            assertEquals(setting>0,FanSetting.validOn(setting));
        }
        assertEquals(10,FanSetting.parse(" h "));
    }
    @Test public void invalidSettingsStayInvalid(){
        for(int value:new int[]{-1,11,255}){assertFalse(FanSetting.valid(value));assertFalse(FanSetting.validOn(value));assertEquals("—",FanSetting.label(value));}
        for(String text:new String[]{null,"","11","-1","3.5","High"})assertThrows(IllegalArgumentException.class,()->FanSetting.parse(text));
    }
}
