# Breeze

Android sensor monitor and XIAO ESP32C6 firmware, with learned manual Dreo IR controls and computer-driven Wi-Fi firmware updates.

The live Android app handles sensors, recording, history, and Dreo IR only. Android OTA was removed after the physical phone workflow failed; its complete source, docs, and final APK are preserved under `backups/2026-10-03-android-ota-attempt`.

Computer OTA is installed and physically proven on the controller. The one-time USB bootstrap to v1.2.0 was followed by a successful Windows Wi-Fi upload to v1.2.1, reboot, live-sensor recovery, and automatic rollback cancellation after the startup health check. The next v1.2.2 image is built and adds retry protection to that rollback confirmation. Wi-Fi updating uses the ESP32's temporary local access point, not Android, a home router, or the Internet. USB remains the recovery path.

To install the next firmware from Windows:

1. Leave the Breeze controller powered and running normally.
2. Open PowerShell in this folder and run `./firmware/wireless-update.ps1`.
3. When prompted, hold the XIAO's built-in **BOOT** button for two seconds, then release it.
4. Wait for `Firmware ... was accepted`. The script reconnects the computer to its previous Wi-Fi network when it finishes.

The script builds the next application image, validates its ESP32-C6 header, version marker, size, and SHA-256, temporarily joins `Breeze-OTA-...`, uploads to the inactive OTA slot, and removes the temporary Windows Wi-Fi profile. See [Computer Wi-Fi firmware updates](docs/PC_WIFI_UPDATE.md) for recovery and advanced use.

Home shows the raw **D0 body probe**, **D1 body probe**, **SHT40 temperature**, and **humidity** in the dark green granite theme. Temperatures display in Fahrenheit. All local sensors sample every 10 seconds. No motor, servo, PWM, or joystick controls are present. The Fan page provides manual learned IR commands, estimated speed synchronization, and a marked probe test. No automatic temperature rules are defined.

After stopping a recording, Breeze downloads its records immediately when connected, or resumes automatically on the next Bluetooth connection. The controller reclaims only records committed to phone storage. The app recreates its Bluetooth connection after adapter restarts or stalled operations rather than retaining a dead connection.

On History, pinch the recorded-night graph to zoom, drag horizontally to pan, and use Reset zoom or double-tap to see the whole night. The temperature axis fits only enabled traces in the visible time window, using fractional degree labels for small changes. Add a feeling entry with its date/time, Hot or Cold, and intensity 1–10; tap a saved entry to edit it. Orange Hot triangles and blue Cold circles mark entries on the graph and show their time/rating when tapped. Phone history and controller sensor rows are retained by an additive database migration.

- [Wiring, probe experiment, remote learning, build and install](docs/SETUP.md)
- [Computer Wi-Fi firmware updates](docs/PC_WIFI_UPDATE.md)
- [BLE protocol](docs/PROTOCOL.md)
- [Validation and hardware limitations](docs/VALIDATION.md)
- [Voice during recording: lock and say "Breeze, warm seven"](docs/BEDTIME-VOICE-TEST.md)
- Android package: `com.aaron.breeze` (Android 13+)
- Latest verified Android APK: `app/out-temperature-scale/outputs/apk/debug/app-debug.apk`
- Firmware: `firmware/BreezeController/BreezeController.ino`
- Windows updater: `firmware/wireless-update.ps1`
- OTA partition table: `firmware/BreezeController/partitions.csv`

The custom 4 MiB layout has two `0x1B0000` application slots and `0x80000` LittleFS. The OTA implementation uses Arduino-ESP32 3.3.12 facilities and adds no external firmware or Android dependency.
