package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class FanSettingProtocolTest {
    @Test public void knownOffThroughHighestAndUnknownHaveDistinctFlags(){
        for(int setting=0;setting<=10;setting++)Protocol.validateRecord(record(2,0,1,setting));
        Protocol.validateRecord(record(2,0,0,0));
        assertNotEquals(record(2,0,1,0)[1],record(2,0,0,0)[1]);
    }
    @Test public void unsignedIdentityRequiresTheBaseTwoRecordsEarlier(){
        Protocol.validateRecord(record(0xffffffffL,0xfffffffdL,1,9));
        reject(record(0,0xfffffffeL,1,1));reject(record(1,0xffffffffL,1,1));
        reject(record(2,1,1,1));reject(record(3,0,1,1));
    }
    @Test public void invalidFlagsSettingsReservedBytesAndCrcAreRejected(){
        for(int flags:new int[]{2,8,128,255})reject(record(2,0,flags,0));
        for(int setting:new int[]{11,127,255})reject(record(2,0,1,setting));
        reject(record(2,0,0,1)); // Unknown cannot carry an invented setting.
        for(int offset:new int[]{11,16,17,18}){
            byte[] packet=record(2,0,1,9);packet[offset]=1;checksum(packet);reject(packet);
        }
        byte[] damaged=record(2,0,1,9);damaged[10]^=1;reject(damaged);
        reject(new byte[19]);reject(new byte[21]);
    }
    private static byte[] record(long index,long parent,int flags,int setting){
        ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0,(byte)11).put(1,(byte)flags).putInt(2,(int)index).putInt(6,1800000000);
        b.put(10,(byte)setting).putInt(12,(int)parent);return checksum(b.array());
    }
    private static byte[] checksum(byte[] packet){packet[19]=(byte)Protocol.crc8(packet,19);return packet;}
    private static void reject(byte[] packet){
        try{Protocol.validateRecord(packet);fail("Accepted an invalid fan setting record");}catch(IllegalArgumentException expected){}
    }
}
