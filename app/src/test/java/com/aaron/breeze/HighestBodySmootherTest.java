package com.aaron.breeze;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class HighestBodySmootherTest {
    private static long[] seconds(long... seconds){long[] times=seconds.clone();for(int i=0;i<times.length;i++)times[i]*=1000;return times;}
    private static void values(HighestBodySmoother.Result result,double... expected){
        assertEquals(expected.length,result.size());
        for(int i=0;i<expected.length;i++)assertEquals("sample "+i,expected[i],result.valueAt(i),1e-10);
    }

    @Test public void averagesTwoMinutesCenteredOnActualTimestampsIncludingEdges(){
        HighestBodySmoother.Result result=HighestBodySmoother.smooth(seconds(0,30,60,90,120,150),new double[]{10,20,30,40,50,60});
        values(result,20,25,30,40,45,50);
        assertTrue(result.sameSegment(0,5));
    }

    @Test public void shortSpikesAreAveragedWithoutShiftingTheDisplayedTimestamp(){
        long[] times=new long[301];double[] raw=new double[301];Arrays.fill(raw,30);
        for(int i=0;i<times.length;i++)times[i]=i*1000L;raw[150]=42;
        HighestBodySmoother.Result result=HighestBodySmoother.smooth(times,raw);
        assertEquals(30,result.valueAt(89),1e-10);assertEquals(30+12d/121,result.valueAt(90),1e-10);
        assertEquals(30+12d/121,result.valueAt(150),1e-10);assertEquals(30+12d/121,result.valueAt(210),1e-10);assertEquals(30,result.valueAt(211),1e-10);
        assertEquals(42,raw[150],0);assertEquals(150000,times[150]);
    }

    @Test public void calculatesTheMaximumBeforeAveragingRatherThanAveragingIndividualProbes(){
        double[] raw={HighestBodySmoother.highest(30,34,31),HighestBodySmoother.highest(34,30,31)};
        values(HighestBodySmoother.smooth(seconds(0,10),raw),34,34);
        assertEquals(33,HighestBodySmoother.highest(Double.NaN,32,33),0);
        assertEquals(-5,HighestBodySmoother.highest(-5,Double.NaN,Double.NEGATIVE_INFINITY),0);
        assertTrue(Double.isNaN(HighestBodySmoother.highest(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)));
    }

    @Test public void missingReadingsBreakTheAverageAndAreNeverFilled(){
        HighestBodySmoother.Result result=HighestBodySmoother.smooth(seconds(0,10,20,30,40,50,60),new double[]{30,32,Double.NaN,38,40,Double.POSITIVE_INFINITY,10});
        values(result,31,31,Double.NaN,39,39,Double.NaN,10);
        assertTrue(result.sameSegment(0,1));assertFalse(result.sameSegment(1,3));assertFalse(result.sameSegment(2,2));assertFalse(result.sameSegment(4,6));
    }

    @Test public void historicalThirtySecondSamplesStayContinuousButRecordingGapsDoNotBlend(){
        HighestBodySmoother.Result result=HighestBodySmoother.smooth(seconds(0,30,65,101,111),new double[]{30,30,30,40,40});
        values(result,30,30,30,40,40);
        assertTrue(result.sameSegment(0,2));assertFalse(result.sameSegment(2,3));assertTrue(result.sameSegment(3,4));
    }

    @Test public void irregularTimesUseTimeDistanceRatherThanAFixedSampleCount(){
        HighestBodySmoother.Result result=HighestBodySmoother.smooth(seconds(0,1,2,30,60,61,90),new double[]{10,20,30,40,50,60,70});
        values(result,30,35,35,40,40,45,55);
    }

    @Test public void duplicateTimesRemainSeparateSamplesAndClockReversalStartsANewSegment(){
        HighestBodySmoother.Result result=HighestBodySmoother.smooth(seconds(10,10,20,5,15),new double[]{10,20,30,40,50});
        values(result,20,20,20,45,45);assertTrue(result.sameSegment(0,2));assertFalse(result.sameSegment(2,3));
    }

    @Test public void inputsAreUntouchedAndEachCalculationIsIndependent(){
        long[] times=seconds(0,10,20);double[] raw={30,45,30};long[] beforeTimes=times.clone();double[] beforeRaw=raw.clone();
        HighestBodySmoother.Result first=HighestBodySmoother.smooth(times,raw);values(first,35,35,35);
        assertArrayEquals(beforeTimes,times);assertArrayEquals(beforeRaw,raw,0);
        raw[1]=60;values(first,35,35,35);values(HighestBodySmoother.smooth(times,raw),40,40,40);
    }

    @Test public void emptySingletonMissingAndInvalidInputsAreExplicit(){
        values(HighestBodySmoother.smooth(new long[0],new double[0]));
        values(HighestBodySmoother.smooth(seconds(0),new double[]{32}),32);
        values(HighestBodySmoother.smooth(seconds(0),new double[]{Double.NaN}),Double.NaN);
        assertThrows(IllegalArgumentException.class,()->HighestBodySmoother.smooth(null,new double[0]));
        assertThrows(IllegalArgumentException.class,()->HighestBodySmoother.smooth(new long[0],null));
        assertThrows(IllegalArgumentException.class,()->HighestBodySmoother.smooth(seconds(0),new double[0]));
    }
}
