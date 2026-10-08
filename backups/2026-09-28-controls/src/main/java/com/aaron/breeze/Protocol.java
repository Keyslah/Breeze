package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public final class Protocol {
    private Protocol() {}
    public static final UUID SERVICE = UUID.fromString("689a1001-744d-4aca-89b3-028bc1fe3201");
    public static final UUID TX = UUID.fromString("689a1002-744d-4aca-89b3-028bc1fe3201");
    public static final UUID RX = UUID.fromString("689a1003-744d-4aca-89b3-028bc1fe3201");
    public static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Logical cooling strength; physical dial order is separately mapped.
    public static final String[] SPEEDS = {"Off", "Low", "Medium", "High"};
    public static class Config {
        public double low = 29, medium = 31, high = 33, hysteresis = .5;
        public int offAngle = 60, highAngle = 75, mediumAngle = 90, lowAngle = 105;
        public int dwell = 30, ambientWeight = 0, humidityThreshold = 65, humidityBoost = 0;
        public boolean calibrated = false;
        public void validate() {
            if (!Double.isFinite(low) || !Double.isFinite(medium) || !Double.isFinite(high) || low < 5 || high > 50 || !(low < medium && medium < high)) throw new IllegalArgumentException("Temperatures must increase: Low < Medium < High, within 5–50 °C.");
            if (!Double.isFinite(hysteresis) || hysteresis < .1 || hysteresis > 2 || medium-low <= hysteresis || high-medium <= hysteresis) throw new IllegalArgumentException("Hysteresis must be 0.1–2 °C and smaller than the gaps between thresholds.");
            for (int a : new int[]{offAngle, highAngle, mediumAngle, lowAngle}) if (a < 0 || a > 180) throw new IllegalArgumentException("Servo angles must be 0–180°.");
            if (!((offAngle < highAngle && highAngle < mediumAngle && mediumAngle < lowAngle) || (offAngle > highAngle && highAngle > mediumAngle && mediumAngle > lowAngle))) throw new IllegalArgumentException("Angles must follow the dial: Off → High → Medium → Low, in increasing or decreasing order.");
            if (Math.abs(offAngle-lowAngle) > 90) throw new IllegalArgumentException("Keep the four positions within a 90° span; expected travel is about 45°.");
            if (dwell < 5 || dwell > 255 || ambientWeight < 0 || ambientWeight > 50 || humidityThreshold < 0 || humidityThreshold > 100 || humidityBoost < 0 || humidityBoost > 20) throw new IllegalArgumentException("Check dwell (5–255 s), room weight (0–50%), RH (0–100%), and boost (0–20 tenths).");
        }
    }
    public static class Reading {
        public int flags, sequence, speed, angle, ack, error, commandError;
        public long uptime, time;
        public double probe, room, humidity;
        public boolean active() { return (flags & 1) != 0; }
        public boolean automatic() { return (flags & 2) != 0; }
        public boolean calibrated() { return (flags & 4) != 0; }
    }
    public static Reading decode(byte[] data, long time) {
        if (data.length != 20 || data[0] != 1) throw new IllegalArgumentException("Unsupported controller packet");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        Reading r = new Reading(); r.time=time; r.flags=data[1]&255; r.sequence=b.getShort(2)&65535;
        r.probe=(r.flags&8)!=0 ? b.getShort(4)/100.0 : Double.NaN;
        r.room=(r.flags&16)!=0 ? b.getShort(6)/100.0 : Double.NaN;
        r.humidity=(r.flags&32)!=0 ? (b.getShort(8)&65535)/100.0 : Double.NaN;
        r.speed=data[10]&255; r.angle=data[11]&255; r.uptime=Integer.toUnsignedLong(b.getInt(12));
        r.ack=b.getShort(16)&65535; r.error=data[18]&255;
        r.commandError=data[19]&255;
        if (r.speed > 3 || r.angle > 180 || (Double.isFinite(r.probe)&&(r.probe < -20 || r.probe > 80)) || (Double.isFinite(r.humidity)&&r.humidity > 100)) throw new IllegalArgumentException("Invalid sensor packet");
        return r;
    }
    private static ByteBuffer packet(int op,int seq) { return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put((byte)1).put((byte)op).putShort((short)seq); }
    public static byte[] config(Config c,int seq) {
        c.validate();
        return packet(1,seq).putShort((short)Math.round(c.low*100)).putShort((short)Math.round(c.medium*100)).putShort((short)Math.round(c.high*100))
                .put((byte)c.offAngle).put((byte)c.lowAngle).put((byte)c.mediumAngle).put((byte)c.highAngle)
                .put((byte)Math.round(c.hysteresis*10)).put((byte)c.dwell).put((byte)c.ambientWeight).put((byte)c.humidityThreshold).put((byte)c.humidityBoost).put((byte)(c.calibrated?1:0)).array();
    }
    public static byte[] control(int action,int value,int seq) { return packet(2,seq).put((byte)action).put((byte)value).array(); }
    public static byte[] sync(long from,int seq) { return packet(3,seq).putInt((int)from).array(); }
    public static byte[] clock(long seconds,int seq) { return packet(4,seq).putInt((int)seconds).array(); }
    public static byte[] retire(long savedCursor,int seq) { return packet(5,seq).putInt((int)savedCursor).array(); }
    public static int crc8(byte[] bytes,int length){int crc=0;for(int i=0;i<length;i++){crc^=bytes[i]&255;for(int bit=0;bit<8;bit++)crc=((crc&128)!=0?(crc<<1)^7:crc<<1)&255;}return crc;}
    public static void validateRecord(byte[] b){if(b.length!=20||b[0]!=2||(b[19]&255)!=crc8(b,19)||(b[16]&255)>3||(b[17]&255)>180)throw new IllegalArgumentException("Damaged controller record; not acknowledged");}
    public static String error(int code) { return switch(code) { case 0 -> ""; case 1 -> "Invalid command"; case 2 -> "Calibrate positions first"; case 3 -> "Probe fault: fan held at its last position"; case 4 -> "Stop control before changing calibration"; case 5 -> "Servo PWM unavailable"; case 6 -> "Controller storage unavailable or full: recording stopped"; case 7 -> "Set the controller clock before starting"; default -> "Controller error " + code; }; }
}
