package com.aaron.breeze;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
public class ProtocolTest {
    @Test public void physicalDialOrderIsOffHighMediumLow(){Protocol.Config c=new Protocol.Config();byte[] b=Protocol.config(c,42);assertEquals(60,b[10]&255);assertEquals(105,b[11]&255);assertEquals(90,b[12]&255);assertEquals(75,b[13]&255);assertEquals(0,b[19]);assertEquals(20,b.length);}
    @Test public void reversedMountingWorks(){Protocol.Config c=new Protocol.Config();c.offAngle=120;c.highAngle=105;c.mediumAngle=90;c.lowAngle=75;c.validate();}
    @Test(expected=IllegalArgumentException.class) public void wrongDialOrderRejected(){Protocol.Config c=new Protocol.Config();c.offAngle=60;c.lowAngle=75;c.mediumAngle=90;c.highAngle=105;c.validate();}
    @Test(expected=IllegalArgumentException.class) public void crossedThresholdsRejected(){Protocol.Config c=new Protocol.Config();c.low=35;c.validate();}
    @Test(expected=IllegalArgumentException.class) public void nanThresholdRejected(){Protocol.Config c=new Protocol.Config();c.low=Double.NaN;c.validate();}
    @Test public void missingSensorIsNotZero(){byte[] b=new byte[20];b[0]=1;b[11]=60;Protocol.Reading r=Protocol.decode(b,123);assertTrue(Double.isNaN(r.probe));assertTrue(Double.isNaN(r.room));assertEquals(123,r.time);}
    @Test public void signedProbeAndLargeUptime(){ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);b.put(0,(byte)1);b.put(1,(byte)8);b.putShort(4,(short)-500);b.putInt(12,-1);Protocol.Reading r=Protocol.decode(b.array(),10);assertEquals(-5,r.probe,0);assertEquals(4294967295L,r.uptime);}
    @Test public void recordCorruptionRejected(){byte[] b=new byte[20];b[0]=2;b[19]=(byte)Protocol.crc8(b,19);Protocol.validateRecord(b);b[10]^=1;try{Protocol.validateRecord(b);fail();}catch(IllegalArgumentException expected){}}
    @Test public void retirementCursorLittleEndian(){byte[] b=Protocol.retire(0x12345678,1);assertEquals(5,b[1]);assertEquals(0x78,b[4]&255);assertEquals(0x12,b[7]&255);}
}
