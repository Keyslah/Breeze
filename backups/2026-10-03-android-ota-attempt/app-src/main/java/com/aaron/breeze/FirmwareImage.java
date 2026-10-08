package com.aaron.breeze;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Validation shared by the file picker and local JVM tests. */
final class FirmwareImage {
    /** Absolute phone-side allocation guard; the controller reports the real OTA slot at READY. */
    static final int MAX_BYTES=0x1B0000;
    private static final int MIN_BYTES=1024;
    private static final byte[] MARKER="BREEZE-FW:".getBytes(StandardCharsets.US_ASCII);

    final String name,version,sha256;
    final byte[] bytes;

    private FirmwareImage(String name,String version,String sha256,byte[] bytes){this.name=name;this.version=version;this.sha256=sha256;this.bytes=bytes;}
    long size(){return bytes.length;}

    static FirmwareImage read(String name,long declaredSize,InputStream input)throws Exception{
        validateNameAndSize(name,declaredSize);
        try(input;ByteArrayOutputStream output=new ByteArrayOutputStream((int)Math.max(0,Math.min(declaredSize,MAX_BYTES)))){
            byte[] buffer=new byte[16384];int count,total=0;
            while((count=input.read(buffer))!=-1){total+=count;if(total>MAX_BYTES)throw new IllegalArgumentException("Firmware exceeds the controller's 1,769,472-byte OTA slot.");output.write(buffer,0,count);}
            return validate(name,output.toByteArray());
        }
    }

    static FirmwareImage validate(String name,byte[] bytes)throws Exception{
        validateNameAndSize(name,bytes==null?-1:bytes.length);
        if(bytes.length<MIN_BYTES)throw new IllegalArgumentException("Firmware file is too small to be an ESP32 application image.");
        if((bytes[0]&255)!=0xE9)throw new IllegalArgumentException("This is not an ESP32 application .bin file (missing image magic).");
        int chip=(bytes[12]&255)|((bytes[13]&255)<<8);
        if(chip!=0x000D)throw new IllegalArgumentException("Firmware is not built for the XIAO ESP32-C6.");
        if(unsignedInt(bytes,32)!=0xABCD5432L)throw new IllegalArgumentException("Firmware is missing the ESP application descriptor.");
        String version=findVersion(bytes);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");StringBuilder hash=new StringBuilder(64);
        for(byte value:digest.digest(bytes))hash.append(String.format(Locale.US,"%02x",value&255));
        return new FirmwareImage(name,version,hash.toString(),bytes);
    }

    private static void validateNameAndSize(String name,long size){
        if(name==null||!name.toLowerCase(Locale.US).endsWith(".bin"))throw new IllegalArgumentException("Choose a compiled Breeze firmware file ending in .bin.");
        if(size>MAX_BYTES)throw new IllegalArgumentException("Firmware exceeds the controller's 1,769,472-byte OTA slot.");
    }

    private static String findVersion(byte[] bytes){
        String found=null;
        for(int i=0;i<=bytes.length-MARKER.length;i++){
            boolean match=true;for(int j=0;j<MARKER.length;j++)if(bytes[i+j]!=MARKER[j]){match=false;break;}if(!match)continue;
            int start=i+MARKER.length,end=start;while(end<bytes.length&&end-start<31){int c=bytes[end]&255;if((c>='0'&&c<='9')||c=='.')end++;else break;}
            String version=new String(bytes,start,end-start,StandardCharsets.US_ASCII);
            if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("Breeze firmware version marker is invalid.");
            if(found!=null)throw new IllegalArgumentException("Firmware contains more than one Breeze version marker.");found=version;i=end-1;
        }
        if(found==null)throw new IllegalArgumentException("This ESP32-C6 image is not a versioned Breeze firmware build.");return found;
    }

    private static long unsignedInt(byte[] bytes,int offset){
        if(bytes.length<offset+4)return -1;
        return Integer.toUnsignedLong((bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16)|(bytes[offset+3]<<24));
    }
}
