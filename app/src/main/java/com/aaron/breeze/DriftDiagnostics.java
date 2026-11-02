package com.aaron.breeze;

import java.io.File;
import java.util.function.LongSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Passive observations only. No control decisions or physical-reception claims. */
final class DriftDiagnostics {
    final DriftJournal journal;
    private final LongSupplier wall, elapsed;
    private Protocol.FanState previousFan;
    private long previousUptime=-1;
    private String failure="", lastIncident="";

    DriftDiagnostics(File directory,LongSupplier wall,LongSupplier elapsed)throws java.io.IOException,JSONException {
        journal=new DriftJournal(directory);this.wall=wall;this.elapsed=elapsed;
    }

    private JSONObject entry(String type,String description,JSONObject data)throws JSONException {
        return new JSONObject().put("type",type).put("at",wall.getAsLong())
                .put("elapsedMs",elapsed.getAsLong()).put("description",description).put("data",data);
    }
    void event(String type,String description,JSONObject data) {
        try{journal.append(entry(type,description,data));}catch(Exception error){failed(error);}
    }
    private void failed(Exception error){failure=error.getClass().getSimpleName()+": "+error.getMessage();}
    void continuityBreak(String reason) {
        previousFan=null;previousUptime=-1;
        event("continuity_break",reason+"; commands during the gap are unknown",new JSONObject());
    }

    void packet(byte[] bytes) {
        if(bytes.length!=20)return;
        int type=bytes[0]&255;
        if(type!=1&&type!=4&&type!=5&&type!=8&&type!=9)return;
        try {
            JSONObject data=new JSONObject().put("packet",hex(bytes));
            String description="Controller packet "+type;
            if(type==5){
                Protocol.FanState state=Protocol.decodeFan(bytes,wall.getAsLong());
                data.put("fan",fan(state));
                if(previousFan!=null){
                    int delta=(state.sendCounter-previousFan.sendCounter)&65535;
                    data.put("previousCounter",previousFan.sendCounter).put("previousEstimate",previousFan.speed);
                    if(state.sendCounter<previousFan.sendCounter&&delta>1000){
                        data.put("counterContinuity","reset_or_unknown");
                        description="Counter moved backwards; intervening commands unknown";
                    }else{
                        data.put("observedSendDelta",delta);
                        if(delta==1)description="IR #"+state.sendCounter+" "+commandName(state.lastSent)+"; estimate "+level(previousFan.speed)+" → "+level(state.speed);
                        else if(delta>1){
                            data.put("unidentifiedSends",delta-1);
                            description="IR counter advanced by "+delta+"; only last command known: "+commandName(state.lastSent)+"; estimate "+level(previousFan.speed)+" → "+level(state.speed);
                        }else description="ESP estimate "+level(state.speed)+"; no new IR count";
                    }
                }else description="ESP observation: "+level(state.speed)+", IR counter "+state.sendCounter+"; prior commands unknown";
                previousFan=state;
            }else if(type==9){
                Protocol.LogicState state=Protocol.decodeLogic(bytes,wall.getAsLong());
                data.put("logic",logic(state));
                description="Logic "+(state.automatic()?"on":"off")+", target "+level(state.target)+", boost "+(state.boosting()?state.boostSeconds+"s":"off")+", time adjustment "+state.windowAdjustment;
            }else if(type==1){
                long uptime=Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12));
                if(previousUptime>=0&&uptime<previousUptime)continuityBreak("Controller uptime restarted");
                previousUptime=uptime;data.put("uptimeSeconds",uptime);
                description="Sensor/acknowledgement packet; controller uptime "+uptime+"s";
            }else if(type==4){
                data.put("firmware",(bytes[5]&255)+"."+(bytes[6]&255)+"."+(bytes[7]&255))
                        .put("hardwareTimed",bytes[13]==1).put("softwareBursts",bytes[14]&255);
                description="IR transmitter status; software bursts "+(bytes[14]&255);
            }else description="IR receiver observation";
            event("controller_"+type,description,data);
        }catch(Exception error){failed(error);}
    }

    void command(String phase,byte[] packet,String result) {
        if(packet.length!=20||packet[1]!=6)return;
        try{
            int action=packet[4]&255, value=packet[5]&255;
            String name=action==Protocol.SEND_IR?commandName(value):actionName(action);
            event("command_"+phase,phase+" "+name+(result.isEmpty()?"":" · "+result),
                    new JSONObject().put("packet",hex(packet)).put("action",action).put("value",value)
                            .put("sequence",(packet[2]&255)|((packet[3]&255)<<8)).put("result",result));
        }catch(Exception error){failed(error);}
    }

    void poll(DreoReport report,boolean accepted,String reason,JSONObject context) {
        try{event("dreo_check","Dreo check: "+reason,new JSONObject().put("accepted",accepted)
                .put("report",report(report)).put("context",context));}catch(Exception error){failed(error);}
    }

    /** Called only after the existing correction guards, before any SYNC packet is queued. */
    void compare(DreoReport report,Protocol.FanState state,JSONObject context) {
        if(state==null||!state.synced())return;
        try{
            int actual=Boolean.FALSE.equals(report.power)?0:report.speed;
            JSONObject data=new JSONObject().put("estimate",state.speed).put("reportedLevel",actual)
                    .put("sendCounter",state.sendCounter).put("report",report(report)).put("context",context);
            if(state.speed==actual){
                journal.matched(entry("matching_checkpoint","Matching check: ESP and Dreo "+level(actual),data));
            }else{
                JSONObject observation=entry("mismatch","Mismatch: ESP "+level(state.speed)+", Dreo "+level(actual)+"; before correction",data);
                if(!failure.isEmpty())observation.put("earlierDiagnosticError",failure);
                lastIncident=journal.mismatch(observation);
            }
        }catch(Exception error){failed(error);}
    }

    String status(){
        try{return "savedIncidents="+journal.incidents().size()+" activeBytes="+journal.currentFile().length()+" lastIncident="+lastIncident+" error="+failure;}
        catch(Exception error){failed(error);return "error="+failure;}
    }

    static JSONObject context(Protocol.FanState fan,Protocol.LogicState logic,String firmware,String savedProfile,
                              boolean profileApplied,boolean paused,String controller,String zone)throws JSONException {
        return new JSONObject().put("fan",fan(fan)).put("logic",logic(logic)).put("firmware",firmware)
                .put("savedProfile",savedProfile).put("profileAppliedForConnection",profileApplied)
                .put("logicPausedOnPhone",paused).put("controller",controller).put("zone",zone)
                .put("evidence","Received telemetry, not proof of physical IR reception. Packet receipt times may differ.");
    }
    private static JSONObject fan(Protocol.FanState f)throws JSONException {
        if(f==null)return new JSONObject().put("available",false);
        return new JSONObject().put("receivedAt",f.time).put("estimate",f.speed).put("sendCounter",f.sendCounter)
                .put("lastSent",commandName(f.lastSent)).put("target",f.target).put("synced",f.synced())
                .put("flags",f.flags).put("error",f.error);
    }
    private static JSONObject logic(Protocol.LogicState l)throws JSONException {
        if(l==null)return new JSONObject().put("available",false);
        return new JSONObject().put("receivedAt",l.time).put("automatic",l.automatic()).put("target",l.target)
                .put("boosting",l.boosting()).put("boostSeconds",l.boostSeconds).put("windowAdjustment",l.windowAdjustment)
                .put("revision",l.revision).put("flags",l.flags).put("error",l.error)
                .put("averageBodyF",finite(l.averageBodyF)).put("roomF",finite(l.roomF)).put("score",finite(l.score));
    }
    private static Object finite(double value){return Double.isFinite(value)?value:JSONObject.NULL;}
    private static JSONObject report(DreoReport r)throws JSONException {
        if(r==null)return new JSONObject().put("available",false);
        return new JSONObject().put("requestStartedAt",r.requestStartedAtMs).put("receivedAt",r.receivedAtMs)
                .put("power",r.power==null?JSONObject.NULL:r.power).put("speed",r.speed==null?JSONObject.NULL:r.speed)
                .put("mode",r.mode==null?JSONObject.NULL:r.mode).put("connected",r.connected==null?JSONObject.NULL:r.connected)
                .put("speedChangedAt",r.speedTimestamp).put("powerChangedAt",r.powerTimestamp).put("modeChangedAt",r.modeTimestamp);
    }
    static String commandName(int slot){return slot>=0&&slot<Protocol.COMMANDS.length?Protocol.COMMANDS[slot]:"Unknown";}
    static String level(int level){return level==10?"H":level==0?"Off":Integer.toString(level);}
    private static String actionName(int action){return switch(action){
        case Protocol.TARGET_SPEED->"target speed";case Protocol.SYNC_SPEED->"synchronize estimate";case Protocol.AUTO->"Logic on/off";
        case Protocol.LOGIC_GLOBAL->"Logic timing settings";case Protocol.LOGIC_RULE->"Logic range settings";
        case Protocol.LOGIC_BOOST->"boost settings";case Protocol.LOGIC_WINDOW->"time-range settings";
        case Protocol.LOGIC_COMMIT->"apply Logic settings";case Protocol.LOGIC_CLOCK->"time-range clock";
        case Protocol.DOUBLE_HIT->"double-hit setting";default->"fan action "+action;};}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
}
