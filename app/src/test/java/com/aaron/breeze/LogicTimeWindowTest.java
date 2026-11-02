package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class LogicTimeWindowTest {
    private static LogicProfile enabled(){LogicProfile p=new LogicProfile();p.timeWindow.enabled=true;return p;}
    private static ByteBuffer buffer(byte[] packet){return ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);}
    private static long epoch(String time){return Instant.parse(time).getEpochSecond();}
    private static LogicProfile.TimeWindow window(int start,int end,int adjustment){
        LogicProfile.TimeWindow w=new LogicProfile.TimeWindow();w.enabled=true;w.startMinute=start;w.endMinute=end;w.adjustment=adjustment;return w;
    }

    @Test public void overlapsAddTheirAdjustmentsWithIndependentStartAndEndTimes(){
        LogicProfile p=new LogicProfile();p.replaceTimeWindows(Arrays.asList(window(270,480,-1),window(360,540,-3)));
        assertTrue(p.hasEnabledTimeWindows());assertEquals(0,p.windowAdjustmentAtMinute(269));
        assertEquals(-1,p.windowAdjustmentAtMinute(270));assertEquals(-1,p.windowAdjustmentAtMinute(359));
        assertEquals(-4,p.windowAdjustmentAtMinute(360));assertEquals(-4,p.windowAdjustmentAtMinute(479));
        assertEquals(-3,p.windowAdjustmentAtMinute(480));assertEquals(-3,p.windowAdjustmentAtMinute(539));assertEquals(0,p.windowAdjustmentAtMinute(540));
        assertEquals(3,p.adjustLevel(7,360));assertEquals(0,p.adjustLevel(3,360));
        p.timeWindows.get(1).enabled=false;assertEquals(-1,p.windowAdjustmentAtMinute(360));
        p.timeWindow.enabled=false;assertFalse(p.hasEnabledTimeWindows());assertEquals(7,p.adjustLevel(7,360));
    }

    @Test public void overnightAndMorningWindowsAccumulateAtMidnightBoundaries(){
        LogicProfile p=new LogicProfile();p.replaceTimeWindows(Arrays.asList(window(1380,120,-1),window(60,180,-3)));
        assertEquals(0,p.windowAdjustmentAtMinute(1379));assertEquals(-1,p.windowAdjustmentAtMinute(1380));
        assertEquals(-1,p.windowAdjustmentAtMinute(1439));assertEquals(-1,p.windowAdjustmentAtMinute(0));assertEquals(-1,p.windowAdjustmentAtMinute(59));
        assertEquals(-4,p.windowAdjustmentAtMinute(60));assertEquals(-4,p.windowAdjustmentAtMinute(119));
        assertEquals(-3,p.windowAdjustmentAtMinute(120));assertEquals(-3,p.windowAdjustmentAtMinute(179));assertEquals(0,p.windowAdjustmentAtMinute(180));
    }

    @Test public void mixedSignsSumBeforeClampingAndDoNotDependOnWindowOrder(){
        LogicProfile p=new LogicProfile();p.replaceTimeWindows(Arrays.asList(window(0,60,-10),window(0,60,10)));
        assertEquals(0,p.windowAdjustmentAtMinute(30));assertEquals(0,p.adjustLevel(0,30));assertEquals(10,p.adjustLevel(10,30));
        p.replaceTimeWindows(Arrays.asList(window(0,60,10),window(0,60,-10)));
        assertEquals(0,p.adjustLevel(0,30));assertEquals(10,p.adjustLevel(10,30));
        p.replaceTimeWindows(Arrays.asList(window(0,60,10),window(0,60,-3)));
        assertEquals(7,p.adjustLevel(0,30));assertEquals(10,p.adjustLevel(8,30));
        assertThrows(IllegalArgumentException.class,()->p.windowAdjustmentAtMinute(-1));
        assertThrows(IllegalArgumentException.class,()->p.windowAdjustmentAtMinute(1440));
        assertThrows(IllegalArgumentException.class,()->p.adjustLevel(-1,30));assertThrows(IllegalArgumentException.class,()->p.adjustLevel(11,30));
    }

    @Test public void savedWindowCountIsIndependentOfControllerScheduleSize(){
        LogicProfile p=new LogicProfile();List<LogicProfile.TimeWindow> windows=new ArrayList<>();
        for(int i=0;i<2000;i++)windows.add(window(0,60,-10));
        p.replaceTimeWindows(windows);assertEquals(-20000,p.windowAdjustmentAtMinute(30));assertEquals(0,p.adjustLevel(10,30));
        LogicProfile saved=p.copy();assertEquals(2000,saved.timeWindows.size());assertTrue(p.sameSettings(saved));
        List<LogicProfile.TimeWindow> compiled=p.compiledTimeWindows();assertEquals(1,compiled.size());assertEquals(-10,compiled.get(0).adjustment);
        for(LogicProfile.TimeWindow w:p.timeWindows)w.adjustment=10;
        assertEquals(20000,p.windowAdjustmentAtMinute(30));assertEquals(10,p.adjustLevel(0,30));assertEquals(10,p.compiledTimeWindows().get(0).adjustment);
        assertEquals(-20000,saved.windowAdjustmentAtMinute(30));
        assertThrows(IllegalArgumentException.class,()->Protocol.logicWindow(p,0,1));
    }

    @Test public void compilerSumsBeforeClampingAndPreservesEveryMinuteAndFanLevel(){
        LogicProfile p=new LogicProfile();List<LogicProfile.TimeWindow> windows=new ArrayList<>();
        Random random=new Random(9014);
        for(int i=0;i<250;i++){
            int start=random.nextInt(1440),end=(start+1+random.nextInt(1439))%1440;
            LogicProfile.TimeWindow w=window(start,end,random.nextInt(21)-10);w.enabled=i%7!=0;windows.add(w);
        }
        p.replaceTimeWindows(windows);LogicProfile compiled=new LogicProfile();compiled.replaceTimeWindows(p.compiledTimeWindows());
        for(int minute=0;minute<1440;minute++){
            long raw=p.windowAdjustmentAtMinute(minute);assertEquals("minute "+minute,Math.max(-10,Math.min(10,raw)),compiled.windowAdjustmentAtMinute(minute));
            for(int normal=0;normal<=FanSetting.MAX;normal++)assertEquals("minute "+minute+" normal "+normal,p.adjustLevel(normal,minute),compiled.adjustLevel(normal,minute));
        }
        p.replaceTimeWindows(Arrays.asList(window(0,60,10),window(0,60,10),window(0,60,-10)));
        assertEquals(10,p.compiledTimeWindows().get(0).adjustment);
        p.replaceTimeWindows(Arrays.asList(window(0,60,-10),window(0,60,-10),window(0,60,10)));
        assertEquals(-10,p.compiledTimeWindows().get(0).adjustment);
    }

    @Test public void compilationHandlesNoAdjustmentFullDayAndMaximumMinuteChanges(){
        LogicProfile p=new LogicProfile();List<LogicProfile.TimeWindow> compiled=p.compiledTimeWindows();assertEquals(1,compiled.size());assertFalse(compiled.get(0).enabled);
        p.replaceTimeWindows(Arrays.asList(window(0,720,-1),window(720,0,-1)));compiled=p.compiledTimeWindows();
        assertEquals(2,compiled.size());assertEquals(0,compiled.get(0).startMinute);assertEquals(720,compiled.get(0).endMinute);
        assertEquals(720,compiled.get(1).startMinute);assertEquals(0,compiled.get(1).endMinute);assertEquals(-1,compiled.get(1).adjustment);
        LogicProfile fullDay=new LogicProfile();fullDay.replaceTimeWindows(compiled);for(int minute=0;minute<1440;minute++)assertEquals(-1,fullDay.windowAdjustmentAtMinute(minute));
        List<LogicProfile.TimeWindow> alternating=new ArrayList<>();
        for(int minute=0;minute<1440;minute++)alternating.add(window(minute,(minute+1)%1440,minute%2==0?-1:1));
        p.replaceTimeWindows(alternating);compiled=p.compiledTimeWindows();assertEquals(1440,compiled.size());
        p.replaceTimeWindows(compiled);ByteBuffer wire=buffer(Protocol.logicWindow(p,1439,7));
        assertEquals(1439,wire.getShort(12)&65535);assertEquals(1440,wire.getShort(14)&65535);assertEquals(0,wire.getShort(16));assertEquals(0,wire.getShort(18));
        for(int minute=0;minute<1440;minute++)assertEquals(minute%2==0?-1:1,p.windowAdjustmentAtMinute(minute));
    }

    @Test public void morningWindowIncludesStartAndExcludesEnd(){
        LogicProfile.TimeWindow w=enabled().timeWindow;
        assertFalse(w.activeAtMinute(269));assertTrue(w.activeAtMinute(270));
        assertTrue(w.activeAtMinute(479));assertFalse(w.activeAtMinute(480));
        assertEquals(5,w.adjustLevel(5,269));
    }

    @Test public void offsetIsAppliedOnceToNormalTargetAndStopsAtOffOrH(){
        LogicProfile p=enabled();LogicProfile.TimeWindow w=p.timeWindow;
        assertEquals(5,w.adjustLevel(5,269));assertEquals(4,w.adjustLevel(5,270));assertEquals(5,w.adjustLevel(5,480));
        for(int minute=270;minute<480;minute++)assertEquals(4,w.adjustLevel(5,minute));
        assertEquals(0,w.adjustLevel(0,300));assertEquals(0,w.adjustLevel(1,300));
        w.adjustment=-2;assertEquals(0,w.adjustLevel(1,300));assertEquals(0,w.adjustLevel(2,300));assertEquals(1,w.adjustLevel(3,300));
        w.adjustment=2;assertEquals(FanSetting.MAX,w.adjustLevel(9,300));assertEquals(FanSetting.MAX,w.adjustLevel(FanSetting.MAX,300));
        w.adjustment=0;assertEquals(5,w.adjustLevel(5,300));
        // The window does not alter the underlying temperature calculation.
        assertEquals(new LogicProfile().level(72,99),p.level(72,99));
    }

    @Test public void overnightWindowSpansMidnightAndDisabledWindowDoesNothing(){
        LogicProfile.TimeWindow w=enabled().timeWindow;w.startMinute=23*60;w.endMinute=6*60;
        assertFalse(w.activeAtMinute(22*60+59));assertTrue(w.activeAtMinute(23*60));
        assertTrue(w.activeAtMinute(1439));assertTrue(w.activeAtMinute(0));assertTrue(w.activeAtMinute(359));assertFalse(w.activeAtMinute(360));
        w.enabled=false;assertFalse(w.activeAtMinute(0));assertFalse(w.activeAtMinute(1400));assertEquals(5,w.adjustLevel(5,0));
    }

    @Test public void oldProfilesRemainDisabledAndNewSettingsRoundTrip(){
        LogicProfile old=LogicProfile.deserialize("version=2\nname=Existing\n");
        assertFalse(old.timeWindow.enabled);assertEquals(270,old.timeWindow.startMinute);assertEquals(480,old.timeWindow.endMinute);assertEquals(-1,old.timeWindow.adjustment);
        LogicProfile p=enabled();p.timeWindow.startMinute=1380;p.timeWindow.endMinute=120;p.timeWindow.adjustment=2;
        LogicProfile copy=p.copy();assertEquals(2,LogicProfile.VERSION);assertTrue(copy.timeWindow.enabled);
        assertEquals(1380,copy.timeWindow.startMinute);assertEquals(120,copy.timeWindow.endMinute);assertEquals(2,copy.timeWindow.adjustment);assertTrue(p.sameSettings(copy));
        copy.timeWindow.enabled=false;assertFalse(p.sameSettings(copy));copy=p.copy();copy.timeWindow.startMinute++;assertFalse(p.sameSettings(copy));
        copy=p.copy();copy.timeWindow.endMinute++;assertFalse(p.sameSettings(copy));copy=p.copy();copy.timeWindow.adjustment--;assertFalse(p.sameSettings(copy));
        assertEquals(2,p.timeWindow.adjustment);
    }

    @Test public void legacySingleWindowMigratesWithoutChangingItsSettings(){
        LogicProfile p=LogicProfile.deserialize("version=2\nid=night\nname=Night cooling\ntimeWindow.enabled=true\ntimeWindow.startMinute=1380\ntimeWindow.endMinute=120\ntimeWindow.adjustment=-3\n");
        assertEquals(1,p.timeWindows.size());assertSame(p.timeWindow,p.timeWindows.get(0));assertTrue(p.hasEnabledTimeWindows());
        assertEquals(-3,p.windowAdjustmentAtMinute(0));assertEquals(0,p.windowAdjustmentAtMinute(120));
        LogicProfile copy=p.copy();assertEquals("night",copy.id);assertEquals("Night cooling",copy.name);assertTrue(p.sameSettings(copy));
        assertTrue(p.serialize().contains("timeWindows.count=1"));assertEquals(2,LogicProfile.VERSION);
    }

    @Test public void everyNamedWindowRoundTripsAndCopiesRemainDetached(){
        LogicProfile p=new LogicProfile();p.id="sleep-profile";p.name="Sleep settings";
        LogicProfile.TimeWindow first=window(1380,120,-1),second=window(60,180,-3),third=window(300,360,2);third.enabled=false;
        p.replaceTimeWindows(Arrays.asList(first,second,third));assertSame(p.timeWindow,p.timeWindows.get(0));
        first.adjustment=8;second.endMinute=400;third.enabled=true;
        assertEquals(-1,p.timeWindow.adjustment);assertEquals(180,p.timeWindows.get(1).endMinute);assertFalse(p.timeWindows.get(2).enabled);
        LogicProfile copy=p.copy();assertEquals("sleep-profile",copy.id);assertEquals("Sleep settings",copy.name);assertEquals(3,copy.timeWindows.size());
        assertSame(copy.timeWindow,copy.timeWindows.get(0));assertNotSame(p.timeWindow,copy.timeWindow);assertNotSame(p.timeWindows.get(1),copy.timeWindows.get(1));
        assertTrue(p.sameSettings(copy));assertEquals(-4,copy.windowAdjustmentAtMinute(90));assertFalse(copy.timeWindows.get(2).enabled);assertEquals(2,copy.timeWindows.get(2).adjustment);
        copy.name="Different saved name";copy.id="different-id";assertTrue(p.sameSettings(copy));
        copy.timeWindows.get(1).adjustment=-2;assertFalse(p.sameSettings(copy));assertEquals(-3,p.timeWindows.get(1).adjustment);
        p.replaceTimeWindows(p.timeWindows);assertEquals(3,p.timeWindows.size());assertEquals(-4,p.windowAdjustmentAtMinute(90));assertSame(p.timeWindow,p.timeWindows.get(0));
        LogicProfile.TimeWindow detached=p.timeWindow.copy();detached.adjustment=7;assertEquals(-1,p.timeWindow.adjustment);
    }

    @Test public void invalidWindowCollectionsCannotReplaceOrLoadASavedProfile(){
        LogicProfile p=enabled();
        assertThrows(IllegalArgumentException.class,()->p.replaceTimeWindows(null));
        assertThrows(IllegalArgumentException.class,()->p.replaceTimeWindows(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,()->p.replaceTimeWindows(Arrays.asList(p.timeWindow,null)));
        assertThrows(IllegalArgumentException.class,()->p.replaceTimeWindows(Arrays.asList(window(0,60,-3),window(5,5,1))));
        assertEquals(1,p.timeWindows.size());assertEquals(-1,p.timeWindow.adjustment);
        for(String count:new String[]{"0","-1","2147483648","many"})assertThrows(count,IllegalArgumentException.class,()->LogicProfile.deserialize("version=2\ntimeWindows.count="+count+"\n"));
        assertThrows(IllegalArgumentException.class,()->LogicProfile.deserialize("version=2\ntimeWindows.count=2\ntimeWindows.0.enabled=true\ntimeWindows.0.startMinute=0\ntimeWindows.0.endMinute=60\ntimeWindows.0.adjustment=-1\n"));
        String saved=p.serialize();assertThrows(IllegalArgumentException.class,()->LogicProfile.deserialize(saved.replace("timeWindows.0.adjustment=-1","timeWindows.0.adjustment=-11")));
        p.timeWindows.clear();assertThrows(IllegalArgumentException.class,p::serialize);
    }

    @Test public void invalidWindowCannotBeSavedOrUploaded(){
        for(String field:new String[]{"startMinute=-1","startMinute=1440","endMinute=-1","endMinute=1440","adjustment=-11","adjustment=11","enabled=maybe"})
            assertThrows(field,IllegalArgumentException.class,()->LogicProfile.deserialize("version=2\ntimeWindow."+field+"\n"));
        LogicProfile p=enabled();p.timeWindow.endMinute=p.timeWindow.startMinute;
        assertThrows(IllegalArgumentException.class,p::serialize);assertThrows(IllegalArgumentException.class,()->Protocol.logicWindow(p,1));
        p.timeWindow.enabled=false;p.validate();assertEquals(5,p.timeWindow.adjustLevel(5,270));
        assertThrows(IllegalArgumentException.class,()->p.timeWindow.activeAtMinute(1440));
        assertThrows(IllegalArgumentException.class,()->p.timeWindow.adjustLevel(-1,0));
        assertThrows(IllegalArgumentException.class,()->p.timeWindow.adjustLevel(11,0));
    }

    @Test public void windowPacketCarriesSignedAdjustmentAndSchema(){
        LogicProfile p=enabled();ByteBuffer b=buffer(Protocol.logicWindow(p,4321));
        assertEquals(20,b.array().length);assertEquals(1,b.get(0));assertEquals(6,b.get(1));assertEquals(4321,b.getShort(2)&65535);
        assertEquals(18,b.get(4));assertEquals(1,b.get(5));assertEquals(270,b.getShort(6)&65535);assertEquals(480,b.getShort(8)&65535);
        assertEquals(-1,b.get(10));assertEquals(1,b.get(11));for(int i=12;i<20;i++)assertEquals(0,b.get(i));
        p.timeWindow.adjustment=-10;assertEquals(-10,Protocol.logicWindow(p,1)[10]);
        p.timeWindow.adjustment=10;assertEquals(10,Protocol.logicWindow(p,1)[10]);
        p.timeWindow.enabled=false;assertEquals(0,Protocol.logicWindow(p,1)[5]);
    }

    @Test public void indexedPacketsCarryEveryWindowAndCannotSilentlyTruncateToLegacy(){
        LogicProfile p=new LogicProfile();p.replaceTimeWindows(Arrays.asList(window(270,480,-1),window(360,540,-3)));
        for(int index=0;index<2;index++){
            ByteBuffer b=buffer(Protocol.logicWindow(p,index,4321+index));LogicProfile.TimeWindow w=p.timeWindows.get(index);
            assertEquals(20,b.array().length);assertEquals(1,b.get(0));assertEquals(6,b.get(1));assertEquals(4321+index,b.getShort(2)&65535);
            assertEquals(18,b.get(4));assertEquals(1,b.get(5));assertEquals(w.startMinute,b.getShort(6)&65535);assertEquals(w.endMinute,b.getShort(8)&65535);
            assertEquals(w.adjustment,b.get(10));assertEquals(2,b.get(11));assertEquals(index,b.getShort(12)&65535);assertEquals(2,b.getShort(14)&65535);
            for(int i=16;i<20;i++)assertEquals(0,b.get(i));
        }
        assertThrows(IllegalArgumentException.class,()->Protocol.logicWindow(p,-1,1));assertThrows(IllegalArgumentException.class,()->Protocol.logicWindow(p,2,1));
        assertThrows(IllegalArgumentException.class,()->Protocol.logicWindow(p,1));
        p.timeWindow.enabled=false;assertThrows(IllegalArgumentException.class,()->Protocol.logicWindow(p,1));
        p.timeWindows.get(1).enabled=false;byte[] legacy=Protocol.logicWindow(p,1);assertEquals(0,legacy[5]);assertEquals(1,legacy[11]);
        assertEquals(0,Protocol.logicWindow(p,1,1)[5]);
    }

    @Test public void clockPacketIncludesChicagoFallTransitionAndPostTransitionOffset(){
        long now=epoch("2026-10-11T12:00:00Z");ByteBuffer b=buffer(Protocol.logicClock(now,ZoneId.of("America/Chicago"),65535));
        assertEquals(20,b.array().length);assertEquals(1,b.get(0));assertEquals(6,b.get(1));assertEquals(65535,b.getShort(2)&65535);
        assertEquals(19,b.get(4));assertEquals(1,b.get(5));assertEquals(now,Integer.toUnsignedLong(b.getInt(6)));assertEquals(-300,b.getShort(10));
        assertEquals(epoch("2026-11-01T07:00:00Z"),Integer.toUnsignedLong(b.getInt(12)));assertEquals(-360,b.getShort(16));assertEquals(0,b.getShort(18));
        b=buffer(Protocol.logicClock(epoch("2026-11-01T07:00:00Z"),ZoneId.of("America/Chicago"),1));
        assertEquals(-360,b.getShort(10));assertEquals(epoch("2027-03-14T08:00:00Z"),Integer.toUnsignedLong(b.getInt(12)));assertEquals(-300,b.getShort(16));
    }

    @Test public void fixedZoneClockHasNoTransitionAndSupportsUnsignedEpoch(){
        ByteBuffer b=buffer(Protocol.logicClock(0xfffffffeL,ZoneOffset.ofHoursMinutes(5,30),1));
        assertEquals(0xfffffffeL,Integer.toUnsignedLong(b.getInt(6)));assertEquals(330,b.getShort(10));assertEquals(0,b.getInt(12));assertEquals(330,b.getShort(16));
        assertThrows(IllegalArgumentException.class,()->Protocol.logicClock(0,ZoneOffset.UTC,1));
        assertThrows(IllegalArgumentException.class,()->Protocol.logicClock(0x100000000L,ZoneOffset.UTC,1));
        assertThrows(IllegalArgumentException.class,()->Protocol.logicClock(100,null,1));
        assertThrows(IllegalArgumentException.class,()->Protocol.logicClock(100,ZoneOffset.ofTotalSeconds(1),1));
    }

    @Test public void windowRequiresAdvertisedCapability(){
        assertFalse(Protocol.logicWindowFirmware(null));assertFalse(Protocol.logicWindowFirmware(new byte[0]));
        assertFalse(Protocol.logicMultiWindowFirmware(null));assertFalse(Protocol.logicMultiWindowFirmware(new byte[0]));
        byte[] caps=new byte[20];caps[0]=4;caps[4]=7;
        assertFalse(Protocol.logicWindowFirmware(caps));assertFalse(Protocol.logicMultiWindowFirmware(caps));
        caps[15]=1;assertTrue(Protocol.logicWindowFirmware(caps));assertFalse(Protocol.logicMultiWindowFirmware(caps));
        caps[15]=2;assertTrue(Protocol.logicWindowFirmware(caps));assertTrue(Protocol.logicMultiWindowFirmware(caps));
        caps[15]=3;assertTrue(Protocol.logicWindowFirmware(caps));assertTrue(Protocol.logicMultiWindowFirmware(caps));
        caps[15]=2;caps[4]=6;assertFalse(Protocol.logicWindowFirmware(caps));assertFalse(Protocol.logicMultiWindowFirmware(caps));
        caps[4]=7;caps[0]=5;assertFalse(Protocol.logicWindowFirmware(caps));assertFalse(Protocol.logicMultiWindowFirmware(caps));
    }

    @Test public void liveStateDecodesActiveSignedAdjustmentAndRejectsInvalidRange(){
        byte[] packet=new byte[20];packet[0]=9;packet[1]=1;packet[13]=7;packet[19]=1;
        assertEquals(0,Protocol.decodeLogic(packet,1).windowAdjustment);
        for(int adjustment:new int[]{-10,-4,-2,-1,0,1,2,4,10}){
            packet[18]=(byte)adjustment;assertEquals(adjustment,Protocol.decodeLogic(packet,1).windowAdjustment);
        }
        for(int adjustment:new int[]{-128,-11,11,127}){
            packet[18]=(byte)adjustment;assertThrows(IllegalArgumentException.class,()->Protocol.decodeLogic(packet,1));
        }
    }
}
