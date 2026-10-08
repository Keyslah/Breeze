# Android OTA attempt backup

Saved before removing Android-based firmware updating from the live Breeze app.

This snapshot contains:

- the complete Android source tree, including the OTA UI, file validation, Wi-Fi handoff, BLE OTA protocol, and tests;
- the Android module build file;
- the last successfully built debug APK (`Breeze-android-ota-debug.apk`);
- the README and documentation as they existed with Android OTA enabled.

Hardware result: the phone successfully commanded the ESP32 into OTA mode, joined its temporary Wi-Fi network, and received `192.168.4.2`. On both physical attempts, Android 17 then rejected the per-network socket bind with `EPERM` before any firmware bytes reached the ESP32. The ESP32 received CANCEL and resumed normal operation; it remained on firmware v1.1.0.

The live project is being changed to a computer-driven wireless update workflow. Do not copy this snapshot wholesale over the live project because it also contains the Dreo UI and protocol state from the same point in time; use it as a reference or restore source selectively.
