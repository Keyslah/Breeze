package com.aaron.breeze;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DreoTemperatureTest {
    private static final long NOW=1793136000000L;
    private static DreoReport report(String fields)throws Exception{
        return DreoReport.parse(new JSONObject("{\"code\":0,\"data\":{\"sn\":\"fan\",\"mixed\":{"+fields+"}}}"),"fan",NOW-1000,NOW);
    }
    @Test public void supportedModelTemperatureIsFahrenheitEvenBelowFifty()throws Exception{
        DreoReport report=report("\"temperature\":{\"state\":41,\"timestamp\":1779310497},\"connected\":{\"state\":true}");
        assertEquals(41,report.temperatureF,0);assertEquals(5,report.temperatureC(),0.000001);
        assertEquals(1779310497000L,report.temperatureTimestamp);
        assertEquals(0,DreoTemperature.celsius(32,"F"),0);
        assertEquals(100,DreoTemperature.celsius(212,"F"),0.000001);
    }
    @Test public void optionalTemperatureDoesNotChangeFanPolicyFields()throws Exception{
        DreoReport report=report("\"temperature\":{\"state\":69,\"timestamp\":1779310497},\"mode\":{\"state\":4},\"poweron\":{\"state\":false}");
        assertEquals((69-32)/1.8,report.temperatureC(),0.000001);
        assertEquals(Integer.valueOf(4),report.mode);assertEquals(Boolean.FALSE,report.power);assertNull(report.speed);
    }
    @Test public void invalidOrUnavailableValuesRemainGaps()throws Exception{
        for(String value:new String[]{"null","\"72\"","true","999","-100"}){
            DreoReport report=report("\"temperature\":{\"state\":"+value+",\"timestamp\":1779310497}");
            assertNull(value,report.temperatureF);assertTrue(Double.isNaN(report.temperatureC()));
        }
        assertTrue(Double.isNaN(report("").temperatureC()));
        assertTrue(Double.isNaN(report("\"temperature\":{\"state\":72}").temperatureC()));
        assertTrue(Double.isNaN(report("\"temperature\":{\"state\":72,\"timestamp\":1793136301}").temperatureC()));
        DreoReport offline=report("\"temperature\":{\"state\":72,\"timestamp\":1779310497},\"connected\":{\"state\":false}");
        assertEquals(72,offline.temperatureF,0);assertTrue(Double.isNaN(offline.temperatureC()));
        assertTrue(Double.isNaN(DreoTemperature.celsius(72,null)));
    }
    @Test public void unchangedOldValueTimestampIsNotBackdated()throws Exception{
        DreoReport report=report("\"temperature\":{\"state\":69,\"timestamp\":1779310497}");
        assertEquals(NOW,report.receivedAtMs);assertEquals(1779310497000L,report.temperatureTimestamp);
        assertTrue(Double.isFinite(report.temperatureC()));
    }
}
