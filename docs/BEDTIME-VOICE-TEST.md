# Bedtime voice test

Historical prototype notes. The current app saves real entries; see
[Silent voice logging](VOICE-LOGGING.md) for current behavior and setup.

This limited prototype recognizes spoken commands silently and displays the result.
There are no spoken startup announcements or replies. It does **not** save feeling
entries, modify the database, or update the graph.

## Try it

1. Open Breeze while the phone is unlocked and tap **Start recording**. Voice now
   starts automatically with normal recordings and probe-test recordings. If the
   controller is already recording, opening Breeze automatically joins it.
2. Allow microphone access while using the app. Allow notifications to keep the
   visible listening status and Stop action available.
3. The status changes to **Listening silently for Breeze** when ready. The offline
   model is prepared on first use. Breeze produces no spoken audio.
4. Lock the phone with the power button and place it where it will stay at night.
5. Say **Breeze, warm seven** in one sentence. The Home screen later shows
   **Heard warm seven. Test only.** There is no audible reply.
6. Try **Breeze, cold three**, then **Breeze, warm two**.
7. Stopping the sensor recording automatically stops listening. To pause just
   voice for the current recording, say **Breeze, stop listening**, tap **Pause
   listening**, or use the notification's Stop action. **Resume listening** restores
   voice during that recording. A new recording enables voice again automatically.

The wake phrase is **Breeze**, optionally **Hey Breeze**. This test does not use
Google Assistant or Gemini. It does not open or unlock the phone when responding.
Say the wake phrase and command together; pausing after the wake phrase is only a
readiness check, not a conversation session.

## Implementation and limits

- Android microphone foreground service prepared by Start recording while Breeze
  is visible, with recognition gated until the controller confirms recording.
  Failed starts release the microphone; pending starts expire after 30 seconds.
- Vosk 0.3.75, JNA 5.18.1, and the small US English 0.15 model bundled in the APK.
- On-device recognition. No text-to-speech engine, installed voice, or media volume is needed.
- Raw microphone audio is processed in memory, not saved or uploaded by this feature.
- Partial CPU wake lock keeps the recognizer working with the screen off. Continuous
  microphone/CPU use consumes battery; this is not Google's low-power hotword engine.
- Voice follows confirmed controller state. It stays running through a Bluetooth
  dropout because the controller can keep recording, and stops when live telemetry
  confirms idle. An interrupted connection alone is not treated as a recording stop.
- There is no fixed 12-hour limit on a recording-linked listener. The CPU wake lock
  is renewed with a timeout while the recording-linked service is running.
- After a reboot, force stop, or process death, open Breeze while unlocked so
  Android allows microphone startup again. Reconnecting Bluetooth alone while the
  app is in the background cannot create a microphone foreground service. Startup
  or audio failures show a Resume listening action rather than a repeated restart.
- Calls or another app taking the microphone can interrupt recognition. The test
  must include the actual phone location and room noise; synthetic tests cannot
  prove that the microphone will hear the user from bed.
- Recognition is probabilistic. A strict parser rejects invalid recognized text,
  but cannot guarantee that a recognizer always hears the original words correctly.

Model origin, hashes, and license text: [VOICE-MODEL-LICENSES.md](VOICE-MODEL-LICENSES.md).

## Validation

### October 7, 2026 silent recording-linked build

- Built and installed `app/out-recording-voice/outputs/apk/debug/app-debug.apk` on
  the Pixel 8 Pro. All text-to-speech code and its manifest discovery query were
  removed. Startup, recognized commands, and stopping produce no spoken output.
- 38 JVM tests passed; lint reported 0 errors and 23 warnings.
- The real Activity, foreground microphone and native recognizer passed automatic
  start, pending-state gating, confirmed-idle shutdown with the Activity destroyed,
  active-recording rejoin, pause persistence, and rapid stop/start replacement.
  Controller telemetry was simulated so these checks did not stop the ongoing
  physical recording. [Silent lifecycle result](bedtime-voice-validation/recording-voice-silent-result.txt).
- BLE recovery regression checks passed using fake transport. The production
  voice code does not write feeling entries. [BLE regression result](bedtime-voice-validation/recording-voice-ble-regression.txt).
- Added `RecordingVoiceController.java`, `RecordingVoiceState.java`,
  `RecordingVoiceStateTest.java`, and `RecordingVoiceChecks.java`. Recording start
  controls prearm the service; live controller telemetry governs its lifetime.
- Earlier speaking-build retries were interrupted by audio focus loss and then
  deliberately stopped at the user's request. The passing result above belongs
  to the final silent build.

JVM tests cover both states, numbers 1–10, two/to/too, optional level/log,
missing/out-of-range/negative values, wake phrase requirements, and stopping.
Device decoding tests use synthetic Microsoft David Desktop speech at 16 kHz,
16-bit mono; they contain no recorded user audio. Device results and the locked
microphone check are recorded after execution below.

### October 7, 2026 initial manual prototype results

These historical results describe the earlier speaking prototype. All spoken output
was subsequently removed at the user's request; the current build is silent.

- Built and installed `app/out-bedtime-voice/outputs/apk/debug/app-debug.apk`
  on the connected Pixel 8 Pro (Android SDK 37). No uninstall or data reset.
- 24 JVM tests passed (10 new command-parser tests, 14 existing protocol tests).
- Lint: 0 errors, 23 warnings, including existing programmatic UI localization warnings.
- Actual Android Vosk decoding passed all 15 synthetic fixtures: warm/cold 1 and
  10, warm 7, cold 3, warm/cold 2, optional level, invalid 0/11/hot, wake, stop,
  and unrelated speech. These tests do not exercise the microphone.
- The live microphone then recognized **warm seven** at 04:24:44 and **cold two**
  at 04:24:55. Both events recorded `locked=true interactive=false`, followed by
  spoken-response completion and return to listening. This is real microphone
  evidence, distinct from the synthetic decoder checks. The user's distance from
  the phone and overnight endurance were not measured.
- Further live commands **warm ten** (04:25:18) and **cold one** (04:25:27) also
  succeeded while locked/noninteractive. At 04:25:47 the service stopped, released
  the microphone/recognizer, and released its wake lock. It was left stopped.
- Android reported keyguard showing/input restricted, active microphone capture
  with `silenced:false`, and the partial CPU wake lock. No screen wake/unlock was
  requested by the listener. Evidence: [locked voice log](bedtime-voice-validation/locked-voice-log.txt),
  [lock state](bedtime-voice-validation/lock-state.txt),
  [power state](bedtime-voice-validation/power-state.txt),
  [microphone state](bedtime-voice-validation/microphone-state.txt).
- Database integrity remained `ok`, schema stayed v3, and every pre-test session,
  sample, weather, and feeling row remained unchanged. The existing BLE download
  added 40 samples and advanced its cursor during testing. Feeling rows stayed at
  zero. The prototype never opens the database.
- Source and installed APK/database backups are under
  `backups/2026-10-07-before-voice-logging/`.

### Feature files (initial prototype)

Modified:

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/aaron/breeze/MainActivity.java`
- `app/src/androidTest/java/com/aaron/breeze/StoreTestRunner.java`
- `README.md`

Added:

- `app/src/main/java/com/aaron/breeze/BedtimeVoiceCommand.java`
- `app/src/main/java/com/aaron/breeze/BedtimeVoiceService.java`
- `app/src/main/java/com/aaron/breeze/OfflineVoiceModel.java`
- `app/src/test/java/com/aaron/breeze/BedtimeVoiceCommandTest.java`
- `app/src/androidTest/java/com/aaron/breeze/BedtimeVoiceChecks.java`
- `app/src/androidTest/assets/bedtime-voice/`: `warm7.wav`, `cold3.wav`, `warm1.wav`,
  `warm10.wav`, `cold1.wav`, `cold10.wav`, `warm2.wav`, `cold2.wav`, `level2.wav`,
  `invalid11.wav`, `invalid0.wav`, `invalidHot.wav`, `stop.wav`, `wake.wav`,
  `background.wav` (synthetic test fixtures, not included in the main APK).
- `app/src/main/assets/voice-model/`: exact model/license inventory is listed in
  [VOICE-MODEL-LICENSES.md](VOICE-MODEL-LICENSES.md).
- `docs/BEDTIME-VOICE-TEST.md`, `docs/VOICE-MODEL-LICENSES.md`, and the four evidence
  text files linked above.

Build artifacts use `app/out-bedtime-voice/`; task downloads and the post-test
database verification copy are retained under `app/voice-test-downloads/`.

## Official references

- [Android microphone foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
- [Android microphone permission lifetime](https://developer.android.com/training/permissions/requesting#one-time)
- [Vosk Android implementation](https://github.com/alphacep/vosk-android-demo)
- [Vosk models and licenses](https://alphacephei.com/vosk/models)
