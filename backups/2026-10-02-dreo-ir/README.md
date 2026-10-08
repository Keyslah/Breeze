# Breeze

Android sensor monitor and XIAO ESP32C6 firmware.

The opening page shows **Body temp**, **Fan temp**, and **Humidity** with a dark granite-green theme. Body temperature uses D0; the SHT40 supplies fan temperature and humidity on D4/D5. Motor controls, fan rules, calibration, PWM output, and joystick handling have been removed from the active app and firmware.

Connection and recording controls are in **Setup**. Saved sessions, graphs, outdoor weather and CSV export remain in **History**. Existing phone records and the controller log format are retained.

- [Wiring and operation](docs/SETUP.md)
- [Validation](docs/VALIDATION.md)
- Android package: `com.aaron.breeze` (Android 13+)
- APK: `app/out-sensors/outputs/apk/debug/app-debug.apk`
- Firmware: `firmware/BreezeController/BreezeController.ino`
- Compiled firmware: `firmware/out-sensors`
