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
