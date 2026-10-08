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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Recording-linked bedside microphone test. It never reads or writes Breeze records. */
public final class BedtimeVoiceService extends Service {
    public static final String UPDATE = "com.aaron.breeze.VOICE_UPDATE";
    public static final String ACTION_STOP = "com.aaron.breeze.STOP_BEDTIME_VOICE";
    public static volatile BedtimeVoiceService current;
    public static volatile String lastStopReason = "";
    public static volatile String lastStatus = "Not listening";
    public volatile String status = "Preparing bedside voice test…";
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
    private volatile boolean stopping, shutdownRequested, commandPending;
    private volatile boolean inputBlocked, recordingActive, captureReady;
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
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Bedtime voice test",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible while Breeze listens locally for bedside test commands");
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            RecordingVoiceController.suppress(this);
            return START_NOT_STICKY;
        }
        if (intent == null || !RecordingVoiceController.shouldRunService()) {
            shutdownRequested = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null || stopping) return START_NOT_STICKY;
        RecordingVoiceController.listenerStarted();
        recordingStateChanged();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is needed. Start the test again from Breeze.");
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            foreground = true;
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Breeze:BedtimeVoice");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_TIMEOUT_MS);
            main.postDelayed(renewWakeLock, WAKE_RENEW_MS);
            main.postDelayed(startupTimeout, 60000);
            worker = new Thread(this::captureLoop, "Breeze-bedtime-voice");
            worker.start();
            Log.i(TAG, "START recording-linked offline bedside test; no logging");
            changed();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start bedside voice test", error);
            fail("Could not start the microphone test. Open Breeze and start it again.");
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final Runnable renewWakeLock = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            if (!RecordingVoiceController.shouldRunService()) { recordingStateChanged(); return; }
            wakeLock.acquire(WAKE_TIMEOUT_MS);
            main.postDelayed(this, WAKE_RENEW_MS);
        }
    };

    /** Invoked only on the main thread, from live telemetry or the visible Activity. */
    void recordingStateChanged() {
        if (isShuttingDown()) return;
        if (!RecordingVoiceController.shouldRunService()) {
            shutdownRequested = true;
            recordingActive = false;
            commandPending = true;
            ready = false;
            resetGeneration.incrementAndGet();
            stopSelf();
            return;
        }
        boolean active = RecordingVoiceController.canRecognize();
        if (recordingActive != active) {
            recordingActive = active;
            resetGeneration.incrementAndGet();
        }
        if (!active) {
            ready = false;
            if (!"Preparing voice • waiting for recording".equals(status))
                state("Preparing voice • waiting for recording");
        }
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
            recognizer = new Recognizer(model, SAMPLE_RATE, BedtimeVoiceCommand.grammarJson());
            recognizer.setWords(true);
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Microphone format unavailable");
            recorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum * 2, SAMPLE_RATE)).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Microphone could not initialize");
            if (stopping || Thread.currentThread().isInterrupted()) return;
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Microphone could not record");
            main.post(() -> {
                if (!isShuttingDown()) {
                    captureReady = true;
                    main.removeCallbacks(startupTimeout);
                    listeningReady();
                }
            });
            short[] samples = new short[1600];
            int seenGeneration = resetGeneration.get();
            boolean wasGated = true;
            long nextInputCheck = 0;
            while (!stopping && !Thread.currentThread().isInterrupted()) {
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
                        main.post(() -> inputAvailabilityChanged(blocked));
                    }
                    nextInputCheck = now + 250;
                }
                int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("Microphone read failed: " + count);
                boolean gated = !recordingActive || commandPending || inputBlocked;
                int generation = resetGeneration.get();
                if (generation != seenGeneration || gated != wasGated) {
                    recognizer.reset();
                    seenGeneration = generation;
                }
                if (wasGated && !gated) main.post(this::listeningReady);
                wasGated = gated;
                // Discard audio until recording is confirmed, or while another command is pending.
                if (count > 0 && !gated && recognizer.acceptWaveForm(samples, count)) {
                    JSONObject result = new JSONObject(recognizer.getResult());
                    BedtimeVoiceCommand command = BedtimeVoiceCommand.parse(result.optString("text", ""));
                    if (command.kind != BedtimeVoiceCommand.Kind.IGNORE && confidentWake(result)) {
                        commandPending = true;
                        resetGeneration.incrementAndGet();
                        main.post(() -> handleCommand(command));
                    }
                }
                if (count == 0) Thread.sleep(20);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError error) {
            if (!stopping) {
                Log.e(TAG, "Bedtime microphone test failed", error);
                main.post(() -> fail("The local voice test could not listen. Open Breeze to restart it."));
            }
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) { }
                recorder.release();
            }
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
            Log.i(TAG, "RELEASE microphone and local recognizer");
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
        if (isShuttingDown() || !captureReady || !recordingActive || inputBlocked) return;
        if (ready) return;
        ready = true;
        state("Listening silently for Breeze • test only");
        Log.i(TAG, "READY offline microphone listening");
    }

    private void handleCommand(BedtimeVoiceCommand command) {
        if (isShuttingDown() || !recordingActive) { commandPending = false; return; }
        switch (command.kind) {
            case FEELING:
                String phrase = command.state.toLowerCase(Locale.US) + " "
                        + BedtimeVoiceCommand.intensityWord(command.intensity);
                lastHeard = "Heard " + phrase + ". Test only.";
                KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                PowerManager power = getSystemService(PowerManager.class);
                Log.i(TAG, "RECOGNIZED " + phrase + " locked=" + keyguard.isKeyguardLocked()
                        + " interactive=" + power.isInteractive() + " testOnly=true");
                break;
            case STOP:
                Log.i(TAG, "STOP spoken request");
                RecordingVoiceController.suppress(this);
                return;
            case READY:
                lastHeard = "Ready. Say Breeze, warm or cold, and a number from one to ten.";
                break;
            case INVALID:
                lastHeard = "Not understood. Say Breeze, warm or cold, and a number from one to ten.";
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
        ready = false;
        commandPending = true;
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
                .setContentTitle("Breeze bedtime voice test")
                .setContentText(status).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Stop listening", stop).build()).build();
    }

    private void state(String message) {
        status = message;
        lastStatus = message;
        changed();
        if (foreground && !stopping
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private void changed() { sendBroadcast(new Intent(UPDATE).setPackage(getPackageName())); }

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        out.println("ready=" + ready + " recordingActive=" + recordingActive + " status=" + status);
        out.println("commandPending=" + commandPending + " inputBlocked=" + inputBlocked
                + " shutdownRequested=" + shutdownRequested
                + " wakeLock=" + (wakeLock != null && wakeLock.isHeld()));
        out.println("lifecycle=" + RecordingVoiceController.describe());
    }

    @Override public void onDestroy() {
        stopping = true;
        ready = false;
        commandPending = true;
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
