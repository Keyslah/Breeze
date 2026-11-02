package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class DoubleHitProtocolTest {
    @Test public void timeoutIsOneControllerSetting(){
        for(int seconds:new int[]{0,1,60,300,3600}){
            byte[] packet=Protocol.doubleHitTimeout(seconds,1234);
            ByteBuffer b=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(20,packet.length);assertEquals(1,packet[0]);assertEquals(6,packet[1]);
            assertEquals(1234,b.getShort(2)&65535);assertEquals(17,packet[4]);assertEquals(0,packet[5]);
            assertEquals(seconds,b.getShort(6)&65535);
            for(int i=8;i<20;i++)assertEquals(0,packet[i]);
        }
    }
    @Test public void rejectsInvalidTimeouts(){
        for(int seconds:new int[]{-1,3601,Integer.MAX_VALUE}){
            try{Protocol.doubleHitTimeout(seconds,1);fail("Invalid timeout accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void requiresAdvertisedSupport(){
        assertFalse(Protocol.doubleHitFirmware(null));assertFalse(Protocol.doubleHitFirmware(new byte[0]));
        byte[] caps=new byte[20];caps[0]=4;caps[4]=7;
        assertFalse(Protocol.doubleHitFirmware(caps));caps[12]=1;assertTrue(Protocol.doubleHitFirmware(caps));
        caps[4]=6;assertFalse(Protocol.doubleHitFirmware(caps));
        caps[4]=7;caps[0]=5;assertFalse(Protocol.doubleHitFirmware(caps));
    }
}
