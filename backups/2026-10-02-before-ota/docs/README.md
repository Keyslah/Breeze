# Breeze

Android sensor monitor and XIAO ESP32C6 firmware, with learned manual Dreo IR controls.

Home shows the raw **D0 body probe**, **D1 body probe**, **SHT40 temperature**, and **humidity** in the existing dark green granite theme. Temperatures display in Fahrenheit. All local sensors sample every 10 seconds, and Start/Stop recording sits directly below humidity on Home. History combines temperatures and humidity on one graph with separate axes and six colored checkboxes. Choose recorded night opens a dated list of saved sessions. Existing D0 calibration and SHT40 D4/D5 wiring remain unchanged. No motor, servo, PWM, or joystick controls are present.

**No automatic temperature rules are defined.** Both probe curves, including sudden drops, are retained for the lie-on/get-off experiment. Automatic fan control cannot be enabled in this build.

The Fan page provides manual learned IR commands, estimated speed synchronization, and a marked probe test. History keeps both raw probes, room temperature, humidity, outdoor weather, and CSV export. Existing records are preserved through an additive database migration and backward-compatible record download.

- [Wiring, probe experiment, remote learning, build and install](docs/SETUP.md)
- [BLE protocol](docs/PROTOCOL.md)
- [Validation and hardware limitations](docs/VALIDATION.md)
- Android package: `com.aaron.breeze` (Android 13+)
- APK: `app/out-sensors/outputs/apk/debug/app-debug.apk`
- Firmware: `firmware/BreezeController/BreezeController.ino`
- Compiled firmware: `firmware/out-dreo`
