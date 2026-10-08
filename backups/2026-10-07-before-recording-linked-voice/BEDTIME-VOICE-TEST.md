# Bedtime voice test

This is the first, deliberately limited voice prototype. It recognizes a spoken
command and speaks back. It does **not** save feeling entries, modify the database,
or update the graph. Those steps follow only after the locked-screen test works.

## Try it

1. Open Breeze while the phone is unlocked. On Home, tap **Start bedtime listening**.
2. Allow microphone access while using the app. Allow notifications to keep the
   visible listening status and Stop action available. Turn media volume up.
3. Wait for the spoken ready message. The offline model is prepared on first use.
4. Lock the phone with the power button and place it where it will stay at night.
5. Say **Breeze, warm seven** in one sentence. It should say
   **Heard warm seven. Test only.**
6. Try **Breeze, cold three**, then **Breeze, warm two**.
7. Say **Breeze, stop listening** to stop, or use the Stop button in Breeze or its
   notification. The listener also stops after 12 hours.

The wake phrase is **Breeze**, optionally **Hey Breeze**. This test does not use
Google Assistant or Gemini. It does not open or unlock the phone when responding.
Say the wake phrase and command together; pausing after the wake phrase is only a
readiness check, not a conversation session.

## Implementation and limits

- Android microphone foreground service started by the user while Breeze is visible.
- Vosk 0.3.75, JNA 5.18.1, and the small US English 0.15 model bundled in the APK.
- On-device recognition and an installed offline English Android text-to-speech voice.
- Raw microphone audio is processed in memory, not saved or uploaded by this feature.
- Speech output pauses recognition to avoid responding to its own confirmation.
- Partial CPU wake lock keeps the recognizer working with the screen off. Continuous
  microphone/CPU use consumes battery; this is not Google's low-power hotword engine.
- Explicit start is required after a reboot, force stop, or Android killing the
  process. There is no hidden background microphone restart.
- Calls or another app taking the microphone can interrupt recognition. The test
  must include the actual phone location and room noise; synthetic tests cannot
  prove that the microphone will hear the user from bed.
- Recognition is probabilistic. A strict parser rejects invalid recognized text,
  but cannot guarantee that a recognizer always hears the original words correctly.

Model origin, hashes, and license text: [VOICE-MODEL-LICENSES.md](VOICE-MODEL-LICENSES.md).

## Validation

JVM tests cover both states, numbers 1–10, two/to/too, optional level/log,
missing/out-of-range/negative values, wake phrase requirements, and stopping.
Device decoding tests use synthetic Microsoft David Desktop speech at 16 kHz,
16-bit mono; they contain no recorded user audio. Device results and the locked
microphone check are recorded after execution below.

### October 7, 2026 results

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

### Feature files

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
