package com.aaron.breeze;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
public class ProtocolTest {
    @Test public void onlySensorFirmwareEnablesRecording(){byte[] b=new byte[20];b[0]=4;for(int version=0;version<10;version++){b[4]=(byte)version;assertEquals(version>=6,Protocol.sensorFirmware(b));}assertFalse(Protocol.sensorFirmware(new byte[0]));}
    @Test public void legacySevenCapabilityKeepsIrAndAddsOptionalOtaFields(){byte[] b=new byte[20];b[0]=4;b[4]=7;Protocol.Capability legacy=Protocol.decodeCapability(b);assertTrue(legacy.irSupported());assertFalse(legacy.otaSupported);assertFalse(legacy.confirmationPending);assertEquals("",legacy.firmwareVersion);b[5]=1;b[6]=2;b[7]=3;b[8]=1;b[9]=1;Protocol.Capability ota=Protocol.decodeCapability(b);assertTrue(ota.irSupported());assertTrue(ota.otaSupported);assertTrue(ota.confirmationPending);assertEquals("1.2.3",ota.firmwareVersion);}
    @Test public void threeReadingsDecodeIndependently(){ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);b.put(0,(byte)1);b.put(1,(byte)56);b.putShort(4,(short)3130);b.putShort(6,(short)2310);b.putShort(8,(short)5825);Protocol.Reading r=Protocol.decode(b.array(),42);assertEquals(31.3,r.probe,0);assertEquals(23.1,r.room,0);assertEquals(58.25,r.humidity,0);b.put(1,(byte)48);r=Protocol.decode(b.array(),42);assertTrue(Double.isNaN(r.probe));assertEquals(23.1,r.room,0);assertEquals(58.25,r.humidity,0);}
    @Test public void recordingCommandsRetainWireCompatibility(){assertEquals(7,Protocol.control(Protocol.START_RECORDING,0,1)[4]);assertEquals(8,Protocol.control(Protocol.STOP_RECORDING,0,2)[4]);}
    @Test public void missingSensorIsNotZero(){byte[] b=new byte[20];b[0]=1;b[11]=60;Protocol.Reading r=Protocol.decode(b,123);assertTrue(Double.isNaN(r.probe));assertTrue(Double.isNaN(r.room));assertEquals(123,r.time);}
    @Test public void signedProbeAndLargeUptime(){ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);b.put(0,(byte)1);b.put(1,(byte)8);b.putShort(4,(short)-500);b.putInt(12,-1);Protocol.Reading r=Protocol.decode(b.array(),10);assertEquals(-5,r.probe,0);assertEquals(4294967295L,r.uptime);}
    @Test public void recordCorruptionRejected(){byte[] b=new byte[20];b[0]=2;b[19]=(byte)Protocol.crc8(b,19);Protocol.validateRecord(b);b[10]^=1;try{Protocol.validateRecord(b);fail();}catch(IllegalArgumentException expected){}}
    @Test public void retirementCursorLittleEndian(){byte[] b=Protocol.retire(0x12345678,1);assertEquals(5,b[1]);assertEquals(0x78,b[4]&255);assertEquals(0x12,b[7]&255);}
}
