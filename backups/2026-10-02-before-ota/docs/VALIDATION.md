# History selection and combined graph — October 2, 2026

- Inspected the live History spinner: the phone had one saved night, and its one-item dropdown overlaid the selected row, making opening it look unchanged. Replaced it with an explicit Choose recorded night button and a titled single-choice dialog with night count, date, reading count and end time.
- Temperature and humidity now share one time axis. Temperature uses the left scale; humidity uses the independent right 0–100% scale. Six colored checkboxes independently toggle D0, D1, SHT40 temperature, outdoor temperature, indoor humidity and outdoor humidity. Selection is saved in the existing app preferences. Outdoor traces stay dashed, missing data stays missing, and temperature scaling follows visible temperature traces.
- Updated source: MainActivity.java and GraphView.java. Prior copies: `backups/2026-10-02-history-032209`. No database, firmware or recording logic changed.
- Android build and 14 existing JVM tests passed; lint 0 errors / 13 warnings. Installed updated APK on the Pixel 8 Pro with existing data retained. APK SHA-256 `42F485EB3ADCE15D464F6EEEE366D764E55863FD6FC8CC6D839A18AE2D60EABC`.
- Actual phone verification: opened chooser, selected October 1 12:57 AM night with 1024 readings and end 9:28 AM, confirmed the graph loads. Unchecked D0, navigated Home → History, and confirmed only D0 stayed unchecked; visually verified its line disappeared while SHT40 and humidity remained. Independently toggled humidity and verified its trace disappears/reappears. Restored all six checkboxes and left the saved night open.
- Screenshots inspected: `docs/screenshots/history-night-list.png`, `history-d0-hidden.png`, `history-humidity-hidden.png`, `history-combined-final.png`. This older night predates D1 and has no downloaded outdoor values, so those checked traces correctly have no data to draw.

# Ten-second sampling, graph colors and Home recording — October 2, 2026

- Both D0/D1 thermistors and SHT40 temperature/humidity now sample every 10 seconds. Outdoor model records remain hourly. No contact-rejection or automatic fan rules were added.
- Home has Start recording directly below humidity, switching to Stop recording from controller state. The existing Setup control remains. Colors and matching legends: D0 green, D1 coral, SHT40 gold, outdoor temperature dashed blue; SHT40 humidity purple, outdoor humidity dashed cyan.
- Source snapshot: `backups/2026-10-02-ten-second-031528`.
- Android APK/test build and all 14 JVM tests passed. Lint: 0 errors, 13 warnings. Updated Pixel 8 Pro using install -r, preserving history/settings. Fake-controller UI instrumentation passed, including Home button placement, Start/Stop routing, and the existing raw-reading/manual-IR checks. No physical recording or IR transmission was started by those tests.
- Actual firmware-function test passed 10-second deadlines for D0, D1 and SHT40, no early reads, recording Start/Stop, sensor reads after Stop, timer rollover and marker-shifted deadlines.
- Firmware compiled at 901,838 bytes flash / 21,164 bytes static RAM. Reidentified the same ESP32-C6FH4 on COM3 (base MAC 10:BD:A3:9F:9F:84), uploaded and verified hashes, automatic reboot. Existing log/NVS partitions were not formatted.
- Live serial samples arrived at host offsets 6.070 s, 16.073 s, 26.074 s: intervals 10.003 s and 10.001 s. Latest sample D0 23.06 C, D1 22.87 C, SHT40 21.97 C / 63.4% RH. D1 is now valid. Serial evidence: `docs/ten-second-serial.txt`.
- Visually inspected actual phone Home and existing-session temperature/humidity graphs: `docs/screenshots/ten-second-home.png`, `ten-second-temperature.png`, `ten-second-humidity.png`. Home shows live D0/D1/SHT40/RH and the requested button. Historical selected session predates D1 and has no downloaded outdoor records, so those traces correctly remain absent; their matching colored legends are visible.
- APK SHA-256 `212BE9D98F408130CC7BD1AF5D196561D6CB6A29F5D341CA35C81F2BDB22015B`; firmware SHA-256 `2FA1E815CF4A6975A21F5766E511991720500F60EB344BDED931BDFE9978B2BA`.

# Firmware upload and live boot — October 2, 2026

- User connected the controller. Identified COM3 as ESP32-C6FH4 revision 0.2, base MAC `10:BD:A3:9F:9F:84`.
- Saved all 4 MiB to `backups/2026-10-02-before-dual-probe-flash/controller.bin`; SHA-256 `E850D2422BD76CDCC9CB04A43E9A9C6EFAA07FE1C78270B5B7CB730299AC422B`. Compared the existing partition table with the new compiled table: exact match.
- Uploaded the previously built `firmware/out-dreo` image; esptool verified each written hash. No erase-all or filesystem format was used; NVS and recording partitions were outside the uploaded ranges. The board reset automatically.
- USB Serial confirms `Breeze IR-v7 ready`, IRremoteESP8266 2.9.0, no learned commands, unknown estimated speed and Auto OFF. Initial samples: D0 22.78 °C, SHT40 22.67 °C, RH 63.0%; D1 `nan` (no valid reading). This establishes running firmware and live sensor output, not independent calibration.
- No IR transmission or recording was started. Physical D1 wiring/contact test, remote learning/replay and visible phone UI remain pending; the phone was still locked.
- Serial evidence: `backups/2026-10-02-before-dual-probe-flash/after-upload-serial.txt`.

# Raw two-probe test and manual IR — October 2, 2026

- User correction governs this revision: collect data first. No sudden-drop/contact/recovery rules or automatic temperature algorithm exist. Auto enable returns rules-pending. Both raw D0/D1 values remain visible, graphed and exported. SHT40 wiring/calibration is preserved.
- Android app and test APK build passed; 14 JVM tests passed; lint has 0 errors and 13 warnings. Build output remains `app/out-sensors`.
- Actual `FanRemote.cpp` host tests passed: decoded/raw capture and sending, NVS reload, rate limiting, manual targets, unknown endpoint behavior, external-remote invalidation, timeout/cancel, simulated save failure, disconnect cancellation, clear, and absence of temperature-driven transmissions. These use IR/NVS spies, not hardware.
- Actual sensor/session firmware-function tests passed: two probe conversion and availability, recording guards and idempotence, original 30-second body/60-second SHT cadence, live readings after Stop, rollover and staggered sample deadlines.
- ESP32-C6 build passed with isolated Arduino-ESP32 3.3.12 and IRremoteESP8266 2.9.0: 901,838 bytes flash and 21,164 bytes static RAM. Binary SHA-256: `AAA088EC63F2889202A27BF2760D09F089B45B19B20F39BB50199148867DB642`.
- App and test APK installed on verified Pixel 8 Pro (`192.168.1.83:5555`) with `install -r`; no app data was cleared. APK SHA-256: `6BA79E5A0354675047E8B6A1564AEF1004746A41AB36A56DB0F5F9AA3211F85A`.
- Isolated on-phone storage instrumentation passed: version-1 database migration retaining existing sample/session/cursor; new D1 and user marker storage/export; mixed old/new records; durable cursor, duplicate/gap handling, restart recovery, CRC rejection, UTC/session boundaries, CSV and weather storage; live ZIP lookup plus 14 hourly weather records.
- The first storage run exposed an overly specific test expectation for the SQLite numeric string (`24.0` versus `24`). The assertion now parses the exported numeric field and checks the marker independently; the rerun passed.
- Visible UI instrumentation did not pass: the phone was locked/dozing, so the launch visibility assertion could not succeed. The runner now detects the locked phone and reports SKIPPED. Visual layout, fake-controller UI interactions, actual BLE version-7 telemetry and physical probe behavior remain unverified.
- No XIAO USB port was present (only COM1/COM5/COM6); this firmware was not uploaded. No physical IR send was initiated. Real reception, persistence across a physical reboot, carrier/frame behavior, fan response, single-step +/- behavior and transistor wiring still need the ordered hardware test in SETUP.md.
- Source before this work is under `backups/2026-10-02-dreo-ir`. Provisional temperature-policy files were moved to the Recycle Bin. Motor/servo code remains absent from active source.

The entries below are historical; current results above supersede them.

# Sensor-only update — September 30, 2026

- Replaced the opening controls/graph screen with Body temp, Fan temp and Humidity in a dark granite-green theme. Connection and recording are in Setup; History remains available.
- Removed motor/servo/joystick execution and all actuator commands from active firmware and app. Body stays on D0; the existing SHT40 uses D4/D5. Obsolete source and tests went to the Recycle Bin; pre-change source is preserved in backups/2026-09-30-sensors.
- ESP32-C6 build passed: 835,092 bytes program storage, 20,348 bytes static RAM. Confirmed ESP32-C6FH4, base MAC 10:BD:A3:9F:9F:84 on COM3, uploaded and verified flash hashes. No log partition format or erase-all was performed.
- Android build and all 7 protocol tests passed. Lint: 0 errors, 10 warnings. Installed the app on Pixel 8 Pro without clearing app data.
- On-phone fake-controller checks passed: three readings visible at launch, missing/stale handling, °F/°C switching, recording routing, old-firmware gating and absence of motor controls.
- Isolated phone database checks passed: durable cursor, duplicate suppression, gaps, restart persistence, CRC, timestamps, session boundaries, sensor CSV, stored weather and live ZIP/weather lookup.
- Actual firmware function host tests passed: thermistor conversion and faults, start/stop idempotence, clock/storage guards, 30-second body and 60-second fan cadence, sensing after Stop, timer rollover and probe recovery.
- Earlier validation below is historical and describes superseded motor builds.
# Validation — September 28, 2026

## Passed

- Android debug APK built with Android Gradle Plugin 9.0.1 / Gradle 9.2.1 / JDK 21, Java source target 17; compile/target SDK 36, minimum SDK 33.
- Android lint: **0 errors**, 16 warnings (English-only UI text, draw allocations, SDK/tool version recommendations, backup configuration).
- **9 JVM protocol tests**: physical dial mapping, reverse mounting, invalid configuration, absent sensor representation, signed temperature, large uptime, CRC corruption rejection and acknowledgement cursor encoding.
- Native host C++ tests against the firmware's actual `Control.h`: B3950 conversion, disconnected/shorted probe limits, heating thresholds, cooling hysteresis, optional room/RH contribution, missing optional sensor fallback and dial order validation.
- Arduino-ESP32 **3.3.7**, board **XIAO_ESP32C6**: firmware compiles, **847,098 bytes flash (64%)**, **20,556 bytes static RAM (6%)**.
- Installed package `com.aaron.breeze` on USB-connected **Pixel 6**, Android 16 (API 36), serial `19151FDF6005K7`. Pixel 8 Pro was not modified.
- Pixel SQLite instrumentation in a temporary isolated database: durable cursor, restart recovery, duplicate suppression, rejected gaps, CRC failure retention, exact UTC timestamps, session end markers, weather persistence, CSV values.
- Pixel live networking test: ZIP **53214** lookup succeeded; Open-Meteo returned **14 hourly weather records** for a 12-hour test window. Temporary test data was not added to the app's real history.
- The app launched successfully and its main dashboard was captured and visually inspected (`screenshots/pixel6-live.png`).

## Not yet verified on the physical controller

No XIAO ESP32C6 USB device was present. Only COM1 and Bluetooth serial ports COM5/COM6 were listed. Nothing was flashed, and no servo was driven.

Still required with the assembled hardware: thermistor divider measurement and calibration; SHT40 readings; actual joystick center/direction/button; BLE connection, offline overnight recording, interruption/resume and acknowledged flash cleanup; deliberate power-loss recovery during append and generation rotation; servo voltage/current/pulse range and four measured dial positions. These hardware checks are separate from compilation and phone database tests.

The phone locked during further interactive UI checks; additional graph/calibration screen inspection depends on unlocking it. No lock or security setting was changed.

## Controls and sampling revision — September 28, 2026

- Updated and reinstalled the debug app on the USB Pixel 6 (`19151FDF6005K7`). Existing app data was retained.
- Connected dashboard uses a test controller to verify the connection box hides, Off/High/Medium/Low remain visible, and Start/Stop dispatch the correct commands without a confirmation dialog. Screenshot: `screenshots/pixel6-connected-test.png` (simulated controller state, not a real BLE connection).
- Visually inspected the compact body/fan temperature row and Rules screen; verified the Save button opens the name prompt. Screenshots: `screenshots/pixel6-controls.png`, `screenshots/pixel6-rules.png`.
- Pixel instrumentation passed named rule save/load with isolated preferences, all temperature fields restored, calibration preserved, and top Save/Load visibility. No test profiles or records were written to the user's settings/history.
- Android build and all 9 protocol tests passed; lint reports 0 errors and 20 warnings (existing categories: hardcoded English text, drawing allocations, SDK/version and backup guidance).
- Host tests compiled the actual firmware Start, Stop, sensor reading and scheduling functions with hardware spies. Passed immediate rule application on Start; unchanged speed, angle and PWM on Stop for Off/Low/Medium/High; storage/clock/calibration guards; 30-second body and 60-second fan sampling; timer rollover; staggered deadlines; no recording or automatic changes after Stop.
- Existing native control-math tests still pass.
- Matching XIAO ESP32C6 firmware compiled successfully. No physical controller was connected or flashed. App actions 7/8 require this firmware; physical BLE, sensor cadence and servo behavior remain unverified.
- Source, firmware, tests and docs before this revision are preserved in `backups/2026-09-28-controls`.

## SG90 motor test and first upload — September 30, 2026

- Live USB identification: COM3, ESP32-C6FH4 revision v0.2, base MAC `10:BD:A3:9F:9F:84`. User confirmed SG90 and signal on D7; installed board variant verifies D7 = GPIO17.
- Backed up all 4 MiB before upload: `backups/2026-09-30-motor-test/esp32c6-before-upload.bin`, SHA-256 `EF8259EC4C35F8093108A1FBF50AFCAE82074570B192EA82E7BF78A743333572`. Source/docs/tests were also backed up there.
- Firmware compiled for `esp32:esp32:XIAO_ESP32C6`: 847,962 bytes flash, 20,572 bytes static RAM. Uploaded to COM3 with the Arduino CLI and verified flash checksums; no erase-all or filesystem formatting. Build artifacts: `firmware/out-motor-test`.
- Updated Android app installed on the Pixel 8 Pro (`192.168.1.83:5555`) at the user's correction. Pixel 6 had received the initial update before that correction.
- Actual BLE advertising discovered `Breeze Fan` at `10:BD:A3:9F:9F:86`; Pixel 8 connected and received the motor-test extension, showing `Connected` and `Motor released · set the starting position`. No physical movement was initiated by the agent.
- New Motor test screen provides explicit initial positioning, +/- buttons, a 2–100 µs step slider, commanded position, and Release motor. Changing the slider sends no movement. Tests work without sensors, recording, filesystem formatting or servo calibration.
- Host test `tests/motor_test.py` compiles the actual firmware movement functions with PWM spies. Passed D7 mapping, explicit initialization, +/- microsteps, range guards, recording guard, release/reinitialize, and hardware-error reporting. Prior session/Start/Stop/cadence host tests also pass.
- Android protocol tests: 13 passed. Pixel 8 instrumentation passed fake-controller UI checks including slider-no-motion, direction/amount routing, initialization, range, recording and release guards. These automated checks do not establish mechanical direction or travel.
- Fixed a real connection UI issue: scanning while already connected could overwrite status with `No controller found`. A ready connection is now kept; a single discovered controller connects automatically; ready status takes precedence over scan status.
- Physical servo direction, deadband and fan-knob travel are for the user to observe. Firmware intentionally leaves PWM off at boot; the first position command can be larger than a microstep because SG90 provides no physical position readback to the ESP.

## Expanded motor test range — September 30, 2026

- User explicitly approved motor-test pulse endpoints 500–2400 µs and doubled largest step from 100 to 200 µs. Smallest/default step remains 2 µs. No position slider was added.
- Final clarified boundary behavior: retain the selected slider amount. A request beyond an endpoint moves only to that endpoint; the next opposite-direction tap uses the original full selected step. Android disables a direction only when already at its endpoint.
- Firmware motor telemetry is version 2. Android requires it and accepts 500–2400 µs. Its approximate angle display uses 500=0°, 1500=90°, 2400=180°. The normal saved fan-calibration mapping is unchanged.
- Actual firmware function host tests passed expanded bounds, endpoint saturation, full-size reverse step, initialization/recording/PWM-error guards. Android unit tests passed 16/16; build and lint passed (0 errors, 20 warnings). Fake-controller instrumentation includes assertions that endpoint motion does not alter slider progress or the next reverse-step amount.
- Full physical shaft range is not inferred from the displayed angle; the user observes the actual SG90 travel. Test-range approval is not a measurement of mechanical endpoints.
- Final expanded-range firmware (847,996 bytes flash) uploaded to COM3 and checksum verified. Final APK installed on Pixel 8; fake-controller instrumentation passed endpoint/unchanged-slider tests. Reopened Motor test and connected to actual version-2 telemetry; no motor movement was initiated by the agent.

## Start motor testing at either end — September 30, 2026

- Replaced forced-center initialization with direct **Start at 0°** (500 µs, action 12) and **Start at 180°** (2400 µs, action 13) controls. No center command or confirmation dialog is inserted by these buttons. Firmware remains motionless at boot.
- Motor telemetry extension is now v3, required by the updated phone app. Existing range, selected step size, boundary saturation, recording guards and saved fan calibration are preserved.
- Host tests execute the actual firmware functions and passed initialization from released PWM at both ends and full-size first steps away from each endpoint, with exactly one PWM write for initialization and no intermediate center target. Recording blocks either start action.
- Firmware build passed at 848,040 bytes flash / 20,572 bytes static RAM, then uploaded to COM3 with flash checksum verified. Android app/test APK build and 16 unit tests passed; lint 0 errors/20 warnings.
- Pixel 8 update installed. The expanded fake-controller instrumentation runner stalled before its endpoint checks and was stopped; do not count that device test as passed. The actual app was relaunched and connected for visual verification. No physical motor movement was initiated by the agent.

## Continuous-rotation motor test — September 30, 2026

- User confirmed replacing the positional SG90 with a three-wire continuous-rotation servo, keeping signal on D7. Added Direction A/B, speed 1–100%, timed turns 20–2000 ms, Stop, Release, and separately saved neutral trim 1400–1600 µs. No endpoint initialization or angle estimate applies to this motor.
- The ESP one-shot timer sends neutral independently of main-loop sensor/storage work. Disconnect stops driven motion without energizing released PWM, clears queued commands, and invalidates older connection generations. Duplicate accepted jog sequences do not repeat motion. This test build rejects all legacy positional/fan/Start commands and preserves their saved configuration.
- `tests/continuous_test.py` compiles actual firmware motion functions with timer/PWM spies. Passed timed and explicit stops, both full-speed outputs, retry suppression, no forced position, invalid packet/recording guards, stop trim, disconnected/stale-command rejection, PWM/timer failures, and rejection of positional controls. Retained positional motor/session regression suites also pass with continuous mode explicitly disabled in those harnesses.
- Final firmware compiled to 848,348 bytes flash and 20,604 bytes static RAM. Application binary SHA-256: `F3EE452521F29A3634067978C636186AE596CB91CF73381910D083E3850926E3`. Confirmed the same ESP32-C6FH4 / base MAC `10:BD:A3:9F:9F:84` on COM3, uploaded, and verified flash hashes. Boot leaves PWM off; no filesystem format or erase-all was used.
- Android app/test APK and lint passed; 15 protocol tests passed. Updated Pixel 8 Pro (`192.168.1.83:5555`) without clearing its data. Isolated on-device UI instrumentation passed continuous controls, sliders sending no commands, running-state busy handling, Stop/Release priority and error cleanup, old-firmware gating, and preservation of calibration/rules. These checks use a fake controller and do not establish physical motor motion.
- Real BLE connection subsequently showed continuous firmware telemetry. The user reported no physical movement. The screen then showed 21% speed, 367 ms duration, and 1600 µs neutral. Stopped telemetry alone cannot establish that a jog was sent or that the shaft moved. A later live slider adjustment exposed an Android range bug: setting minimum before maximum clamps the intended 1400 minimum to Android's default maximum, allowing invalid trim values. Correction and verification are tracked below.
- Corrected slider initialization to set maximum before minimum. Added exact min/max assertions for speed, duration and trim; the updated isolated Pixel 8 instrumentation passed. Corrected APK SHA-256: `67DAE8A98799A7FEE3068F3EF3E301311B0EBE9EB45A3FD9FC18D113D9B2BF6E`. Reinstalled, relaunched and connected to the real controller. Physical no-motion report remains unresolved until the user observes a valid full-speed jog; the slider defect alone is not established as its cause.

## Continuous run option — September 30, 2026

- Added user-requested Continuous mode alongside Timed mode. Action 17 starts persistent motion without a duration timer, and permits explicit changes to direction/speed during a continuous run. Stop, Release and disconnect end it. Changing mode/sliders alone does not issue motion.
- Motor telemetry v5 reports continuous running separately from timed motion; older v4 retains timed operation in the app. Motor boot state, positional-command rejection, neutral trim, saved settings and records remain unchanged.
- Expanded actual-function host tests passed continuous motion beyond a minute of simulated time, stale timeout callbacks, direction/speed replacement, duplicate suppression before/after Stop, Stop/Release/disconnect, invalid duration/payload rejection, stale connection rejection, and mutual exclusion with timed jogs. Read-only firmware review found no additional issue.
- Started the ESP build before completing the Android work. Final firmware compiled at 848,526 bytes flash / 20,604 bytes static RAM and was uploaded to the confirmed ESP32-C6 on COM3 with flash hashes verified. Binary SHA-256: `8791532A6D8F009827BBA258E1428CB49571FCF7FC71F2170D054548CC2F09B2`.
- Updated the ADB UI helper to use a fresh temporary XML path per read. A failed UI dump can no longer silently return a stale screen tree.
- Android app/test builds and 19 protocol unit tests passed; lint reported 0 errors / 21 warnings. Final APK SHA-256: `2FE62C4B7DE76611CD1D7C92BB6463518B5565EA584221FA720CA2A6563474A3`. Installed on Pixel 8 with data retained; isolated device instrumentation passed the Timed/Continuous mode routing, no-motion mode/slider changes, v4 fallback, running-state direction/speed updates, and Stop/Release queue checks.
- Relaunched the actual app, opened Motor test and connected to the ESP. Final live screenshot `screenshots/pixel8-continuous-option.png` shows Continuous selected and controller-reported Direction B at 70%, command pulse 1150 µs. The assistant did not tap a physical motor direction button. This verifies the live app/controller state, not measured shaft movement or torque.


Final live verification: after upload, Setup reported Connected and Live readings / recording stopped (sensor firmware capability accepted). Home displayed all three live values. Screenshot: docs/screenshots/sensor-home.png. APK SHA-256: 28CBEE0E134A35D13EB9339240415070506750767125965A56A82A7C9BFDB0B6. Firmware SHA-256: 2B07CC527CED36C6CC5CCECE663D70D88DD28ECF48FFA8EB40E51FD8FBCB4FB2. These verify delivered readings and UI, not independent sensor calibration.
