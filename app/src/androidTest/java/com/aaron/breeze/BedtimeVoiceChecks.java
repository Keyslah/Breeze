package com.aaron.breeze;

import android.app.Instrumentation;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Native decoder check with synthetic fixtures, no microphone or history access. */
public final class BedtimeVoiceChecks {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    public static String run(Instrumentation instrumentation) throws Exception {
        String[][] cases={
            {"warm7","FEELING","WARM","7"},{"cold3","FEELING","COLD","3"},
            {"warm1","FEELING","WARM","1"},{"warm10","FEELING","WARM","10"},
            {"cold1","FEELING","COLD","1"},{"cold10","FEELING","COLD","10"},
            {"warm2","FEELING","WARM","2"},{"cold2","FEELING","COLD","2"},
            {"level2","FEELING","WARM","2"},{"invalid11","INVALID"},
            {"invalid0","INVALID"},{"invalidHot","INVALID"},{"stop","STOP"},
            {"wake","READY"},{"background","IGNORE"}
        };
        StringBuilder evidence=new StringBuilder();
        try(Model model=new Model(OfflineVoiceModel.prepare(instrumentation.getTargetContext()).getAbsolutePath())){
            for(String[] test:cases){
                byte[] pcm;
                try(InputStream input=instrumentation.getContext().getAssets().open("bedtime-voice/"+test[0]+".wav")){
                    pcm=waveData(input.readAllBytes());
                }
                try(Recognizer recognizer=new Recognizer(model,16000,BedtimeVoiceCommand.grammarJson())){
                    recognizer.setWords(true);
                    StringBuilder heard=new StringBuilder();
                    for(int at=0;at<pcm.length;at+=3200){
                        int count=Math.min(3200,pcm.length-at);
                        byte[] chunk=java.util.Arrays.copyOfRange(pcm,at,at+count);
                        if(recognizer.acceptWaveForm(chunk,count))append(heard,recognizer.getResult());
                    }
                    byte[] silence=new byte[3200];
                    for(int n=0;n<30;n++)if(recognizer.acceptWaveForm(silence,silence.length))append(heard,recognizer.getResult());
                    append(heard,recognizer.getFinalResult());
                    BedtimeVoiceCommand command=BedtimeVoiceCommand.parse(heard.toString());
                    evidence.append(test[0]).append(": ").append(heard).append(" -> ").append(command.kind).append('\n');
                    check(command.kind.name().equals(test[1]),evidence.toString());
                    if(test.length==4)check(test[2].equals(command.state)&&Integer.parseInt(test[3])==command.intensity,evidence.toString());
                }
            }
        }
        return "PASS: actual Android Vosk decoding of 15 synthetic phrases; no microphone or database used\n"+evidence;
    }

    private static void append(StringBuilder text,String json) throws Exception {
        String part=new JSONObject(json).optString("text","").trim();
        if(!part.isEmpty()){if(text.length()>0)text.append(' ');text.append(part);}
    }

    private static byte[] waveData(byte[] wave){
        ByteBuffer data=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        check(data.getInt(0)==0x46464952&&data.getInt(8)==0x45564157,"RIFF WAVE required");
        boolean format=false;
        for(int at=12;at+8<=wave.length;){
            int type=data.getInt(at),length=data.getInt(at+4);int start=at+8;
            check(length>=0&&start+length<=wave.length,"Valid wave chunk required");
            if(type==0x20746d66){check(length>=16&&data.getShort(start)==1&&data.getShort(start+2)==1
                    &&data.getInt(start+4)==16000&&data.getShort(start+14)==16,"16 kHz mono PCM required");format=true;}
            if(type==0x61746164){check(format,"Wave format must precede data");return java.util.Arrays.copyOfRange(wave,start,start+length);}
            at=start+length+(length&1);
        }
        throw new AssertionError("Wave data missing");
    }
}
