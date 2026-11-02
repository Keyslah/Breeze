package com.aaron.breeze;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Offline fan voice control, with feeling logging only during recording. Feedback is text-only. */
public final class BedtimeVoiceService extends Service {
    public static final String UPDATE = "com.aaron.breeze.VOICE_UPDATE";
    public static final String ACTION_STOP = "com.aaron.breeze.STOP_BEDTIME_VOICE";
    public static volatile BedtimeVoiceService current;
    public static volatile String lastStopReason = "";
    public static volatile String lastStatus = "Not listening";
    public volatile String status = "Preparing bedside listening…";
    public volatile String lastHeard = "";
    public volatile boolean ready;

    private static final String TAG = "BreezeVoice";
    private static final String CHANNEL = "bedtime_voice";
    private static final int NOTIFICATION_ID = 72;
    private static final int SAMPLE_RATE = 16000;
    private static final long WAKE_TIMEOUT_MS = 15L * 60 * 1000;
    private static final long WAKE_RENEW_MS = 10L * 60 * 1000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger resetGeneration = new AtomicInteger();
    private final AtomicInteger captureGeneration = new AtomicInteger();
    private boolean captureAllowed;
    private final Object captureSignal = new Object();
    private final Object recorderLock = new Object();
    private AudioRecord activeRecorder;
    private volatile boolean stopping, shutdownRequested, commandPending;
    private volatile boolean inputBlocked, recognitionAllowed, feelingRecordingActive, captureReady;
    private Thread worker;
    private boolean foreground;
    private AudioManager audio;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        lastStopReason = "";
        lastStatus = status;
        audio = getSystemService(AudioManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Voice commands",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible while Breeze listens locally for fan commands and recording feelings");
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            RecordingVoiceController.suppress(this);
            return START_NOT_STICKY;
        }
        if (intent == null || RecordingVoiceController.isListeningDisabled(this)
                || !RecordingVoiceController.shouldRunService()
                || (worker == null && !RecordingVoiceController.shouldCapture())) {
            shutdownRequested = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null || stopping) return START_NOT_STICKY;
        RecordingVoiceController.listenerStarted();
        recordingStateChanged();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is needed. Resume listening from Breeze.");
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            foreground = true;
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Breeze:BedtimeVoice");
            wakeLock.setReferenceCounted(false);
            updateCaptureWakeLock();
            main.postDelayed(startupTimeout, 60000);
            worker = new Thread(this::captureLoop, "Breeze-bedtime-voice");
            worker.start();
            Log.i(TAG, "START offline silent voice commands");
            changed();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start bedside listening", error);
            fail("Could not start the microphone. Open Breeze and resume listening.");
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final Runnable renewWakeLock = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            updateCaptureWakeLock();
        }
    };
    private void updateCaptureWakeLock() {
        main.removeCallbacks(renewWakeLock);
        if (wakeLock == null) return;
        if (isShuttingDown() || !RecordingVoiceController.shouldCapture()) {
            if (wakeLock.isHeld()) wakeLock.release();
            return;
        }
        wakeLock.acquire(WAKE_TIMEOUT_MS);
        main.postDelayed(renewWakeLock, WAKE_RENEW_MS);
    }
    private void notifyCaptureState() {
        // Stop capture before releasing the wake lock; the worker owns final AudioRecord release.
        if(isShuttingDown()||!RecordingVoiceController.shouldCapture())synchronized(recorderLock){
            if(activeRecorder!=null)try{activeRecorder.stop();}catch(RuntimeException ignored){}
        }
        synchronized (captureSignal) { captureSignal.notifyAll(); }
        updateCaptureWakeLock();
        BreezeService controller = BreezeService.current;
        if (controller != null) controller.voiceActivityChanged();
    }

    /** Invoked only on the main thread, from live telemetry or the visible Activity. */
    void recordingStateChanged() {
        if (isShuttingDown()) return;
        if (!RecordingVoiceController.shouldRunService()) {
            shutdownRequested = true;
            recognitionAllowed = false;
            feelingRecordingActive = false;
            commandPending = true;
            ready = false;
            resetGeneration.incrementAndGet();
            notifyCaptureState();
            stopSelf();
            return;
        }
        boolean active = RecordingVoiceController.canRecognize();
        boolean feelings = RecordingVoiceController.canLogFeeling();
        boolean capture = RecordingVoiceController.shouldCapture();
        if(captureAllowed!=capture){captureAllowed=capture;captureGeneration.incrementAndGet();resetGeneration.incrementAndGet();}
        if (recognitionAllowed != active || feelingRecordingActive != feelings) {
            recognitionAllowed = active;
            feelingRecordingActive = feelings;
            resetGeneration.incrementAndGet();
        }
        if (!capture) {
            ready = false;
            captureReady = false;
            main.removeCallbacks(startupTimeout);
            state(RecordingVoiceController.status());
        } else if (!active) {
            ready = false;
            state(RecordingVoiceController.status());
        }
        notifyCaptureState();
        listeningReady();
    }

    boolean isShuttingDown() { return stopping || shutdownRequested; }

    private final Runnable startupTimeout = () ->
            fail("Voice preparation timed out. Open Breeze and try again.");

    @android.annotation.SuppressLint("MissingPermission")
    private void captureLoop() {
        Model model = null;
        Recognizer recognizer = null;
        AudioRecord recorder = null;
        try {
            model = new Model(OfflineVoiceModel.prepare(this).getAbsolutePath());
            if (stopping || Thread.currentThread().isInterrupted()) return;
            short[] samples = new short[1600];
            int seenGeneration = resetGeneration.get();
            boolean wasGated = true;
            int recorderGeneration=-1;
            long nextInputCheck = 0;
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                if(recorder!=null&&recorderGeneration!=captureGeneration.get()){
                    releaseRecorder(recorder);recorder=null;
                    if(recognizer!=null){recognizer.close();recognizer=null;}wasGated=true;
                }
                if (!RecordingVoiceController.shouldCapture()) {
                    if (recorder != null) { releaseRecorder(recorder); recorder = null; }
                    if(recognizer!=null){recognizer.close();recognizer=null;}wasGated = true;
                    synchronized (captureSignal) {
                        while (!isShuttingDown() && !RecordingVoiceController.shouldCapture()) captureSignal.wait();
                    }
                    if (isShuttingDown()) break;
                    continue;
                }
                if(recognizer==null){recognizer=new Recognizer(model,SAMPLE_RATE,BedtimeVoiceCommand.grammarJson());recognizer.setWords(true);}
                if (recorder == null) {
                    recorderGeneration=captureGeneration.get();
                    recorder = createRecorder();
                    synchronized(recorderLock){
                        if(!isShuttingDown()&&RecordingVoiceController.shouldCapture()&&recorderGeneration==captureGeneration.get()){
                            activeRecorder=recorder;recorder.startRecording();
                            if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                                throw new IllegalStateException("Microphone could not record");
                        }
                    }
                    if(!RecordingVoiceController.shouldCapture()||isShuttingDown()||recorderGeneration!=captureGeneration.get()){releaseRecorder(recorder);recorder=null;continue;}
                    recognizer.reset();seenGeneration = resetGeneration.get();nextInputCheck = 0;
                    final int startedGeneration=recorderGeneration;
                    main.post(() -> {
                        if (!isShuttingDown() && RecordingVoiceController.shouldCapture()&&startedGeneration==captureGeneration.get()) {
                            captureReady = true;main.removeCallbacks(startupTimeout);listeningReady();
                        }
                    });
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextInputCheck) {
                    AudioRecordingConfiguration configuration = recorder.getActiveRecordingConfiguration();
                    int mode = audio.getMode();
                    boolean blocked = (configuration != null && configuration.isClientSilenced())
                            || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                            || mode == AudioManager.MODE_RINGTONE;
                    if (blocked != inputBlocked) {
                        inputBlocked = blocked;
                        resetGeneration.incrementAndGet();
                        final int availabilityGeneration=recorderGeneration;
                        main.post(() -> {if(availabilityGeneration==captureGeneration.get())inputAvailabilityChanged(blocked);});
                    }
                    nextInputCheck = now + 250;
                }
                int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                if(!RecordingVoiceController.shouldCapture()||isShuttingDown()||recorderGeneration!=captureGeneration.get())continue;
                if (count < 0) throw new IllegalStateException("Microphone read failed: " + count);
                boolean gated = !recognitionAllowed || !RecordingVoiceController.canRecognize()
                        || commandPending || inputBlocked;
                int generation = resetGeneration.get();
                if (generation != seenGeneration || gated != wasGated) {
                    recognizer.reset();
                    seenGeneration = generation;
                    count = 0; // A buffer captured across an eligibility change cannot begin a new command.
                }
                if (wasGated && !gated) main.post(this::listeningReady);
                wasGated = gated;
                // Fan commands do not require recording; power/disable and pending-command gates still apply.
                if (count > 0 && !gated && recognizer.acceptWaveForm(samples, count)) {
                    JSONObject result = new JSONObject(recognizer.getResult());
                    BedtimeVoiceCommand command = BedtimeVoiceCommand.parse(result.optString("text", ""));
                    if (command.kind != BedtimeVoiceCommand.Kind.IGNORE && confidentWake(result)
                            && generation == resetGeneration.get() && !isShuttingDown()
                            && recognitionAllowed && RecordingVoiceController.canRecognize()) {
                        // Freeze identity, clock and timezone at acceptance, before asynchronous persistence.
                        long receivedAt = System.currentTimeMillis();
                        String receivedZoneId = ZoneId.systemDefault().getId();
                        RecordingVoiceController.RecordingContext recording = RecordingVoiceController.snapshotForCommand();
                        boolean logFeeling = command.kind == BedtimeVoiceCommand.Kind.FEELING
                                && RecordingVoiceController.canLogFeeling();
                        if(!resetGeneration.compareAndSet(generation,generation+1))continue;
                        commandPending = true;
                        int acceptedGeneration = generation+1;
                        main.post(() -> {
                            if (acceptedGeneration != resetGeneration.get()) { commandPending = false; return; }
                            handleCommand(command, logFeeling);
                        });
                        if (logFeeling) {
                            String token = UUID.randomUUID().toString();
                            RecordingVoiceController.saveFeeling(this, token, receivedAt, receivedZoneId,
                                    recording, command.state, command.intensity, saved -> {
                                        if (current == this && !isShuttingDown()) {
                                            lastHeard = saved;
                                            changed();
                                        }
                                    });
                        }
                    }
                }
                if (count == 0) Thread.sleep(20);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError error) {
            if (!stopping) {
                Log.e(TAG, "Bedtime microphone failed", error);
                main.post(() -> fail("The local listener stopped. Open Breeze to resume listening."));
            }
        } finally {
            if (recorder != null) releaseRecorder(recorder);
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
            Log.i(TAG, "RELEASE microphone and local recognizer");
        }
    }
    private AudioRecord createRecorder() {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Microphone permission is needed to resume listening.");
        int minimum=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IllegalStateException("Microphone format unavailable");
        AudioRecord recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum*2,SAMPLE_RATE)).build();
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){recorder.release();throw new IllegalStateException("Microphone could not initialize");}
        return recorder;
    }
    private void releaseRecorder(AudioRecord recorder) {
        synchronized(recorderLock){
            if(activeRecorder==recorder)activeRecorder=null;
            try { recorder.stop(); } catch (RuntimeException ignored) { }
            recorder.release();
        }
    }

    private static boolean confidentWake(JSONObject result) {
        JSONArray words = result.optJSONArray("result");
        if (words == null || words.length() == 0) return false;
        double total = 0;
        boolean wake = false;
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.optJSONObject(i);
            if (word == null) return false;
            double confidence = word.optDouble("conf", 0);
            total += confidence;
            if ("breeze".equals(word.optString("word")) && confidence >= 0.80) wake = true;
        }
        return wake && total / words.length() >= 0.75;
    }

    private void inputAvailabilityChanged(boolean blocked) {
        if (stopping) return;
        if (blocked) {
            ready = false;
            state("Microphone is busy or muted. Listening paused.");
        } else listeningReady();
    }

    private void listeningReady() {
        if (isShuttingDown() || !captureReady || !recognitionAllowed
                || !RecordingVoiceController.canRecognize() || inputBlocked) return;
        if (ready) return;
        ready = true;
        state("Listening silently for Breeze");
        Log.i(TAG, "READY offline microphone listening");
    }

    private void handleCommand(BedtimeVoiceCommand command, boolean logFeeling) {
        if (isShuttingDown() || !recognitionAllowed || !RecordingVoiceController.canRecognize()) {
            commandPending = false;
            return;
        }
        switch (command.kind) {
            case FEELING:
                if (!logFeeling) {
                    lastHeard = "Start recording to save warm or cold feelings. Fan voice commands are available.";
                    break;
                }
                String phrase = command.state.toLowerCase(Locale.US) + " "
                        + BedtimeVoiceCommand.intensityWord(command.intensity);
                lastHeard = "Saving " + phrase + "…";
                KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                PowerManager power = getSystemService(PowerManager.class);
                Log.i(TAG, "RECOGNIZED " + phrase + " locked=" + keyguard.isKeyguardLocked()
                        + " interactive=" + power.isInteractive());
                break;
            case STOP:
                Log.i(TAG, "STOP spoken request");
                RecordingVoiceController.suppress(this);
                return;
            case POWER: case LOGIC: case OSCILLATE: case FIX_FAN:
                BreezeService controller=BreezeService.current;
                lastHeard=command.kind==BedtimeVoiceCommand.Kind.FIX_FAN?"Checking Dreo to synchronize the fan and app…":"Sending fan command…";
                if(controller==null)lastHeard="Not sent · connect the controller in Breeze first.";
                else controller.voiceFanCommand(command.kind,message->{if(current==this&&!isShuttingDown()){lastHeard=message;changed();}});
                break;
            case READY:
                lastHeard = "Ready. Say Breeze power, logic, oscillate, or fix fan. During recording, say warm/cold with a number from one to ten.";
                break;
            case INVALID:
                lastHeard = "Not understood. Say Breeze power, logic, oscillate, or fix fan. Warm/cold logging requires recording.";
                break;
            default:
                break;
        }
        resetGeneration.incrementAndGet();
        commandPending = false;
        changed();
    }

    private void fail(String message) {
        if (isShuttingDown()) return;
        lastStopReason = message;
        shutdownRequested = true;
        ready = false;
        commandPending = true;
        resetGeneration.incrementAndGet();
        notifyCaptureState();
        state(message);
        RecordingVoiceController.listenerFailed(this, message);
        Log.w(TAG, "STOP " + message);
        stopSelf();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, NOTIFICATION_ID,
                new Intent(this, BedtimeVoiceService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_breeze)
                .setContentTitle("Breeze voice commands")
                .setContentText(status).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Stop listening", stop).build()).build();
    }

    private void state(String message) {
        // UI reconciliation can repeat the same paused state. Broadcasting it again
        // feeds a status -> UI refresh -> status loop and floods notifications.
        if (java.util.Objects.equals(status, message)) return;
        status = message;
        lastStatus = message;
        changed();
        if (foreground && !stopping
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private void changed() { sendBroadcast(new Intent(UPDATE).setPackage(getPackageName())); }

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        out.println("ready=" + ready + " captureReady=" + captureReady + " recognitionAllowed=" + recognitionAllowed
                + " feelingRecordingActive=" + feelingRecordingActive + " status=" + status);
        out.println("commandPending=" + commandPending + " inputBlocked=" + inputBlocked
                + " shutdownRequested=" + shutdownRequested
                + " workerAlive=" + (worker != null && worker.isAlive())
                + " wakeLock=" + (wakeLock != null && wakeLock.isHeld()));
        out.println("externallyPowered=" + RecordingVoiceController.isExternallyPowered()
                + " powerBlocked=" + RecordingVoiceController.isPowerBlocked());
        out.println("lifecycle=" + RecordingVoiceController.describe());
    }

    @Override public void onDestroy() {
        stopping = true;
        ready = false;
        commandPending = true;
        resetGeneration.incrementAndGet();
        notifyCaptureState();
        main.removeCallbacksAndMessages(null);
        if (worker != null) worker.interrupt();
        if (worker != null) {
            try { worker.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (current == this) current = null;
        if (!shutdownRequested && RecordingVoiceController.shouldRunService())
            RecordingVoiceController.listenerFailed(this, "Listening stopped. Open Breeze and tap Resume listening.");
        RecordingVoiceController.listenerStopped();
        lastStatus = RecordingVoiceController.status();
        changed();
        Log.i(TAG, "STOP service destroyed; wake lock released");
        super.onDestroy();
    }
}
