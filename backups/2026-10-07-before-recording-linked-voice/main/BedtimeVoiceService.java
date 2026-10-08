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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Explicitly started bedside microphone test. It never reads or writes Breeze records. */
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
    private static final long SESSION_MS = 12L * 60 * 60 * 1000;
    private static final long SPEECH_TAIL_MS = 700;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger resetGeneration = new AtomicInteger();
    private volatile boolean stopping, speechGate = true;
    private volatile long listenAfter = Long.MAX_VALUE;
    private volatile boolean inputBlocked;
    private Thread worker;
    private TextToSpeech speech;
    private boolean speechReady, captureReady, announced, foreground;
    private AudioManager audio;
    private AudioFocusRequest speechFocus;
    private PowerManager.WakeLock wakeLock;
    private String utteranceId;
    private int speechSequence;
    private boolean stopAfterSpeech;
    private long sessionDeadline;

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        lastStopReason = "";
        lastStatus = status;
        audio = getSystemService(AudioManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Bedtime voice test",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible while Breeze listens locally for bedside test commands");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null || stopping) return START_NOT_STICKY;
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
            wakeLock.acquire(SESSION_MS + 10000);
            sessionDeadline = SystemClock.elapsedRealtime() + SESSION_MS;
            main.postDelayed(sessionTimeout, SESSION_MS);
            main.postDelayed(startupTimeout, 60000);
            speech = new TextToSpeech(this, result -> main.post(() -> initializeSpeech(result)));
            worker = new Thread(this::captureLoop, "Breeze-bedtime-voice");
            worker.start();
            Log.i(TAG, "START offline bedside test; maximum session 12 hours; no logging");
            changed();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start bedside voice test", error);
            fail("Could not start the microphone test. Open Breeze and start it again.");
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final Runnable sessionTimeout = () -> {
        lastStopReason = "Bedtime voice test stopped after 12 hours.";
        Log.i(TAG, "STOP session timeout");
        stopSelf();
    };

    private final Runnable startupTimeout = () ->
            fail("Voice preparation timed out. Open Breeze and try again.");

    private final Runnable speechTimeout = () ->
            fail("Spoken confirmation timed out. Open Breeze and try again.");

    private void initializeSpeech(int result) {
        if (stopping) return;
        if (result != TextToSpeech.SUCCESS || speech == null) {
            fail("Android speech output could not start. Check the phone's text-to-speech settings.");
            return;
        }
        Set<Voice> voices = speech.getVoices();
        Voice selected = null;
        if (voices != null) {
            for (Voice voice : voices) {
                if (voice.isNetworkConnectionRequired() || !"en".equals(voice.getLocale().getLanguage())) continue;
                if (voice.getFeatures() != null && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (selected == null || ("US".equals(voice.getLocale().getCountry())
                        && !"US".equals(selected.getLocale().getCountry()))
                        || (voice.getLocale().equals(selected.getLocale()) && voice.getQuality() > selected.getQuality()))
                    selected = voice;
            }
        }
        if (selected == null || speech.setVoice(selected) != TextToSpeech.SUCCESS) {
            fail("Install an offline English voice in Android text-to-speech settings, then start this test again.");
            return;
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        speech.setAudioAttributes(attributes);
        speech.setSpeechRate(0.93f);
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> finishSpeech(id)); }
            @Override public void onError(String id) { main.post(() -> speechFailed(id)); }
            @Override public void onError(String id, int errorCode) { main.post(() -> speechFailed(id)); }
            @Override public void onStop(String id, boolean interrupted) {
                main.post(() -> { if (!stopping && id.equals(utteranceId)) speechFailed(id); });
            }
        });
        speechFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        if (utteranceId != null) {
                            // A lost spoken acknowledgment must not be reported as a successful test.
                            fail("Another app interrupted spoken feedback. Open Breeze to restart the voice test.");
                        }
                    }
                }, main).build();
        speechReady = true;
        maybeAnnounceReady();
    }

    private void maybeAnnounceReady() {
        if (stopping || announced || !speechReady || !captureReady) return;
        announced = true;
        main.removeCallbacks(startupTimeout);
        speak("Bedtime voice test is ready. Lock your phone, then say Breeze warm seven. "
                + "I will repeat it. Test only. Nothing is saved.", false);
    }

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
            main.post(() -> { if (!stopping) { captureReady = true; maybeAnnounceReady(); } });
            short[] samples = new short[1600];
            int seenGeneration = resetGeneration.get();
            boolean wasGated = true;
            long nextInputCheck = 0;
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                long now = SystemClock.elapsedRealtime();
                if (now >= sessionDeadline) {
                    main.post(sessionTimeout);
                    break;
                }
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
                boolean gated = speechGate || inputBlocked || now < listenAfter;
                int generation = resetGeneration.get();
                if (generation != seenGeneration || gated != wasGated) {
                    recognizer.reset();
                    seenGeneration = generation;
                }
                if (wasGated && !gated) main.post(this::listeningReady);
                wasGated = gated;
                // Read and discard while TTS speaks and during its tail, preventing self-triggering.
                if (count > 0 && !gated && recognizer.acceptWaveForm(samples, count)) {
                    JSONObject result = new JSONObject(recognizer.getResult());
                    BedtimeVoiceCommand command = BedtimeVoiceCommand.parse(result.optString("text", ""));
                    if (command.kind != BedtimeVoiceCommand.Kind.IGNORE && confidentWake(result)) {
                        speechGate = true;
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
            if (utteranceId != null) fail("The microphone or spoken response was interrupted. Restart the test from Breeze.");
        }
    }

    private void listeningReady() {
        if (stopping || speechGate || inputBlocked || SystemClock.elapsedRealtime() < listenAfter) return;
        ready = true;
        state("Listening for Breeze • test only");
        Log.i(TAG, "READY offline microphone listening");
    }

    private void handleCommand(BedtimeVoiceCommand command) {
        if (stopping) return;
        ready = false;
        switch (command.kind) {
            case FEELING:
                String phrase = command.state.toLowerCase(Locale.US) + " "
                        + BedtimeVoiceCommand.intensityWord(command.intensity);
                lastHeard = "Heard " + phrase + ". Test only.";
                KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                PowerManager power = getSystemService(PowerManager.class);
                Log.i(TAG, "RECOGNIZED " + phrase + " locked=" + keyguard.isKeyguardLocked()
                        + " interactive=" + power.isInteractive() + " testOnly=true");
                speak(lastHeard, false);
                break;
            case STOP:
                Log.i(TAG, "STOP spoken request");
                speak("Bedtime voice test stopped.", true);
                break;
            case READY:
                speak("I'm listening. Say Breeze, warm or cold, and a number from one to ten.", false);
                break;
            case INVALID:
                speak("I didn't get that. Say Breeze, warm or cold, and a number from one to ten.", false);
                break;
            default:
                releaseSpeechGate();
                break;
        }
    }

    private void speak(String words, boolean stopWhenDone) {
        if (stopping) return;
        ready = false;
        speechGate = true;
        listenAfter = Long.MAX_VALUE;
        resetGeneration.incrementAndGet();
        if (!speechReady || speech == null || speechFocus == null
                || audio.requestAudioFocus(speechFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail("Spoken feedback is unavailable or another app is using audio. Restart the test from Breeze.");
            return;
        }
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            fail("Turn up media volume so you can hear Breeze, then start the voice test again.");
            return;
        }
        stopAfterSpeech = stopWhenDone;
        utteranceId = "breeze-bedtime-" + (++speechSequence);
        state(stopWhenDone ? "Stopping bedtime voice test…" : "Speaking • test only");
        main.removeCallbacks(speechTimeout);
        main.postDelayed(speechTimeout, 30000);
        Bundle parameters = new Bundle();
        parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        if (speech.speak(words, TextToSpeech.QUEUE_FLUSH, parameters, utteranceId) != TextToSpeech.SUCCESS)
            fail("Android could not play spoken feedback. Restart the test from Breeze.");
    }

    private void finishSpeech(String id) {
        if (stopping || !id.equals(utteranceId)) return;
        utteranceId = null;
        main.removeCallbacks(speechTimeout);
        audio.abandonAudioFocusRequest(speechFocus);
        if (stopAfterSpeech) { stopSelf(); return; }
        releaseSpeechGate();
    }

    private void speechFailed(String id) {
        if (!stopping && id.equals(utteranceId))
            fail("Spoken feedback was interrupted. Restart the voice test from Breeze.");
    }

    private void releaseSpeechGate() {
        listenAfter = SystemClock.elapsedRealtime() + SPEECH_TAIL_MS;
        resetGeneration.incrementAndGet();
        speechGate = false;
    }

    private void fail(String message) {
        if (stopping) return;
        lastStopReason = message;
        ready = false;
        speechGate = true;
        state(message);
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

    @Override public void onDestroy() {
        stopping = true;
        ready = false;
        speechGate = true;
        main.removeCallbacksAndMessages(null);
        if (worker != null) worker.interrupt();
        if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
        if (speechFocus != null && audio != null) audio.abandonAudioFocusRequest(speechFocus);
        if (worker != null) {
            try { worker.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (current == this) current = null;
        lastStatus = lastStopReason.isEmpty() ? "Not listening" : lastStopReason;
        changed();
        Log.i(TAG, "STOP service destroyed; wake lock and speech released");
        super.onDestroy();
    }
}
