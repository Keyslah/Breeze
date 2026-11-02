package com.aaron.breeze;

import android.os.Handler;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One read-only request at a time, owned by the already-started Bluetooth foreground service. */
final class DreoSynchronizer {
    static final long POLL_MS=180000, MAX_RETRY_MS=900000;
    private final BreezeService service;
    private final Handler handler;
    private final DreoClient client;
    private final DreoSyncPolicy policy=new DreoSyncPolicy();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Runnable poll=this::fetch;
    private volatile boolean stopped;
    private boolean inFlight,authenticationFailed,applied;
    private int failures;
    private long lastConfirmedAt;
    private long lastAttemptWallTime,lastResponseWallTime;
    private int requestCount,successfulReadCount;
    private DreoReport report;
    private String correctionNote="";
    private String versionsKey;
    // Worker-owned collection identity prevents connecting a line across service restarts or failures.
    private String temperatureSegment=java.util.UUID.randomUUID().toString(),temperatureDevice;

    DreoSynchronizer(BreezeService service,Handler handler) {
        this.service=service;this.handler=handler;client=new DreoClient(new DreoAccountStore(service));
    }
    void start() { schedule(0); }
    /** Explicit voice refresh uses the same read-only request and freshness/correction policy. */
    boolean requestNow(Consumer<String> result) {
        if(!active()||inFlight||!service.visibleForBluetooth())return false;
        handler.removeCallbacks(poll);fetch(result);return true;
    }
    boolean active() { return !stopped&&!authenticationFailed; }
    void invalidate() {
        policy.changed(SystemClock.elapsedRealtime(),System.currentTimeMillis());applied=false;correctionNote="";
        service.changed();
    }
    void stop() { stopped=true;handler.removeCallbacks(poll);worker.shutdownNow(); }
    private void schedule(long delay) {
        handler.removeCallbacks(poll);
        if(active())handler.postDelayed(poll,delay);
    }
    private void fetch() { fetch(null); }
    private static void complete(Consumer<String> result,String message){if(result!=null)result.accept(message);}
    private void fetch(Consumer<String> requested) {
        if(!active()||!service.visibleForBluetooth())return;
        if(inFlight){schedule(POLL_MS);return;}
        inFlight=true;final long generation=policy.generation();
        lastAttemptWallTime=System.currentTimeMillis();requestCount++;
        service.recordDreoPollStart();
        worker.execute(()->{
            DreoReport response=null;String failure=null;boolean auth=false;
            try{response=client.fetch();}
            catch(DreoClient.AuthRequiredException error){failure="Dreo sign-in required";auth=true;}
            catch(IOException|RuntimeException error){failure="Dreo unavailable";}
            if(!stopped)saveTemperature(response);
            final DreoReport result=response;final String error=failure;final boolean authentication=auth;
            handler.post(()->{
                if(stopped){service.recordDreoCheck(result,false,"Synchronization stopped during request"+(error==null?"":"; "+error));complete(requested,"Fan sync stopped before confirmation.");return;}
                inFlight=false;
                lastResponseWallTime=System.currentTimeMillis();
                if(generation!=policy.generation()){service.recordDreoCheck(result,false,"Local state changed during request"+(error==null?"":"; "+error));complete(requested,"Not synced · fan state changed during the Dreo check. Try again.");schedule(POLL_MS);return;}
                correctionNote="";
                if(error!=null){
                    service.recordDreoCheck(null,false,error);
                    applied=false;policy.unavailable(error);failures++;
                    if(authentication){authenticationFailed=true;service.dreoActivityChanged();}
                    else schedule(Math.min(MAX_RETRY_MS,POLL_MS*(1L<<Math.min(3,failures-1))));
                    complete(requested,"Not synced · "+error+".");service.changed();return;
                }
                failures=0;
                successfulReadCount++;
                if(versionsKey==null){versionsKey=versionKey(result.serial);policy.restoreVersions(service.getSharedPreferences("dreo-state-versions",0).getString(versionsKey,null));}
                boolean accepted=policy.accept(generation,result,SystemClock.elapsedRealtime());
                service.recordDreoCheck(result,accepted,policy.reason());
                if(policy.versionsChanged())service.getSharedPreferences("dreo-state-versions",0).edit().putString(versionsKey,policy.saveVersions()).apply();
                report=policy.observed();
                if(accepted) {
                    lastConfirmedAt=SystemClock.elapsedRealtime();
                    applied=false;correctionNote="Applying Dreo report · using estimate";
                    service.applyDreoReport(result,ok->{
                        if(stopped||generation!=policy.generation()){complete(requested,"Fan sync stopped before confirmation.");return;}
                        applied=ok;correctionNote=ok?"":"Controller correction waiting · using estimate";service.changed();
                        complete(requested,ok?"Fan and app synchronized from the Dreo report.":"Not synced · wait for an idle controller and try again.");
                    });
                }else {applied=false;complete(requested,"Not synced · "+policy.reason());}
                schedule(POLL_MS);
                service.changed();
            });
        });
    }
    private void saveTemperature(DreoReport reading){
        boolean available=reading!=null&&Double.isFinite(reading.temperatureC());
        String device=reading==null?temperatureDevice:reading.serial;
        if(device==null)return;
        if(!device.equals(temperatureDevice)){temperatureDevice=device;temperatureSegment=java.util.UUID.randomUUID().toString();}
        try(Store store=new Store(service)){
            // Store independently of speed correction eligibility: mode changes or an IR command
            // during the HTTP request must not discard an otherwise valid temperature observation.
            store.recordDreoTemperature(device,reading==null?System.currentTimeMillis():reading.receivedAtMs,
                    reading==null?0:reading.temperatureTimestamp,reading==null?null:reading.temperatureF,
                    DreoTemperature.SOURCE_UNIT,temperatureSegment,available);
        }catch(RuntimeException failure){
            // Temperature logging is observational and must not stop speed synchronization.
            android.util.Log.w("BreezeDreo","Could not save Dreo temperature observation",failure);
            available=false;
        }
        if(!available)temperatureSegment=java.util.UUID.randomUUID().toString();
    }
    String summary() {
        if(!policy.confirmed()||!applied||report==null||SystemClock.elapsedRealtime()-lastConfirmedAt>POLL_MS*2)
            return correctionNote.isEmpty()?policy.confirmed()?"Dreo confirmation expired · using estimate":policy.reason():correctionNote;
        return "Dreo reported "+(Boolean.FALSE.equals(report.power)?"Off":"speed "+report.speed)+" · checked "+Math.max(0,(SystemClock.elapsedRealtime()-lastConfirmedAt)/1000)+"s ago";
    }
    String status() {
        String value=summary();
        if(report!=null){
            value+="\nLast Dreo report: power "+(report.power==null?"unknown":report.power?"on":"off")+", speed "+(report.speed==null?"unknown":report.speed)+", mode "+mode(report.mode);
            value+="\nOscillation: "+oscillation(report.oscillation);
            value+="\nDreo value timestamps: speed "+timestamp(report.speedTimestamp)+", power "+timestamp(report.powerTimestamp)+", mode "+timestamp(report.modeTimestamp)+". Cloud values may be delayed.";
        }
        return value;
    }
    String diagnostics(){return "active="+active()+" inFlight="+inFlight+" authenticationRequired="+authenticationFailed+" requests="+requestCount+" successfulReads="+successfulReadCount+" lastAttemptEpochMs="+lastAttemptWallTime+" lastResponseEpochMs="+lastResponseWallTime+" generation="+policy.generation()+" pollIntervalMs="+POLL_MS+" status="+summary();}
    private static String mode(Integer mode) {
        return mode==null?"unknown":switch(mode){case 1->"Normal";case 2->"Natural";case 3->"Sleep";case 4->"Auto";case 5->"Turbo";default->"reported "+mode;};
    }
    private static String versionKey(String serial) {
        try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(serial.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte b:digest)key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return key.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String timestamp(long value) {return value<=0?"unavailable":java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new java.util.Date(value));}
    private static String oscillation(Integer value) {
        return value==null?"unavailable":switch(value){case 0->"off";case 1->"horizontal";case 2->"vertical";case 3->"horizontal and vertical";default->"reported "+value;};
    }
}
