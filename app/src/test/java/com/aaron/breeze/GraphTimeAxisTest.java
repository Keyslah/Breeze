package com.aaron.breeze;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class GraphTimeAxisTest {
    private long time(String value){return OuraData.timestamp(value);}
    @Test public void clockAndTwoHourMarksUseLocalMilitaryTime(){
        long start=time("2026-11-03T00:53:28-06:00"),end=time("2026-11-03T07:02:28-06:00");
        assertEquals("00:53",GraphTimeAxis.label(start));assertEquals("03:57",GraphTimeAxis.label(start+(end-start)/2));assertEquals("07:02",GraphTimeAxis.label(end));
        assertEquals(List.of(time("2026-11-03T02:00:00-06:00"),time("2026-11-03T04:00:00-06:00"),time("2026-11-03T06:00:00-06:00")),GraphTimeAxis.ticks(start,end));
    }
    @Test public void ticksAreInsideEndpointsAndCrossMidnight(){
        long start=time("2026-11-02T22:00:00-06:00"),end=time("2026-11-03T04:00:00-06:00");
        assertEquals(List.of(start+7200000,start+14400000),GraphTimeAxis.ticks(start,end));
        assertEquals("00:00",GraphTimeAxis.label(start+7200000));
        assertTrue(GraphTimeAxis.ticks(start,start+30000).isEmpty());
    }
    @Test public void daylightSavingTicksKeepActualOffsets(){
        assertEquals(List.of(time("2026-11-01T02:00:00-06:00")),GraphTimeAxis.ticks(time("2026-11-01T00:30:00-05:00"),time("2026-11-01T02:30:00-06:00")));
        assertEquals(List.of(time("2026-03-08T04:00:00-05:00")),GraphTimeAxis.ticks(time("2026-03-08T01:30:00-06:00"),time("2026-03-08T04:30:00-05:00")));
    }
}
