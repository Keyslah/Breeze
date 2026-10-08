package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public final class Protocol {
    private Protocol() {}
    public static final int START_RECORDING=7, STOP_RECORDING=8;
    public static boolean sensorFirmware(byte[] data){return data.length==20&&data[0]==4&&data[4]==6;}
    public static final UUID SERVICE = UUID.fromString("689a1001-744d-4aca-89b3-028bc1fe3201");
    public static final UUID TX = UUID.fromString("689a1002-744d-4aca-89b3-028bc1fe3201");
    public static final UUID RX = UUID.fromString("689a1003-744d-4aca-89b3-028bc1fe3201");
    public static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static class Reading {
        public int flags, sequence, ack, error, commandError;
        public long uptime, time;
        public double probe, room, humidity;
        public boolean active() { return (flags & 1) != 0; }
    }
    public static Reading decode(byte[] data, long time) {
        if (data.length != 20 || data[0] != 1) throw new IllegalArgumentException("Unsupported controller packet");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        Reading r = new Reading(); r.time=time; r.flags=data[1]&255; r.sequence=b.getShort(2)&65535;
        r.probe=(r.flags&8)!=0 ? b.getShort(4)/100.0 : Double.NaN;
        r.room=(r.flags&16)!=0 ? b.getShort(6)/100.0 : Double.NaN;
        r.humidity=(r.flags&32)!=0 ? (b.getShort(8)&65535)/100.0 : Double.NaN;
        r.uptime=Integer.toUnsignedLong(b.getInt(12));
        r.ack=b.getShort(16)&65535; r.error=data[18]&255;
        r.commandError=data[19]&255;
        if ((Double.isFinite(r.probe)&&(r.probe < -20 || r.probe > 80)) || (Double.isFinite(r.humidity)&&r.humidity > 100)) throw new IllegalArgumentException("Invalid sensor packet");
        return r;
    }
    private static ByteBuffer packet(int op,int seq) { return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put((byte)1).put((byte)op).putShort((short)seq); }
    public static byte[] control(int action,int value,int seq) { return packet(2,seq).put((byte)action).put((byte)value).array(); }
    public static byte[] sync(long from,int seq) { return packet(3,seq).putInt((int)from).array(); }
    public static byte[] clock(long seconds,int seq) { return packet(4,seq).putInt((int)seconds).array(); }
    public static byte[] retire(long savedCursor,int seq) { return packet(5,seq).putInt((int)savedCursor).array(); }
    public static int crc8(byte[] bytes,int length){int crc=0;for(int i=0;i<length;i++){crc^=bytes[i]&255;for(int bit=0;bit<8;bit++)crc=((crc&128)!=0?(crc<<1)^7:crc<<1)&255;}return crc;}
    public static void validateRecord(byte[] b){if(b.length!=20||b[0]!=2||(b[19]&255)!=crc8(b,19)||(b[16]&255)>3||(b[17]&255)>180)throw new IllegalArgumentException("Damaged controller record; not acknowledged");}
    public static String error(int code) { return switch(code) { case 0 -> ""; case 1 -> "Unsupported command"; case 3 -> "Body probe unavailable"; case 4 -> "Stop recording before changing storage"; case 6 -> "Controller storage unavailable or full: recording stopped"; case 7 -> "Set the controller clock before starting"; default -> "Controller error " + code; }; }

}
