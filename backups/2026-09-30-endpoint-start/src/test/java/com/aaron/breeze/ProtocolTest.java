package com.aaron.breeze;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
public class ProtocolTest {
    @Test public void motorTelemetryDistinguishesReleasedFromKnownPosition(){byte[] b=new byte[20];b[0]=4;b[4]=2;Protocol.MotorReading r=Protocol.decodeMotor(b);assertFalse(r.canStep());b[1]=3;ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(2,(short)1502);r=Protocol.decodeMotor(b);assertTrue(r.canStep());assertEquals(1502,r.pulse);b[1]=1;assertFalse(Protocol.decodeMotor(b).canStep());}
    @Test public void motorTelemetryAcceptsExtendedEndpointsAndCenter(){byte[] b=new byte[20];b[0]=4;b[1]=3;b[4]=2;int[] pulses={500,1500,2400};double[] angles={0,90,180};for(int i=0;i<pulses.length;i++){ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(2,(short)pulses[i]);Protocol.MotorReading r=Protocol.decodeMotor(b);assertTrue(r.canStep());assertEquals(angles[i],r.approximateAngle(),0.00001);}}
    @Test public void motorTelemetryRejectsOutOfRangePulses(){byte[] b=new byte[20];b[0]=4;b[1]=3;b[4]=2;for(int pulse:new int[]{499,2401}){ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(2,(short)pulse);try{Protocol.decodeMotor(b);fail("invalid pulse accepted");}catch(IllegalArgumentException expected){}}}
    @Test(expected=IllegalArgumentException.class) public void legacyMotorFirmwareRejected(){byte[] b=new byte[20];b[0]=4;b[4]=1;Protocol.decodeMotor(b);}
    @Test public void motorStepsHaveDirectionAndMicrosecondAmount(){byte[] plus=Protocol.motorStep(true,2,77),minus=Protocol.motorStep(false,200,78);assertEquals(10,plus[4]);assertEquals(2,plus[5]);assertEquals(11,minus[4]);assertEquals(200,minus[5]&255);}
    @Test(expected=IllegalArgumentException.class) public void oversizedMotorStepRejected(){Protocol.motorStep(true,201,1);}
    @Test(expected=IllegalArgumentException.class) public void undersizedMotorStepRejected(){Protocol.motorStep(false,1,1);}
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
