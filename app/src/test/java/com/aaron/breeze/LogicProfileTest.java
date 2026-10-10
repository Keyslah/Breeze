package com.aaron.breeze;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class LogicProfileTest {
    @Test public void defaultAndRenameKeepIdentityAndIndependentBoosts(){
        LogicProfile p=new LogicProfile();assertEquals("default",p.name);assertEquals(71,p.roomBoundaryF,0);assertEquals(60,p.averageSeconds);assertEquals(3,p.warm.minimumSpeed);assertEquals(1,p.cool.minimumSpeed);assertFalse(p.warm.offBelow);assertTrue(p.cool.offBelow);
        p.name="Night cooling";p.warm.boost.enabled=true;p.warm.boost.amount=3;p.cool.boost.enabled=false;p.riseSeconds=12;
        LogicProfile copy=LogicProfile.deserialize(p.serialize());assertEquals("default",copy.id);assertEquals("Night cooling",copy.name);assertTrue(copy.warm.boost.enabled);assertEquals(3,copy.warm.boost.amount);assertFalse(copy.cool.boost.enabled);assertEquals(12,copy.riseSeconds);
    }
    @Test public void wireFieldsMatchControllerContract(){
        LogicProfile p=new LogicProfile();ByteBuffer g=ByteBuffer.wrap(Protocol.logicGlobal(p,42)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(20,g.array().length);assertEquals(13,g.get(4));assertEquals(7,g.get(5));assertEquals(7100,g.getShort(6));assertEquals(60,g.getShort(8));assertEquals(10,g.getShort(10));assertEquals(5,g.getShort(12));assertEquals(9,g.get(14));assertEquals(2,g.get(15));assertEquals(7100,g.getShort(16));
        ByteBuffer r=ByteBuffer.wrap(Protocol.logicRule(p,1,43)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(14,r.get(4));assertEquals(1,r.get(5));assertEquals(9800,r.getShort(6));assertEquals(40,r.getShort(8));assertEquals(1,r.get(10));assertEquals(1,r.get(11));assertEquals(1,r.get(12));
        p.warm.boost.enabled=true;ByteBuffer b=ByteBuffer.wrap(Protocol.logicBoost(p,0,44)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(15,b.get(4));assertEquals(1,b.get(6));assertEquals(2,b.get(7));assertEquals(40,b.getShort(8));assertEquals(30,b.getShort(10));assertEquals(3,b.getShort(12));assertEquals(120,b.getShort(14));assertEquals(5,b.getShort(16));assertEquals(1,b.get(18));assertEquals(1,b.get(19));
        assertEquals(16,Protocol.logicCommit(45)[4]);assertEquals(0,Protocol.fan(Protocol.SYNC_SPEED,0,1,46)[5]);
    }
    @Test public void invalidSettingsCannotBeSerialized(){
        LogicProfile p=new LogicProfile();p.cool.stepF=0;assertThrows(IllegalArgumentException.class,p::serialize);p.cool.stepF=0.4;p.name=" ";assertThrows(IllegalArgumentException.class,p::serialize);p.name="default";p.probeMask=0;assertThrows(IllegalArgumentException.class,p::serialize);p.probeMask=3;p.maxSpeed=2;assertThrows(IllegalArgumentException.class,p::serialize);
    }
    @Test public void highestSettingAndNineLevelBoostPersistAndEncode(){
        LogicProfile p=new LogicProfile();p.maxSpeed=10;p.warm.minimumSpeed=10;p.cool.minimumSpeed=10;p.warm.boost.amount=9;
        LogicProfile saved=LogicProfile.deserialize(p.serialize());assertEquals(10,saved.maxSpeed);assertEquals(10,saved.warm.minimumSpeed);assertEquals(10,saved.cool.minimumSpeed);assertEquals(9,saved.warm.boost.amount);
        assertEquals(10,Protocol.logicGlobal(saved,1)[14]);assertEquals(10,Protocol.logicRule(saved,0,2)[10]);assertEquals(9,Protocol.logicBoost(saved,0,3)[7]);
        p.maxSpeed=11;assertThrows(IllegalArgumentException.class,p::validate);p.maxSpeed=10;p.warm.minimumSpeed=11;assertThrows(IllegalArgumentException.class,p::validate);p.warm.minimumSpeed=10;p.warm.boost.amount=10;assertThrows(IllegalArgumentException.class,p::validate);
    }
    @Test public void automaticStateAcceptsHighestButRejectsEleven(){
        byte[] b=new byte[20];b[0]=9;b[3]=10;b[13]=7;b[19]=1;assertEquals(10,Protocol.decodeLogic(b,1).target);b[3]=11;assertThrows(IllegalArgumentException.class,()->Protocol.decodeLogic(b,1));
    }
    @Test public void malformedSavedProfileCannotSilentlyApply(){assertThrows(IllegalArgumentException.class,()->LogicProfile.deserialize("version=1\nroomBoundaryF=NaN\n"));assertThrows(IllegalArgumentException.class,()->LogicProfile.deserialize("version=1\ncool.boost.enabled=maybe\n"));}
    @Test public void independentRoomThresholdsPersistAndEncode(){LogicProfile p=new LogicProfile();p.roomBoundaryF=72;p.coolRoomThresholdF=70;LogicProfile saved=LogicProfile.deserialize(p.serialize());assertEquals(72,saved.roomBoundaryF,0);assertEquals(70,saved.coolRoomThresholdF,0);ByteBuffer g=ByteBuffer.wrap(Protocol.logicGlobal(saved,9)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(7200,g.getShort(6));assertEquals(7000,g.getShort(16));p.coolRoomThresholdF=73;assertThrows(IllegalArgumentException.class,p::validate);}
}
