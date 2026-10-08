package com.aaron.breeze;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class OtaProtocolTest {
    @Test public void otaActionsUseExistingTwentyByteEnvelope(){
        byte[] enter=Protocol.ota(Protocol.ENTER_OTA,0x1234),cancel=Protocol.ota(Protocol.CANCEL_OTA,7),confirm=Protocol.ota(Protocol.CONFIRM_OTA,8);
        assertEquals(20,enter.length);assertEquals(1,enter[0]);assertEquals(7,enter[1]);assertEquals(0x1234,ByteBuffer.wrap(enter).order(ByteOrder.LITTLE_ENDIAN).getShort(2)&0xffff);assertEquals(1,enter[4]);assertEquals(2,cancel[4]);assertEquals(3,confirm[4]);
    }
    @Test public void readyStatusDerivesLockedWifiCredentials(){
        byte[] b=new byte[20];b[0]=7;b[1]=2;b[2]=37;ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(4,1234).putInt(8,5678);for(int i=0;i<8;i++)b[12+i]=(byte)(i+1);
        Protocol.OtaStatus status=Protocol.decodeOta(b);assertEquals(Protocol.OTA_READY,status.state);assertEquals(37,status.progress);assertEquals(1234,status.received);assertEquals(5678,status.total);assertEquals("0102030405060708",status.tokenHex());assertEquals("Breeze-OTA-010203",status.ssid());
    }
    @Test public void malformedStatusAndActionAreRejected(){
        byte[] bad=new byte[20];bad[0]=7;bad[1]=8;try{Protocol.decodeOta(bad);fail();}catch(IllegalArgumentException expected){}
        try{Protocol.ota(4,1);fail();}catch(IllegalArgumentException expected){}
    }
}
