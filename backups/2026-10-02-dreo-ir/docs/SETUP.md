# Breeze sensor setup

## Sensors

The existing sensor circuit is retained:

- **Body temperature:** D0, thermistor divider midpoint. 3.3 V → 10 kΩ resistor → D0 → 10 kΩ B3950 thermistor → GND.
- **Fan temperature and humidity:** SHT40, SDA on D4, SCL on D5, power at 3.3 V and common GND.

D1, D2 and D7 are unused by this firmware. It configures no actuator output. The thermistor calculation retains the existing 3.3 V / 10 kΩ / B3950 parameters and zero offset; readings have not been independently calibrated against a reference thermometer.

## App

Home opens with just the three sensor readings, the °F/°C toggle and navigation. A missing sensor or stale connection shows an em dash. Changing temperature units leaves relative humidity unchanged. The saved controller reconnects automatically when Bluetooth permission is available; first-time connection is under Setup.

Setup contains connection, Start/Stop recording, downloads, weather location and the wiring reference. Recording requires the sensor firmware (capability version 6). Older firmware can still supply live readings and existing records; the updated app cannot send actuator commands.

Body temperature updates every 30 seconds. Fan temperature and humidity update every 60 seconds. Both are read at boot and when a recording begins. Stopping recording leaves live sensing running. The controller records without the phone once a session has started; reconnect after a controller power loss to set its clock before another session.

History retains recorded sessions, temperature/humidity graphs, outdoor weather and CSV export. CSV exports contain sensor readings without fan control fields. The existing SQLite tables and 20-byte controller record framing are retained for historical data compatibility. The controller retires records only after the phone has committed and acknowledged them.

## Build and validate

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python tests/session_test.py
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 --build-path firmware/out-sensors firmware/BreezeController
arduino-cli upload --fqbn esp32:esp32:XIAO_ESP32C6 --port <confirmed-port> --input-dir firmware/out-sensors firmware/BreezeController
```

App output uses `app/out-sensors` to avoid locks in the previous output folder. The sensor session test compiles the actual firmware functions with fake sensor inputs. Android instrumentation checks the UI with a fake controller and validates durable storage in a temporary database.

Obsolete motor source and tests were moved to the Windows Recycle Bin. Earlier snapshots remain under backups for recovery; they are not part of the active build.
