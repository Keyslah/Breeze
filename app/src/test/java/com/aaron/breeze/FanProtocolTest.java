package com.aaron.breeze;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
public class FanProtocolTest {
    private byte[] state(){byte[] b=new byte[20];b[0]=5;b[6]=(byte)255;b[14]=(byte)255;b[15]=(byte)255;b[19]=7;return b;}
    @Test public void unknownSpeedAndMissingSecondProbeStayUnknown(){Protocol.FanState f=Protocol.decodeFan(state(),123);assertFalse(f.synced());assertTrue(Double.isNaN(f.secondProbe));assertTrue(Double.isNaN(f.warmerBody));assertEquals(123,f.time);}
    @Test public void decodingPreservesRawSecondProbeAndUserMarker(){byte[] b=state();b[1]=5;b[4]=7;b[8]=3;b[11]=2;b[18]=3;ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);p.putShort(2,(short)2350).putShort(12,(short)2350);Protocol.FanState f=Protocol.decodeFan(b,1);assertEquals(23.5,f.secondProbe,0);assertEquals(3,f.phase);assertEquals(7,f.speed);assertTrue(f.synced());assertTrue(f.has(0));assertFalse(f.has(2));}
    @Test public void fanCommandsUseExistingTwentyByteEnvelope(){byte[] b=Protocol.fan(Protocol.LEARN,5,38,513);assertEquals(20,b.length);assertEquals(1,b[0]);assertEquals(6,b[1]);assertEquals(513,ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(2));assertEquals(1,b[4]);assertEquals(5,b[5]);assertEquals(38,b[6]);}
    @Test public void boundsAndSyncConfirmationValidated(){for(int[] v:new int[][]{{Protocol.TARGET_SPEED,0,0},{Protocol.TARGET_SPEED,10,0},{Protocol.LEARN,12,38},{Protocol.LEARN,0,29},{Protocol.SYNC_SPEED,5,0},{Protocol.MARK_TEST,4,0}}){try{Protocol.fan(v[0],v[1],v[2],1);fail();}catch(IllegalArgumentException expected){}}}
    @Test public void typeSixRecordsCarrySecondProbeWithoutLegacySpeedBounds(){byte[] b=new byte[20];b[0]=6;b[1]=2;ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(16,(short)3130);b[18]=(byte)192;b[19]=(byte)Protocol.crc8(b,19);Protocol.validateRecord(b);b[16]^=1;try{Protocol.validateRecord(b);fail();}catch(IllegalArgumentException expected){}}
    @Test public void malformedStateRejected(){for(int index:new int[]{4,5,6,7,11,14,15,18,19}){byte[] b=state();b[index]=99;try{Protocol.decodeFan(b,0);fail("accepted field "+index);}catch(IllegalArgumentException expected){}}}
    @Test public void noAutomaticRulesReportedExplicitly(){assertTrue(Protocol.error(30).contains("not defined"));assertEquals("Command not learned",Protocol.error(20));}
}
