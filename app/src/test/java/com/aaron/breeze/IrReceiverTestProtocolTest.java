package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class IrReceiverTestProtocolTest {
    private byte[] result(){byte[] bytes=new byte[20];bytes[0]=7;bytes[13]=1;bytes[14]=38;bytes[15]=17;bytes[16]=16;bytes[19]=1;return bytes;}
    @Test public void testCommandIsIndependentOfLearnedSlots(){byte[] bytes=Protocol.fan(Protocol.IR_RECEIVER_TEST,0,0,513);assertEquals(20,bytes.length);assertEquals(6,bytes[1]);assertEquals(12,bytes[4]);assertEquals(0,bytes[5]);assertEquals(0,ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(6));assertEquals(513,ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(2));}
    @Test public void rejectsUnknownDiagnosticArguments(){for(int[] arguments:new int[][]{{1,0},{0,1}}){try{Protocol.fan(Protocol.IR_RECEIVER_TEST,arguments[0],arguments[1],1);fail();}catch(IllegalArgumentException expected){}}}
    @Test public void decodingPreservesOpticalResultsAndUnsignedDiagnostics(){byte[] bytes=result();bytes[1]=3;bytes[2]=3;bytes[3]=3;bytes[4]=3;bytes[6]=2;ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(8,(short)65000).putShort(11,(short)340);Protocol.IrReceiverTest decoded=Protocol.decodeIrReceiverTest(bytes,123);assertFalse(decoded.active());assertEquals(65000,decoded.testId);assertEquals(340,decoded.lastRawLength);assertEquals(2,decoded.overflows);assertEquals(123,decoded.time);assertEquals("PASS: receiver detected 3/3 test bursts",decoded.description());}
    @Test public void noDetectionAndPartialDetectionAreDistinct(){byte[] bytes=result();bytes[1]=3;bytes[2]=3;assertEquals("NO IR DETECTED: 0/3",Protocol.decodeIrReceiverTest(bytes,1).description());bytes[3]=1;bytes[4]=2;assertEquals("PARTIAL: receiver detected 1/3 test bursts",Protocol.decodeIrReceiverTest(bytes,1).description());}
    @Test public void unmatchedInfraredFramesAreNotReportedAsNoInfrared(){byte[] bytes=result();bytes[1]=3;bytes[2]=3;bytes[4]=2;assertEquals("IR DETECTED: test pattern not matched (0/3)",Protocol.decodeIrReceiverTest(bytes,1).description());}
    @Test public void countdownAndAwaitingReceiverRemainActive(){byte[] bytes=result();bytes[1]=1;bytes[5]=5;assertTrue(Protocol.decodeIrReceiverTest(bytes,1).active());assertTrue(Protocol.decodeIrReceiverTest(bytes,1).description().contains("5 seconds"));bytes[1]=2;bytes[5]=0;bytes[2]=1;assertTrue(Protocol.decodeIrReceiverTest(bytes,1).active());bytes[1]=4;assertFalse(Protocol.decodeIrReceiverTest(bytes,1).active());}
    @Test public void malformedResultsCannotBecomeAnOpticalPass(){for(int index:new int[]{0,1,2,3,4,5,13,14,15,16,19}){byte[] bytes=result();bytes[index]=(byte)99;try{Protocol.decodeIrReceiverTest(bytes,1);fail("accepted field "+index);}catch(IllegalArgumentException expected){}}byte[] bytes=result();bytes[1]=3;bytes[2]=3;bytes[3]=3;bytes[4]=0;try{Protocol.decodeIrReceiverTest(bytes,1);fail("pass without received frames");}catch(IllegalArgumentException expected){}}
}
