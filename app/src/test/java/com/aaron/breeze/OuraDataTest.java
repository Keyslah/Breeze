package com.aaron.breeze;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class OuraDataTest {
    private JSONObject source()throws Exception{return new JSONObject().put("id","synthetic-period").put("day","2026-11-01").put("type","long_sleep")
        .put("bedtime_start","2026-11-01T00:30:00-05:00").put("bedtime_end","2026-11-01T02:30:00-06:00");}
    private OuraData.Period period(JSONObject source)throws Exception{return new OuraData.Period(OuraData.retain(source,null));}
    @Test public void differenceUnitsAndSummaryLabelsDoNotBecomeAbsoluteTemperatures()throws Exception{
        OuraData.Period p=period(source().put("readiness",new JSONObject().put("temperature_deviation",0.5)).put("average_breath",14.25));
        assertEquals(0.9,OuraData.deviationF(p.temperatureC),0.00001);assertEquals(-1.8,OuraData.deviationF(-1),0);
        assertEquals("Oura temperature deviation: +0.9 °F",p.temperatureLabel());assertTrue(p.breathLabel().contains("sleep-period average"));
    }
    @Test public void returnedSamplingTimestampAndFractionalSecondsArePreserved()throws Exception{
        JSONObject hr=new JSONObject().put("timestamp","2026-11-01T00:31:00-05:00").put("interval",90.5).put("items",new JSONArray().put(60).put(JSONObject.NULL).put(62));
        OuraData.Period p=period(source().put("heart_rate",hr));assertEquals(90500,p.heartIntervalMs,0);assertEquals(181000,p.heartTime(2)-p.heartTime(0),0);
        assertTrue(p.heartValid(0));assertFalse(p.heartValid(1));assertTrue(p.heartValid(2));assertEquals(2,p.heartCount());
        assertEquals(hr.toString(),new JSONObject(p.raw).getJSONObject("heart_rate").toString());
    }
    @Test public void malformedSamplingMetadataDoesNotInventCadence()throws Exception{
        for(Object interval:new Object[]{JSONObject.NULL,0,-1,"300"}){
            JSONObject hr=new JSONObject().put("timestamp","2026-11-01T00:31:00-05:00").put("interval",interval).put("items",new JSONArray().put(60));
            assertEquals(0,period(source().put("heart_rate",hr)).heart.length);
        }
    }
    @Test public void stagesUseActualResolutionAndUnknownCodesStayGaps()throws Exception{
        OuraData.Period coarse=period(source().put("sleep_phase_5_min","41203"));assertEquals(300,coarse.stageIntervalSeconds);assertEquals(0,coarse.stage(3));assertEquals(4,coarse.stage(0));
        OuraData.Period fine=period(source().put("sleep_phase_5_min","111").put("sleep_phase_30_sec","2403"));assertEquals(30,fine.stageIntervalSeconds);assertEquals(90000,fine.stageStart(3)-fine.start);
        assertEquals(300,period(source().put("sleep_phase_30_sec",JSONObject.NULL).put("sleep_phase_5_min","1234")).stageIntervalSeconds);
    }
    @Test public void daylightSavingUsesOffsetsAndCentralDisplay()throws Exception{
        OuraData.Period p=period(source());assertEquals(10800000,p.end-p.start);assertTrue(p.label().contains("00:30"));assertTrue(p.label().contains("02:30"));assertFalse(p.label().contains("CDT"));assertFalse(p.label().contains("CST"));
        long a=OuraData.timestamp("2026-03-08T01:30:00-06:00"),b=OuraData.timestamp("2026-03-08T03:30:00-05:00");assertEquals(3600000,b-a);
        assertFalse(OuraData.overlaps(p.start,p.end,p.end,p.end+1000));assertTrue(OuraData.overlaps(p.start,p.end,p.end-1000,p.end+1000));
    }
    @Test public void optionalMissingFieldsDoNotBlockOtherMeasurements()throws Exception{
        OuraData.Period p=period(source().put("average_breath",15).put("sleep_phase_5_min","1234"));assertTrue(p.temperatureLabel().contains(OuraData.UNAVAILABLE));assertEquals(0,p.heartCount());assertTrue(p.hasStages());assertEquals(15,p.breath,0);
    }
    @Test public void crossMidnightMatchesActualTimeRatherThanAssignedDay()throws Exception{
        OuraData.Period p=period(source().put("bedtime_start","2026-10-31T23:30:00-05:00"));
        long recordingStart=OuraData.timestamp("2026-10-31T23:00:00-05:00"),recordingEnd=OuraData.timestamp("2026-11-01T03:00:00-06:00");
        assertEquals("2026-11-01",p.day);assertTrue(OuraData.overlaps(p.start,p.end,recordingStart,recordingEnd));
        assertFalse(OuraData.overlaps(p.start,p.end,recordingEnd,recordingEnd+3600000));
    }
    @Test public void readinessFallbackKeepsSourceAndOnlyFourMeasurements()throws Exception{
        JSONObject readiness=new JSONObject().put("id","synthetic-readiness").put("timestamp","2026-11-01T09:00:00-06:00").put("temperature_deviation",-0.25).put("score",99);
        JSONObject kept=OuraData.retain(source().put("hrv",new JSONObject()).put("score",99),readiness);
        assertEquals(-0.25,kept.getDouble("temperature_deviation_c"),0);assertEquals("daily_readiness",kept.getString("temperature_source"));assertEquals("synthetic-readiness",kept.getString("temperature_record_id"));
        assertFalse(kept.has("score"));assertFalse(kept.has("hrv"));assertEquals("sleep_period_average",kept.getString("respiratory_kind"));
    }
    @Test public void duplicateSpansPreferReturnedFineResolutionAndSeparatePeriodsRemain()throws Exception{
        OuraData.Period coarse=period(source().put("sleep_phase_5_min","1234"));
        OuraData.Period fine=period(source().put("id","fine").put("sleep_phase_30_sec","1234"));
        OuraData.Period nap=period(source().put("id","nap").put("bedtime_start","2026-11-01T16:00:00-06:00").put("bedtime_end","2026-11-01T17:00:00-06:00"));
        List<OuraData.Period> result=OuraData.displayPeriods(List.of(coarse,fine,nap));assertEquals(2,result.size());assertEquals("fine",result.get(0).id);assertEquals("nap",result.get(1).id);
    }
    @Test public void incompleteDeletedAndRestPeriodsAreNotShown()throws Exception{
        OuraData.Period p=period(source());assertFalse(p.completed(p.end-1));assertTrue(p.completed(p.end));
        assertFalse(period(source().put("type","deleted")).completed(p.end));assertFalse(period(source().put("type","rest")).completed(p.end));
    }
}
