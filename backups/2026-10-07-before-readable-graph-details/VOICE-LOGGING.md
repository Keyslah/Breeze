# Silent voice logging

Start recording in Breeze while the phone is unlocked. Listening starts automatically.
You can then lock the phone and say **Breeze, warm seven** or **Breeze, cold three**.
Say the whole command together. **Hey Breeze** and **Breeze, log warm level two**
also work. There are no spoken announcements, confirmations, or errors.

Warm and cold accept intensities 1–10. Each recognized valid command saves the
phone's current timestamp, original local offset/timezone, canonical WARM/COLD,
intensity, and signed value: warm positive, cold negative. Invalid commands do
not save an entry or clamp the value. Two/to/too are accepted only in the intensity
position. Separate intentional repetitions are separate entries; a retry of the
same accepted utterance cannot duplicate its database row.

Home shows the latest saved entry. **View saved feelings** opens History. The
feeling subplot uses a fixed −10 to +10 scale, with zero neutral, and the same
actual timestamp axis as the sensor graph. Pinch, pan, and selection are shared.
Entries can be edited and exported using **Export feeling entries**. Sensor CSVs
keep their original format.

## Recording and Bluetooth

Voice stops when the controller confirms recording ended. **Breeze, stop listening**
or **Pause listening** pauses only the listener for that recording. **Resume listening**
starts it again; the next recording enables it automatically.

Entries save immediately on the phone, including during a Bluetooth dropout.
Their graph placement may wait for the next controller download, normally about
30 seconds. Breeze matches an exact controller archive and record, then checks
the recorded time interval. It never assigns entries to whichever historical
night happens to be selected. Pending entries appear under **Saved feelings
awaiting a night**. If controller identity or recording boundaries cannot be
proved, the saved entry remains available there for an explicit night choice.

## Voice mechanism and Android setup

This uses bundled, offline **Vosk** recognition in Android's microphone foreground
service, rather than an Assistant/Gemini invocation. The wake phrase is **Breeze**,
not **Hey Google**. No Tasker, Firebase, Keep, Sheets, server, cloud database, or
audio upload is involved. Raw microphone audio is not saved by this feature.

Allow Breeze microphone access while using the app and notifications for visible
listening status. Start recording, or open Breeze during an existing recording,
while unlocked. Once started, listening continues with Breeze in the background
and the screen locked. After force stop, reboot, or process death, reopen Breeze
while unlocked; this app cannot start a new microphone service from the background.
See [Android microphone foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).

No media volume or installed text-to-speech voice is needed. Other apps or calls
can temporarily take the microphone. Continuous microphone recognition uses
battery; recognition and hearing distance depend on the phone and room noise.

## Implementation

`Store.logTemperatureFeeling(state, intensity)` is the reusable current-time entry
point. The voice overload accepts the immutable capture timestamp, timezone,
utterance UUID and verified controller archive/record anchor. A single background
writer commits the entry before reporting it saved. A package-scoped change
broadcast refreshes History and Home. SQLite schema v4 preserves v3 manual entries
and all existing sensor, weather, session and cursor data. Legacy Hot maps to
WARM; old entries retain their exact timestamp and mark their reconstructed UTC
display as derived because the original timezone was not stored.

Files changed for production logging:

- `app/src/main/java/com/aaron/breeze/Store.java`
- `app/src/main/java/com/aaron/breeze/BedtimeVoiceService.java`
- `app/src/main/java/com/aaron/breeze/RecordingVoiceController.java`
- `app/src/main/java/com/aaron/breeze/BreezeService.java`
- `app/src/main/java/com/aaron/breeze/BedtimeVoiceCommand.java` (documentation)
- `app/src/main/java/com/aaron/breeze/MainActivity.java`
- `app/src/main/java/com/aaron/breeze/FeelingEntriesView.java`
- `app/src/main/java/com/aaron/breeze/GraphView.java`
- `app/src/androidTest/java/com/aaron/breeze/StoreTestRunner.java`
- `app/src/androidTest/java/com/aaron/breeze/FeelingEntryChecks.java`
- `app/src/androidTest/java/com/aaron/breeze/HistoryZoomChecks.java`
- Added `app/src/androidTest/java/com/aaron/breeze/VoiceFeelingChecks.java`
- Added `app/src/androidTest/java/com/aaron/breeze/SignedFeelingGraphChecks.java`
- `README.md`, `docs/BEDTIME-VOICE-TEST.md` (historical notice), this guide, and validation evidence in `docs/voice-logging-validation/`

Source and the pre-upgrade phone database are preserved in
`backups/2026-10-07-before-live-feeling-logging/`.

## Installed build and validation — October 7, 2026

The production logging build was installed on the connected Pixel 8 Pro using
`adb install -r`; no uninstall or data reset was used. The user does not need to
rebuild or reinstall it. APK: `app/out-voice-logging/outputs/apk/debug/app-debug.apk`.
SHA-256: `1583fb82df2303bb30ecbbd407e6a56cc3edc854bdc0b8e1751635520045bb8e`.

- Build and 38 JVM tests passed, including strict command parsing. Lint: zero
  errors, 27 warnings in existing warning categories.
- [Phone storage and real asynchronous writer checks](voice-logging-validation/voiceLogging-final.txt)
  passed migration, current device timestamps, original timezone, canonical signs,
  constraints, durable pending entries, late binding, duplicate retries, intentional
  repeats, reopen persistence, explicit recovery, and same/different-controller
  reconnect handling. These checks use isolated temporary databases.
- [Existing manual entry checks](voice-logging-validation/feelingEntries-final.txt),
  [signed graph rendering](voice-logging-validation/signedFeelings.txt),
  [History gestures](voice-logging-validation/historyZoom.txt), and
  [BLE recovery](voice-logging-validation/bleRecovery.txt) passed.
- The signed subplot was rendered and visually inspected using fabricated data:
  [graph rendering](voice-logging-validation/signed-feeling-graph.png). Unequal
  ten-minute and thirty-minute gaps occupy proportional widths.
- [Actual phone database verification](voice-logging-validation/database-preservation.json)
  reports schema v4 and integrity `ok`. All 38,509 original samples, 26 weather rows,
  and original session identities/details remain intact; previously completed
  session endpoints and cursors did not regress. Normal controller downloads added
  106 samples and delivered one ending and one new recording start. No fabricated
  feelings were inserted into the real phone database.
- The installed app automatically joined the active controller recording. After
  locking the phone, [the live listener](voice-logging-validation/live-locked-listener.txt)
  remained ready, with capture unblocked and the partial wake lock held. Android
  reported [locked state](voice-logging-validation/live-lock-state.txt) and
  [screen-off/dozing power state](voice-logging-validation/live-power-state.txt).
  The listener was left running silently. No synthetic speech was played aloud.

The initial late-binding check exposed an SQLite numeric-affinity bug; explicit
integer casts fixed it and the final test passed. The microphone's earlier real
locked-screen phrase recognition is documented in the historical prototype notes;
this upgrade additionally verifies the complete parser-to-persistent-writer path
without creating pretend entries in the user's recordings. Overnight endurance
and the user's actual bedside microphone distance have not been measured.
