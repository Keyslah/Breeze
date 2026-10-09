package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiverInputProtocolTest {
    private byte[] packet(){byte[] bytes=new byte[20];bytes[0]=8;bytes[2]=(byte)255;ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(4,(short)-1);bytes[19]=1;return bytes;}
    @Test public void initialReceiverStateHasNoInput(){Protocol.ReceiverInput input=Protocol.decodeReceiverInput(packet(),123);assertFalse(input.hasInput());assertEquals(0,input.counter);assertEquals(-1,input.protocol);assertEquals("Unrecognized IR",input.label());}
    @Test public void externalRemotePacketPreservesRawUnsignedValue(){byte[] bytes=packet();bytes[1]=1;bytes[2]=0;bytes[3]=32;ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(4,(short)3).putShort(6,(short)65535).putLong(8,0xFEDCBA9876543210L).putShort(16,(short)65000);Protocol.ReceiverInput input=Protocol.decodeReceiverInput(bytes,123);assertTrue(input.hasInput());assertFalse(input.duringLedSend());assertEquals("External input",input.source());assertEquals("Power",input.label());assertEquals("NEC",input.protocolName());assertEquals(65535,input.counter);assertEquals(65000,input.rawLength);assertTrue(input.details().contains("0xFEDCBA9876543210"));assertEquals(123,input.time);}
    @Test public void ledSendAndDiagnosticFlagsAreDistinct(){byte[] bytes=packet();bytes[1]=3;bytes[2]=2;Protocol.ReceiverInput input=Protocol.decodeReceiverInput(bytes,1);assertEquals("During LED send",input.source());assertEquals("Speed -",input.label());bytes[1]=19;assertEquals("Test burst",Protocol.decodeReceiverInput(bytes,1).label());}
    @Test public void futureProtocolEnumIsReportedWithoutGuessing(){byte[] bytes=packet();bytes[1]=1;ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(4,(short)101);assertEquals("Protocol #101",Protocol.decodeReceiverInput(bytes,1).protocolName());}
    @Test public void malformedMonitorPacketsAreRejected(){for(int index:new int[]{0,1,2,19}){byte[] bytes=packet();bytes[index]=(byte)99;try{Protocol.decodeReceiverInput(bytes,1);fail("accepted field "+index);}catch(IllegalArgumentException expected){}}}
}
