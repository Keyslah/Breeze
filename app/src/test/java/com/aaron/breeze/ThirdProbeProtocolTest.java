package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThirdProbeProtocolTest {
    @Test public void advertisedSupportSeparatesD2FromLegacyMotorFields(){
        byte[] capability=new byte[20];capability[0]=4;capability[4]=7;
        assertFalse(Protocol.thirdProbeFirmware(capability));capability[8]=16;assertTrue(Protocol.thirdProbeFirmware(capability));
        capability[4]=5;assertFalse(Protocol.thirdProbeFirmware(capability));
        ByteBuffer old=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        old.put(0,(byte)1).put(1,(byte)(2|8)).putShort(4,(short)3100).put(10,(byte)3).put(11,(byte)90).putShort(16,(short)123);
        Protocol.Reading reading=Protocol.decode(old.array(),1,false);
        assertEquals(31,reading.probe,0);assertEquals(123,reading.ack);assertTrue(Double.isNaN(reading.thirdProbe));
        assertTrue(Double.isNaN(Protocol.decode(old.array(),1).thirdProbe));
    }
    @Test public void liveD2DecodesIndependentlyAndOldFirmwareStaysMissing(){
        ByteBuffer packet=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0,(byte)1).put(1,(byte)2).putShort(10,(short)3725);
        Protocol.Reading reading=Protocol.decode(packet.array(),123,true);
        assertEquals(37.25,reading.thirdProbe,0);assertTrue(Double.isNaN(reading.probe));assertTrue(Double.isNaN(reading.room));
        packet.put(1,(byte)0);
        assertTrue(Double.isNaN(Protocol.decode(packet.array(),123,true).thirdProbe));
        assertTrue(Double.isNaN(new Protocol.Reading().thirdProbe));
    }
    @Test public void liveD2AcceptsSignedLimitsAndRejectsInvalidMeasurements(){
        ByteBuffer packet=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0,(byte)1).put(1,(byte)2);
        for(int value:new int[]{-2000,-500,0,8000}){packet.putShort(10,(short)value);assertEquals(value/100.0,Protocol.decode(packet.array(),1,true).thirdProbe,0);}
        for(int value:new int[]{-2001,8001}){packet.putShort(10,(short)value);try{Protocol.decode(packet.array(),1,true);fail();}catch(IllegalArgumentException expected){}}
    }
    @Test public void companionKeepsUnsignedRecordIdentityAndMissingReading(){
        Protocol.validateRecord(companion(1,0,8,3712));
        Protocol.validateRecord(companion(0xffffffffL,0xfffffffeL,8,-500));
        Protocol.validateRecord(companion(1,0,0,0));
    }
    @Test public void companionRejectsCorruptionInvalidIdentityFlagsAndTemperature(){
        for(byte[] record:new byte[][]{companion(0,0xffffffffL,8,3000),companion(2,0,8,3000),companion(1,0,1,3000),companion(1,0,8,-2001),companion(1,0,8,8001)})reject(record);
        for(int offset:new int[]{16,17,18}){byte[] record=companion(1,0,8,3000);record[offset]=1;record[19]=(byte)Protocol.crc8(record,19);reject(record);}
        byte[] record=companion(1,0,8,3000);record[10]^=1;reject(record);
    }
    private static byte[] companion(long index,long parent,int flags,int temperature){
        ByteBuffer record=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        record.put(0,(byte)10).put(1,(byte)flags).putInt(2,(int)index).putInt(6,1791527526).putShort(10,(short)temperature).putInt(12,(int)parent);
        byte[] bytes=record.array();bytes[19]=(byte)Protocol.crc8(bytes,19);return bytes;
    }
    private static void reject(byte[] record){try{Protocol.validateRecord(record);fail("Accepted damaged D2 record");}catch(IllegalArgumentException expected){}}
}
