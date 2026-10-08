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
