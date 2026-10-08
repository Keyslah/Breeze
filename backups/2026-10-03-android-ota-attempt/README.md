# Breeze

Android sensor monitor and XIAO ESP32C6 firmware, with learned manual Dreo IR controls.

Firmware **v1.1.0** introduced local Wi-Fi OTA while retaining BLE for normal operation; the physical controller now has that required one-time USB bootstrap. The current source and prepared first wireless test image are **v1.1.1**. The app enters update mode over BLE, Android shows one system approval prompt for the controller's temporary WPA2 network during each OTA join attempt, and the selected versioned ESP32-C6 firmware application `.bin` is sent directly to the inactive OTA partition. The update does not use a home router or Internet connection. The intended first OTA proof is v1.1.0 → v1.1.1 through **Setup → Firmware Update**. USB remains the recovery path.

Home shows the raw **D0 body probe**, **D1 body probe**, **SHT40 temperature**, and **humidity** in the existing dark green granite theme. Temperatures display in Fahrenheit. All local sensors sample every 10 seconds, and Start/Stop recording sits directly below humidity on Home. History combines temperatures and humidity on one graph with separate axes and six colored checkboxes. Choose recorded night opens a dated list of saved sessions. Existing D0 calibration and SHT40 D4/D5 wiring remain unchanged. No motor, servo, PWM, or joystick controls are present.

**No automatic temperature rules are defined.** Both probe curves, including sudden drops, are retained for the lie-on/get-off experiment. Automatic fan control cannot be enabled in this build.

The Fan page provides manual learned IR commands, estimated speed synchronization, and a marked probe test. History keeps both raw probes, room temperature, humidity, outdoor weather, and CSV export. Existing records are preserved through an additive database migration and backward-compatible record download.

- [Wiring, probe experiment, remote learning, build and install](docs/SETUP.md)
- [BLE protocol](docs/PROTOCOL.md)
- [Validation and hardware limitations](docs/VALIDATION.md)
- Android package: `com.aaron.breeze` (Android 13+)
- APK: `app/out-sensors/outputs/apk/debug/app-debug.apk`
- Firmware: `firmware/BreezeController/BreezeController.ino`
- OTA partition table: `firmware/BreezeController/partitions.csv`
- USB bootstrap build, v1.1.0: `firmware/out-ota-bootstrap`
- Prepared first OTA test image, v1.1.1: `firmware/out-dreo/BreezeController.ino.bin`

The custom 4 MiB layout has two `0x1B0000` application slots and `0x80000` LittleFS. Before the initial USB bootstrap, stop recording and download/confirm every controller-held record because the LittleFS offset changes. OTA, BLE, Wi-Fi, SHA-256, Preferences and LittleFS support come from Arduino-ESP32 3.3.12; no new external OTA library was added. The Android implementation uses platform APIs and adds no dependency.

The v1.1.0 USB bootstrap is not proof that wireless updating works. The real v1.1.0 → v1.1.1 Android system Wi-Fi approval, upload, reboot, BLE version/health confirmation, sensor/Dreo regression, power-cycle, interruption and USB-recovery checks remain pending until performed on the physical phone and controller.
