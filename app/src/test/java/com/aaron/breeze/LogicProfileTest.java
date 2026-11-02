package com.aaron.breeze;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class LogicProfileTest {
    private static final double[] ROOMS={68,70,72,74},BODIES={94,97,99};
    private static void table(LogicProfile p,int[][] expected){for(int room=0;room<4;room++)for(int body=0;body<3;body++)assertEquals("room "+ROOMS[room]+" body "+BODIES[body],expected[room][body],p.level(ROOMS[room],BODIES[body]));}
    @Test public void defaultsAreTheChosenRangeTable(){
        LogicProfile p=new LogicProfile();assertEquals("Default",p.name);assertEquals(30,p.averageSeconds);assertEquals(10,p.riseSeconds);assertEquals(5,p.fallSeconds);assertFalse(p.boost.enabled);assertEquals(1,p.boost.triggerRiseF,0);
        assertEquals(11.0/6,p.roomWeight(),1e-9);assertEquals(0,p.score(68,98),1e-9);assertEquals(10,p.score(74,97),1e-9);
        table(p,new int[][]{{0,0,1},{0,3,5},{4,7,9},{7,10,10}});
        assertEquals(0,p.level(68,97.99));assertEquals(0,p.level(68,98));assertEquals(1,p.level(68,98.01));assertEquals(9,p.level(74,96));assertEquals(10,p.level(74,96.01));
        assertEquals(4,p.level(71,96));assertEquals(10,p.level(73,99));
        // The reported incident: level 4 held at room 72.6 with probes at 74.98. Cooled probes are now Off.
        assertEquals(0,p.level(72.6,74.98));assertEquals(0,p.level(79,75));
    }
    @Test public void bodyWeightReshapesTheRangeBetweenTheSameTwoPoints(){
        LogicProfile p=new LogicProfile();p.bodyWeight=0.5;p.validate();assertEquals(1.75,p.roomWeight(),1e-9);assertEquals(0,p.score(68,98),1e-9);assertEquals(10,p.score(74,97),1e-9);
        table(p,new int[][]{{0,0,1},{2,3,4},{5,7,8},{9,10,10}});
        p.bodyWeight=0;p.validate();assertEquals(5,p.level(71,60));assertEquals(5,p.level(71,110));
    }
    @Test public void renameKeepsIdentityAndEverySettingRoundTrips(){
        LogicProfile p=new LogicProfile();p.name="Night cooling";p.offRoomF=67.5;p.offBodyF=97.25;p.fullRoomF=75;p.fullBodyF=96.5;p.bodyWeight=1.25;p.steadiness=0.2;p.averageSeconds=45;p.riseSeconds=12;p.fallSeconds=9;
        p.boost.enabled=true;p.boost.amount=3;p.boost.triggerRiseF=1.5;p.boost.windowSeconds=40;p.boost.riseSeconds=4;p.boost.holdSeconds=90;p.boost.returnSeconds=7;p.boost.curve=0;
        LogicProfile copy=LogicProfile.deserialize(p.serialize());assertEquals("default",copy.id);assertEquals("Night cooling",copy.name);
        assertEquals(67.5,copy.offRoomF,0);assertEquals(97.25,copy.offBodyF,0);assertEquals(75,copy.fullRoomF,0);assertEquals(96.5,copy.fullBodyF,0);assertEquals(1.25,copy.bodyWeight,0);assertEquals(0.2,copy.steadiness,0);
        assertEquals(45,copy.averageSeconds);assertEquals(12,copy.riseSeconds);assertEquals(9,copy.fallSeconds);
        assertTrue(copy.boost.enabled);assertEquals(3,copy.boost.amount);assertEquals(1.5,copy.boost.triggerRiseF,0);assertEquals(40,copy.boost.windowSeconds);assertEquals(4,copy.boost.riseSeconds);assertEquals(90,copy.boost.holdSeconds);assertEquals(7,copy.boost.returnSeconds);assertEquals(0,copy.boost.curve);
        assertTrue(p.sameSettings(copy));copy.name="Another name";assertTrue(p.sameSettings(copy));copy.steadiness=0.1;assertFalse(p.sameSettings(copy));
    }
    @Test public void wireFieldsMatchControllerContract(){
        LogicProfile p=new LogicProfile();ByteBuffer g=ByteBuffer.wrap(Protocol.logicGlobal(p,42)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(20,g.array().length);assertEquals(6,g.get(1));assertEquals(42,g.getShort(2));assertEquals(13,g.get(4));assertEquals(0,g.get(5));assertEquals(30,g.getShort(6));assertEquals(10,g.getShort(8));assertEquals(5,g.getShort(10));assertEquals(30,g.getShort(12));assertEquals(3,g.get(15));
        ByteBuffer r=ByteBuffer.wrap(Protocol.logicRange(p,43)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(14,r.get(4));assertEquals(0,r.get(5));assertEquals(6800,r.getShort(6));assertEquals(9800,r.getShort(8));assertEquals(7400,r.getShort(10));assertEquals(9700,r.getShort(12));assertEquals(100,r.getShort(14));assertEquals(3,r.get(16));
        p.boost.enabled=true;ByteBuffer b=ByteBuffer.wrap(Protocol.logicBoost(p,44)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(15,b.get(4));assertEquals(0,b.get(5));assertEquals(1,b.get(6));assertEquals(2,b.get(7));assertEquals(100,b.getShort(8));assertEquals(30,b.getShort(10));assertEquals(3,b.getShort(12));assertEquals(120,b.getShort(14));assertEquals(5,b.getShort(16));assertEquals(1,b.get(18));assertEquals(3,b.get(19));
        assertEquals(16,Protocol.logicCommit(45)[4]);assertEquals(0,Protocol.fan(Protocol.SYNC_SPEED,0,1,46)[5]);
        p.offRoomF=-10.25;assertEquals(-1025,ByteBuffer.wrap(Protocol.logicRange(p,47)).order(ByteOrder.LITTLE_ENDIAN).getShort(6));
    }
    private static void rejected(java.util.function.Consumer<LogicProfile> change){LogicProfile p=new LogicProfile();change.accept(p);assertThrows(IllegalArgumentException.class,p::serialize);}
    @Test public void invalidSettingsCannotBeSerialized(){
        rejected(p->p.name=" ");rejected(p->p.fullRoomF=68);rejected(p->p.fullRoomF=68.5);rejected(p->p.offRoomF=75);rejected(p->p.offBodyF=97.123);
        rejected(p->p.bodyWeight=-0.1);rejected(p->p.bodyWeight=10.5);rejected(p->p.steadiness=1.5);rejected(p->p.averageSeconds=0);rejected(p->p.riseSeconds=121);rejected(p->p.fallSeconds=0);
        rejected(p->p.boost.amount=10);rejected(p->p.boost.triggerRiseF=0);rejected(p->p.boost.curve=2);
        // A warmer body at the Full point is fine until the body alone would cover the whole range.
        LogicProfile warmer=new LogicProfile();warmer.fullBodyF=103;warmer.validate();assertEquals(5.0/6,warmer.roomWeight(),1e-9);
        rejected(p->p.fullBodyF=108);rejected(p->{p.fullBodyF=103;p.bodyWeight=2;});
    }
    @Test public void pacingReportsTheOneSecondMinimum(){
        assertEquals(1112,LogicProfile.stepMillis(10));assertEquals(1000,LogicProfile.stepMillis(5));assertEquals(1000,LogicProfile.stepMillis(1));assertEquals(1000,LogicProfile.stepMillis(9));assertEquals(2223,LogicProfile.stepMillis(20));assertEquals(13334,LogicProfile.stepMillis(120));
    }
    @Test public void automaticStateDecodesRangePositionAndRejectsEleven(){
        byte[] b=new byte[20];b[0]=9;b[1]=1|64;b[3]=10;b[13]=7;b[19]=1;ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(16,(short)-1460);
        Protocol.LogicState state=Protocol.decodeLogic(b,1);assertEquals(10,state.target);assertEquals(-14.6,state.score,1e-9);assertTrue(state.automatic());
        b[1]=1;assertTrue(Double.isNaN(Protocol.decodeLogic(b,1).score));b[2]=(byte)255;assertEquals(255,Protocol.decodeLogic(b,1).rule);
        b[3]=11;assertThrows(IllegalArgumentException.class,()->Protocol.decodeLogic(b,1));
    }
    @Test public void malformedSavedProfileCannotSilentlyApply(){
        assertThrows(IllegalArgumentException.class,()->LogicProfile.deserialize("version=2\noffRoomF=NaN\n"));assertThrows(IllegalArgumentException.class,()->LogicProfile.deserialize("version=2\nboost.enabled=maybe\n"));
        // A threshold-rule profile is never read as a range.
        assertThrows(IllegalArgumentException.class,()->LogicProfile.deserialize("version=1\nroomBoundaryF=73\n"));
    }
    @Test public void thresholdProfileCarriesOnlyItsTimingIntoTheDefaultRange(){
        String earlier="version=1\nid=default\nname=Summer\nroomBoundaryF=73.0\ncoolRoomThresholdF=72.0\naverageSeconds=45\nriseSeconds=12\nfallSeconds=7\nmaxSpeed=9\nprobeMask=3\nwarm.thresholdF=96.0\ncool.thresholdF=97.5\ncool.offBelow=true\nwarm.boost.enabled=true\n";
        LogicProfile carried=LogicProfile.fromThresholdProfile(earlier);assertEquals(45,carried.averageSeconds);assertEquals(12,carried.riseSeconds);assertEquals(7,carried.fallSeconds);
        LogicProfile fresh=new LogicProfile();assertEquals("Default",carried.name);assertFalse(carried.boost.enabled);carried.averageSeconds=fresh.averageSeconds;carried.riseSeconds=fresh.riseSeconds;carried.fallSeconds=fresh.fallSeconds;assertTrue(fresh.sameSettings(carried));
        assertTrue(fresh.sameSettings(LogicProfile.fromThresholdProfile(null)));assertTrue(fresh.sameSettings(LogicProfile.fromThresholdProfile("averageSeconds=many")));assertTrue(fresh.sameSettings(LogicProfile.fromThresholdProfile("version=1\naverageSeconds=999\n")));
    }
}
