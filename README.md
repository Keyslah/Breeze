# Breeze

Android sensor monitor and XIAO ESP32C6 firmware, with learned manual Dreo IR controls and computer-driven Wi-Fi firmware updates.

Current hardware and agent handoff: [IR transmitter troubleshooting](TROUBLESHOOTING.md). Direct D7 drive through 220 Ω now operates the fan at about eight feet; reliable reception through oscillation remains open. The latest app/firmware changes remove queued manual sends and the manual 450 ms cooldown.

The live Android app handles sensors, recording, history, and Dreo IR only. Android OTA was removed after the physical phone workflow failed; its complete source, docs, and final APK are preserved under `backups/2026-10-03-android-ota-attempt`.

Computer OTA is installed and physically proven on the controller. The latest recorded update installed v1.2.5, confirmed rollback was cleared, and retained the learned remote slots. Source/build-script version defaults still say 1.2.2, so reproduce v1.2.5 with the explicit build below. Wi-Fi updating uses the ESP32's temporary local access point, not Android, a home router, or the Internet. USB remains the recovery path.

To rebuild and install firmware 1.2.5 from Windows:

1. Leave the Breeze controller powered and running normally.
2. Open PowerShell in this folder, run `./firmware/build-ota.ps1 -BuildPath ./firmware/out-immediate-ir -VersionPatch 5`, then `./firmware/wireless-update.ps1 -FirmwarePath ./firmware/out-immediate-ir/BreezeController.ino.bin`.
3. When prompted, hold the XIAO's built-in **BOOT** button for two seconds, then release it.
4. Wait for `Firmware ... was accepted`. The script reconnects the computer to its previous Wi-Fi network when it finishes.

The build creates the versioned application image. The uploader validates its ESP32-C6 header, version marker, size, and SHA-256, temporarily joins `Breeze-OTA-...`, uploads to the inactive OTA slot, and removes the temporary Windows Wi-Fi profile. See [Computer Wi-Fi firmware updates](docs/PC_WIFI_UPDATE.md) for recovery and advanced use.

Home shows the raw **D0 body probe**, **D1 body probe**, **SHT40 temperature**, and **humidity** in the dark green granite theme. Temperatures display in Fahrenheit. All local sensors sample every 10 seconds. No motor, servo, PWM, or joystick controls are present. The Fan page provides manual learned IR commands, estimated speed synchronization, and a marked probe test. No automatic temperature rules are defined.

After stopping a recording, Breeze downloads its records immediately when connected, or resumes automatically on the next Bluetooth connection. The controller reclaims only records committed to phone storage. The app recreates its Bluetooth connection after adapter restarts or stalled operations rather than retaining a dead connection.

Bluetooth stays connected for 2 minutes after leaving Breeze or locking the screen, allowing a switch to Camera without interrupting troubleshooting. Returning before the deadline cancels the countdown and preserves the connection. After 2 minutes away, Breeze closes the connection and cancels retries; opening Breeze again reconnects and resumes downloads. The controller continues recording independently. In **Setup → Voice during recording**, **Disable listening** starts checked and remains saved across restarts. While checked, starting a recording, reconnecting, or tapping a previous listening action cannot turn on the microphone. Uncheck it explicitly to allow voice logging again.

On History, pinch the recorded-night graph to zoom, drag horizontally to pan, and use Reset zoom or double-tap to see the whole night. The temperature axis fits only enabled traces in the visible time window, using fractional degree labels for small changes. Add a feeling entry with its date/time, Hot or Cold, and intensity 1–10; tap a saved entry to edit it. Orange Hot triangles and blue Cold circles mark entries on the graph and show their time/rating when tapped. Phone history and controller sensor rows are retained by an additive database migration.

- [Wiring, probe experiment, remote learning, build and install](docs/SETUP.md)
- [Computer Wi-Fi firmware updates](docs/PC_WIFI_UPDATE.md)
- [BLE protocol](docs/PROTOCOL.md)
- [Validation and hardware limitations](docs/VALIDATION.md)
- [Silent voice logging: lock and say "Breeze, warm seven"](docs/VOICE-LOGGING.md)
- Android package: `com.aaron.breeze` (Android 13+)
- Latest recorded Android build: `app/out-immediate-ir/outputs/apk/debug/app-debug.apk` (generated locally; see the handoff for validation limits)
- Firmware: `firmware/BreezeController/BreezeController.ino`
- Windows updater: `firmware/wireless-update.ps1`
- OTA partition table: `firmware/BreezeController/partitions.csv`

The custom 4 MiB layout has two `0x1B0000` application slots and `0x80000` LittleFS. The OTA implementation uses Arduino-ESP32 3.3.12 facilities and adds no external firmware or Android dependency.
