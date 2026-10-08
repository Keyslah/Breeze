# Breeze

Android companion and Arduino firmware for an under-sheet temperature controlled fan.
See `docs/SETUP.md` for wiring, calibration, operation, and known limits.

The physical dial goes **Off → High → Medium → Low**, left to right. Servo output is disabled until explicitly calibrated. The XIAO stores its own timestamped records, and erases them only after Android confirms durable download. No firmware has been uploaded as part of creating this project.

- [Wiring, calibration and operation](docs/SETUP.md)
- [Validation and remaining hardware checks](docs/VALIDATION.md)
- Android package: `com.aaron.breeze` (Android 13+)
- APK: `app/out/outputs/apk/debug/app-debug.apk`
- Firmware: `firmware/BreezeController/BreezeController.ino`
