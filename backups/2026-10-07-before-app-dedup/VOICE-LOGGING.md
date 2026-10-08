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

Setup shows the latest saved entry and voice controls. **View saved feelings**
opens History. Home shows the four sensor readings with **Start/Stop recording**
at the bottom, directly below humidity. The
feeling subplot uses a fixed −10 to +10 scale, with zero neutral, and the same
actual timestamp axis as the sensor graph. Pinch, pan, and selection are shared.
Entries can be edited and exported using **Export feeling entries**. Sensor CSVs
keep their original format.

History opens with **Recorded night** at the very top. Temperature scale numbers
and °F/°C are on the right; humidity and % RH are on the left. Tap a graph point
to update the date/time beside **Recorded night** in the header. Color-matched
readings sit directly below the graph, without a repeated heading or timestamp.
Values use 26sp type with 16sp labels. All six readouts fit together on the
verified Pixel 8 Pro viewport at its current font scale. The panel switches
from two columns to one when space or larger Android fonts require it. Missing
values stay as an em dash. Reset zoom and trace controls follow the readings;
the night chooser follows the graph card.

Tapping a feeling keeps the enabled thermometer and humidity readings in the
same large readout panel. The feeling is one compact row, such as **Feeling ·
Warm 7 / 10**. Measurements use the nearest actual sample to the feeling's time;
missing or distant readings remain blank rather than borrowing a value from
another part of the night.

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
broadcast refreshes History and the saved-feelings display in Setup. SQLite schema v4 preserves v3 manual entries
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
- Added `app/src/main/java/com/aaron/breeze/GraphReadingDetailsView.java`
- `app/src/androidTest/java/com/aaron/breeze/StoreTestRunner.java`
- `app/src/androidTest/java/com/aaron/breeze/FeelingEntryChecks.java`
- `app/src/androidTest/java/com/aaron/breeze/HistoryZoomChecks.java`
- Added `app/src/androidTest/java/com/aaron/breeze/VoiceFeelingChecks.java`
- Added `app/src/androidTest/java/com/aaron/breeze/SignedFeelingGraphChecks.java`
- `README.md`, `docs/BEDTIME-VOICE-TEST.md` (historical notice), this guide, and validation evidence in `docs/voice-logging-validation/`

Source and the pre-upgrade phone database are preserved in
`backups/2026-10-07-before-live-feeling-logging/`.

## Installed build and validation — October 7, 2026

The production logging, combined feeling/readings, and right-temperature-axis build was installed on the connected Pixel 8 Pro using
`adb install -r`; no uninstall or data reset was used. The user does not need to
rebuild or reinstall it. APK: `app/out-voice-logging/outputs/apk/debug/app-debug.apk`.
SHA-256: `90d3eb777bccc1a44c3638a7b298930fe99331a0e3a8bd28e3dfc600967a4fe4`.

- Build and 38 JVM tests passed, including strict command parsing. Lint: zero
  errors, 24 warnings in existing warning categories.
- [Phone storage and real asynchronous writer checks](voice-logging-validation/voiceLogging-final.txt)
  passed migration, current device timestamps, original timezone, canonical signs,
  constraints, durable pending entries, late binding, duplicate retries, intentional
  repeats, reopen persistence, explicit recovery, and same/different-controller
  reconnect handling. These checks use isolated temporary databases.
- [Existing manual entry checks](voice-logging-validation/feelingEntries-final.txt),
  [signed graph rendering and native reading details](voice-logging-validation/signedFeelings-readable.txt),
  [History gestures](voice-logging-validation/historyZoom-readable.txt), and
  [BLE recovery](voice-logging-validation/bleRecovery.txt) passed.
- The signed subplot was rendered and visually inspected using fabricated data:
  [graph rendering](voice-logging-validation/signed-feeling-graph.png). Unequal
  ten-minute and thirty-minute gaps occupy proportional widths.
- The large [native reading panel](voice-logging-validation/graph-reading-details.png)
  was rendered on the phone with fabricated data at 320dp width and visually
  inspected. The graph callback supplied all six enabled series with appropriate
  temperature and humidity units. The phone locked before the final visible
  History tap check; this image is an off-screen native render, not a live night.
- [Actual phone database verification](voice-logging-validation/database-preservation.json)
  reports schema v4 and integrity `ok`. All 38,509 original samples, 26 weather rows,
  and original session identities/details remain intact; previously completed
  session endpoints and cursors did not regress. Normal controller downloads added
  106 samples and delivered one ending and one new recording start. No fabricated
  feelings were inserted into the real phone database.
- The installed app automatically joined the active controller recording. With
  the screen off, [the live listener](voice-logging-validation/live-locked-listener.txt)
  remained ready, with capture unblocked and the partial wake lock held. Android
  reported [screen-off/dozing power state](voice-logging-validation/live-power-state.txt).
  The contemporaneous [lock-state snapshot](voice-logging-validation/live-lock-state.txt)
  reported `deviceLocked=0`, so this run does not establish secure-lock recognition.
  The listener was left running silently. No synthetic speech was played aloud.
- After installing the graph readability update, the [listener remained ready](voice-logging-validation/live-readable-listener.txt)
  with recording active, capture unblocked, and its wake lock held while Android
  reported [deviceLocked=1](voice-logging-validation/live-readable-lock-state.txt).
  This confirms listener readiness while securely locked, not a newly spoken
  command or an overnight endurance run.

The initial late-binding check exposed an SQLite numeric-affinity bug; explicit
integer casts fixed it and the final test passed. The microphone's earlier real
locked-screen phrase recognition is documented in the historical prototype notes;
this upgrade additionally verifies the complete parser-to-persistent-writer path
without creating pretend entries in the user's recordings. Overnight endurance
and the user's actual bedside microphone distance have not been measured.

### Temperature axis on the right

The subsequent axis update moves temperature ticks and °F/°C to the right and
humidity ticks and % RH to the left, preserving plot geometry and auto-range.
Visible guidance and the accessibility description match. Build and lint passed
(zero errors, 23 warnings). The new APK at the path above has SHA-256
`291fe96d486e6ab3c92007f07ef388c0d882d05621d36f3ed1448eb0965cdc17`.
Installed successfully on the unlocked Pixel 8 Pro with `adb install -r`, and
the [installed APK hash](voice-logging-validation/right-axis-installed-hash.txt)
matches. [Graph checks](voice-logging-validation/signedFeelings-right-axis.txt)
and [History gestures](voice-logging-validation/historyZoom-right-axis.txt)
passed on the installed update. The [native graph render](voice-logging-validation/right-axis-graph.png)
was visually checked: temperature units and numbers are on the right, humidity
on the left. Breeze was reopened and connected normally. The controller reported
[recording=false](voice-logging-validation/right-axis-controller-state.txt), so
the silent listener correctly remained idle until the next recording.

### Compact feeling with thermometer readings

Selecting a feeling now keeps the enabled sensor readouts visible and uses one
18sp feeling row. Large signed-rating cells and the separate neutral explanation
were removed from this readout. Sensor values use the nearest actual finite
sample within 35 seconds for D0/D1, 65 seconds for room/humidity, and 3,700
seconds for hourly outdoor data. Missing values remain an em dash.

Build, 38 JVM tests, lint, [combined-readout checks](voice-logging-validation/combinedFeelingReadings.txt),
and [History gesture checks](voice-logging-validation/combinedFeelingHistoryZoom.txt)
passed. The update was installed with data preserved, and its
[installed hash](voice-logging-validation/combined-installed-hash.txt) matches.
The [native fixture](voice-logging-validation/feeling-with-readings.png) shows all
six readings with one feeling row. A real saved feeling was then tapped in the
[phone History screen](voice-logging-validation/combined-live-selected.png):
Warm 1 / 10 remained one row alongside D0 98.6 F, D1 96.8 F, room 73.0 F and
humidity 53.4%. Outdoor series were already unchecked in the user preferences.
No fabricated entries were added to the phone database.

### Home recording access

The voice instructions, listener controls, latest saved feeling and saved-feelings
shortcut now live in Setup. Home retains its original four sensor cards and
Start/Stop recording at the bottom, directly below humidity. The button location
and behavior were preserved. Build, 38 JVM tests and lint passed.
The [installed APK hash](voice-logging-validation/home-clean-installed-hash.txt)
matches the build. Live phone UI inspection confirms the full
[Home screen and recording button](voice-logging-validation/home-clean.png)
fit without the old voice card, and the [relocated card is in Setup](voice-logging-validation/voice-in-setup.png).
The phone was left on Home; no recording was started or stopped during checks.
The existing voice lifecycle check now navigates to Setup before finding Pause
listening; it was compiled, but its live-microphone test was not rerun for this layout change.

### Compact History header and all six readings

The selected date/time now shares the Recorded night header row. The duplicate
Selected reading heading, lower timestamp, canvas hints and blank footer space
were removed. The graph container is 346dp instead of 430dp, while removing
84dp of reserved footer preserves the actual plotted sensor/feeling area and
touch coordinates. The readout grid comes first, with 26sp values, tighter
vertical spacing, and any compact feeling row after all six values.

Build, 38 JVM tests, lint, [graph/readout checks](voice-logging-validation/compactReadings.txt)
and [History gestures](voice-logging-validation/compactHistoryZoom.txt) passed.
The update was installed and its [APK hash verified](voice-logging-validation/compact-installed-hash.txt).
[Live screenshot](voice-logging-validation/compact-selected.png) and
[viewport bounds](voice-logging-validation/compact-viewport-verification.json)
confirm the selected time is aligned with the header, all six values are fully
visible above navigation without scrolling, and there is no duplicate Selected
heading. A saved-feeling selection also retained all six readings, with the
compact feeling row below them. No recordings were created or stopped.
