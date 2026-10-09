package com.aaron.breeze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public final class Protocol {
    private Protocol() {}
    public static final int START_RECORDING=7, STOP_RECORDING=8;
    public static boolean sensorFirmware(byte[] data){return data.length==20&&data[0]==4&&(data[4]&255)>=6;}
    public static final int LEARN=1,CANCEL_LEARN=2,CLEAR_LEARNED=3,SEND_IR=4,TARGET_SPEED=5,SYNC_SPEED=6,AUTO=7,VERIFIED=8,FORGET_FAN=9,CONTACT_TEST=10,MARK_TEST=11,IR_RECEIVER_TEST=12;
    public static final String[] COMMANDS={"Power","Speed +","Speed -","Mode","Horizontal oscillation","Vertical oscillation","Extra 1","Extra 2","Extra 3","Extra 4","Extra 5","Extra 6"};
    public static class FanState {
        public int flags,speed,target,learning,learnState,learned,error,source,resultSlot,lastSent,sendCounter,phase;
        public double secondProbe,warmerBody;
        public long time;
        public boolean synced(){return (flags&4)!=0;}
        public boolean busy(){return (flags&32)!=0;}
        public boolean has(int slot){return slot>=0&&slot<COMMANDS.length&&(learned&(1<<slot))!=0;}
    }
    public static FanState decodeFan(byte[] data,long time){
        if(data.length!=20||data[0]!=5||data[19]!=7)throw new IllegalArgumentException("Unsupported fan packet");
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);FanState f=new FanState();f.time=time;f.flags=data[1]&255;
        f.secondProbe=(f.flags&1)!=0?b.getShort(2)/100.0:Double.NaN;f.speed=data[4]&255;f.target=data[5]&255;f.learning=data[6]&255;f.learnState=data[7]&255;f.learned=b.getShort(8)&65535;f.error=data[10]&255;f.source=data[11]&255;f.warmerBody=f.source==0?Double.NaN:b.getShort(12)/100.0;f.resultSlot=data[14]&255;f.lastSent=data[15]&255;f.sendCounter=b.getShort(16)&65535;f.phase=data[18]&255;
        if(f.phase>3||f.speed>9||f.target>9||f.source>2||f.learnState>5||(f.learning!=255&&f.learning>=COMMANDS.length)||(f.resultSlot!=255&&f.resultSlot>=COMMANDS.length)||(f.lastSent!=255&&f.lastSent>=COMMANDS.length)||(f.learned&~4095)!=0||(Double.isFinite(f.secondProbe)&&(f.secondProbe< -20||f.secondProbe>80)))throw new IllegalArgumentException("Invalid fan packet");
        return f;
    }
    public static byte[] fan(int action,int value,int argument,int seq){
        if(action<1||action>12)throw new IllegalArgumentException("Unknown fan action");
        if(action==IR_RECEIVER_TEST&&(value!=0||argument!=0))throw new IllegalArgumentException("Invalid LED receiver test setting");
        if(action==MARK_TEST&&(value<1||value>3))throw new IllegalArgumentException("Unknown probe test marker");
        if((action==LEARN||action==CLEAR_LEARNED||action==SEND_IR)&&(value<0||value>=COMMANDS.length))throw new IllegalArgumentException("Unknown remote button");
        if((action==TARGET_SPEED||action==SYNC_SPEED)&&(value<1||value>9))throw new IllegalArgumentException("Speed must be 1–9");
        if(action==LEARN&&(argument<30||argument>60))throw new IllegalArgumentException("Raw carrier must be 30–60 kHz");
        if(action==SYNC_SPEED&&argument!=1)throw new IllegalArgumentException("Confirm fan is on in Normal mode");
        return packet(6,seq).put((byte)action).put((byte)value).putShort((short)argument).array();
    }
    public static class IrReceiverTest {
        public int state,attempts,matched,framesSeen,secondsUntilNext,overflows,testId,error,lastRawLength,rxIdleLevel,carrier,txPin,rxPin;
        public long time;
        public boolean active(){return state==1||state==2;}
        public String description(){
            if(error!=0)return "LED receiver test stopped: "+Protocol.error(error);
            return switch(state){
                case 1 -> "LED test starts in "+secondsUntilNext+" seconds. Aim the LED at the receiver.";
                case 2 -> "Checking infrared: "+attempts+"/3 bursts sent · "+matched+"/3 detected";
                case 3 -> matched==3?"PASS: receiver detected 3/3 test bursts":matched==0?(framesSeen==0?"NO IR DETECTED: 0/3":"IR DETECTED: test pattern not matched (0/3)"):"PARTIAL: receiver detected "+matched+"/3 test bursts";
                case 4 -> "LED receiver test canceled.";
                default -> "Ready to test the LED with the receiver.";
            };
        }
    }
    public static IrReceiverTest decodeIrReceiverTest(byte[] data,long time){
        if(data.length!=20||data[0]!=7||data[19]!=1)throw new IllegalArgumentException("Unsupported LED receiver test packet");
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);IrReceiverTest test=new IrReceiverTest();test.time=time;
        test.state=data[1]&255;test.attempts=data[2]&255;test.matched=data[3]&255;test.framesSeen=data[4]&255;test.secondsUntilNext=data[5]&255;test.overflows=data[6]&255;test.testId=b.getShort(8)&65535;test.error=data[10]&255;test.lastRawLength=b.getShort(11)&65535;test.rxIdleLevel=data[13]&255;test.carrier=data[14]&255;test.txPin=data[15]&255;test.rxPin=data[16]&255;
        if(test.state>4||test.attempts>3||test.matched>test.attempts||test.framesSeen>test.attempts||test.matched>test.framesSeen||test.secondsUntilNext>5||test.rxIdleLevel>1||test.carrier!=38||test.txPin!=17||test.rxPin!=16)throw new IllegalArgumentException("Invalid LED receiver test packet");
        return test;
    }
    public static class ReceiverInput {
        public int flags,matchedSlot,bits,protocol,counter,rawLength;
        public long value,time;
        public boolean hasInput(){return (flags&1)!=0;}
        public boolean duringLedSend(){return (flags&2)!=0;}
        public String source(){return duringLedSend()?"During LED send":"External input";}
        public String label(){return (flags&16)!=0?"Test burst":matchedSlot<COMMANDS.length?COMMANDS[matchedSlot]:"Unrecognized IR";}
        public String protocolName(){return switch(protocol){case -1->"Unknown";case 1->"RC5";case 2->"RC6";case 3->"NEC";case 4->"Sony";case 5->"Panasonic";case 6->"JVC";case 7->"Samsung";default->"Protocol #"+protocol;};}
        public String details(){return protocolName()+" · "+bits+" bits · 0x"+Long.toUnsignedString(value,16).toUpperCase(java.util.Locale.US)+((flags&4)!=0?" · repeat":"")+((flags&8)!=0?" · overflow":"");}
    }
    public static ReceiverInput decodeReceiverInput(byte[] data,long time){
        if(data.length!=20||data[0]!=8||data[19]!=1)throw new IllegalArgumentException("Unsupported receiver input packet");
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);ReceiverInput input=new ReceiverInput();input.time=time;input.flags=data[1]&255;input.matchedSlot=data[2]&255;input.bits=data[3]&255;input.protocol=b.getShort(4);input.counter=b.getShort(6)&65535;input.value=b.getLong(8);input.rawLength=b.getShort(16)&65535;
        if((input.flags&~31)!=0||(input.matchedSlot!=255&&input.matchedSlot>=COMMANDS.length))throw new IllegalArgumentException("Invalid receiver input packet");
        return input;
    }
    public static final UUID SERVICE = UUID.fromString("689a1001-744d-4aca-89b3-028bc1fe3201");
    public static final UUID TX = UUID.fromString("689a1002-744d-4aca-89b3-028bc1fe3201");
    public static final UUID RX = UUID.fromString("689a1003-744d-4aca-89b3-028bc1fe3201");
    public static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static class Reading {
        public int flags, sequence, ack, error, commandError;
        public long uptime, time;
        public double probe, room, humidity;
        public boolean active() { return (flags & 1) != 0; }
        public boolean storageReady() { return (flags & 128) != 0; }
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
    public static void validateRecord(byte[] b){
        if(b.length!=20||(b[0]!=2&&b[0]!=6)||(b[19]&255)!=crc8(b,19))throw new IllegalArgumentException("Damaged controller record; not acknowledged");
        if(b[0]==2&&((b[16]&255)>3||(b[17]&255)>180))throw new IllegalArgumentException("Damaged legacy record");
        if(b[0]==6&&(b[1]&2)!=0){int v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(16);if(v< -2000||v>8000)throw new IllegalArgumentException("Damaged second probe record");}
    }
    public static String error(int code) { return switch(code) { case 0 -> ""; case 1 -> "Unsupported command"; case 3 -> "Both body probes unavailable"; case 4 -> "Stop recording first"; case 6 -> "Controller storage unavailable or full: recording stopped"; case 7 -> "Set the controller clock before starting"; case 20 -> "Command not learned"; case 21 -> "Sync current speed with the fan on in Normal mode"; case 22 -> "Controller busy; wait for recording or IR work to finish"; case 23 -> "Learning timed out; try again"; case 24 -> "Could not save IR data in controller storage"; case 25 -> "IR capture too short or too long; try one button press"; case 26 -> "Invalid fan setting"; case 27 -> "No usable body probe; automatic changes stopped"; case 28 -> "Test the learned commands on the physical fan first"; case 29 -> "Original remote detected; sync current speed again"; case 30 -> "Automatic rules are not defined yet. Collect the probe test data first."; default -> "Controller error " + code; }; }

}
