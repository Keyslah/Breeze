package com.aaron.breeze;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class FirmwareImageTest {
    private byte[] valid(){byte[] b=new byte[2048];b[0]=(byte)0xE9;b[12]=0x0D;ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(32,0xABCD5432);byte[] marker="BREEZE-FW:1.1.0".getBytes(StandardCharsets.US_ASCII);System.arraycopy(marker,0,b,128,marker.length);return b;}
    @Test public void validatesC6BreezeImageAndComputesHash()throws Exception{FirmwareImage image=FirmwareImage.validate("BreezeController.bin",valid());assertEquals("1.1.0",image.version);assertEquals(2048,image.size());assertEquals("79b8940e3a95303e235ddaa6a9fd03f55fcfa7ffa73805adc8d91861450faa06",image.sha256);}
    @Test public void wrongExtensionMagicChipAndMarkerAreRejected()throws Exception{
        byte[] good=valid();assertRejected("firmware.txt",good,"ending in .bin");byte[] badMagic=good.clone();badMagic[0]=0;assertRejected("firmware.bin",badMagic,"image magic");byte[] badChip=good.clone();badChip[12]=0;assertRejected("firmware.bin",badChip,"ESP32-C6");byte[] badDescriptor=good.clone();badDescriptor[32]=0;assertRejected("firmware.bin",badDescriptor,"application descriptor");byte[] noMarker=good.clone();noMarker[128]=0;assertRejected("firmware.bin",noMarker,"versioned Breeze");
    }
    @Test public void oversizedImageIsRejectedBeforeUpload()throws Exception{assertRejected("firmware.bin",new byte[FirmwareImage.MAX_BYTES+1],"OTA slot");}
    private void assertRejected(String name,byte[] bytes,String message)throws Exception{try{FirmwareImage.validate(name,bytes);fail("accepted "+message);}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(message));}}
}
