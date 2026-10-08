# Breeze setup and operation

## What runs where

The XIAO ESP32C6 reads the NTC probe, SHT40 and joystick, controls the servo, and records timestamped sensor data in its own internal flash every 10 seconds. The Android phone is not required during the session. Android connects over Bluetooth LE to configure the controller, download its records, display graphs, and retrieve outdoor weather later.

**The physical fan dial goes left to right: Off → High → Medium → Low.** The rule engine uses increasing cooling strength (Off, Low, Medium, High) and looks up the separately calibrated angle for each setting. It never assumes the dial moves monotonically with cooling strength.

Both Android and firmware begin with calibration disabled. The default angle placeholders are Off 60°, High 75°, Medium 90°, Low 105°; these are not measured positions. They span the requested approximate 45°. Reversed servo mounting is supported by entering decreasing angles in physical dial order. The exact servo model, pulse limits, linkage and direction still need bench verification.

## Wiring

| XIAO pin | Connection |
|---|---|
| D0 / GPIO0 / A0 | Thermistor divider midpoint |
| D1 / GPIO1 / A1 | Joystick Y output |
| D2 / GPIO2 | Joystick switch; other switch terminal to GND |
| D3 / GPIO21 | Servo signal |
| D4 / GPIO22 | SHT40 SDA |
| D5 / GPIO23 | SHT40 SCL |
| 3V3 | SHT40 VIN, joystick supply, top of divider resistor |
| GND | Sensor/joystick ground and servo supply ground |

Thermistor divider: **3.3 V → 10 kΩ fixed resistor → D0 → 10 kΩ B3950 NTC → GND**. Use a measured 1% resistor. Optional 100 nF from D0 to GND reduces noise. The code assumes a 10 kΩ nominal resistance at 25 °C, B=3950, 10 kΩ series resistance and 3.300 V supply; update the constants from measurements if needed. The probe reading is the under-sheet environment, not a core-body-temperature measurement.

Power the joystick from 3.3 V so its Y signal cannot exceed the ESP32 input range. X is unused. Supply the servo from a separate regulated 5 V source suitable for its stall current, with a common ground. Do not power the servo from the XIAO 3V3 pin. A local bulk capacitor near the servo supply can reduce resets. This project mechanically turns the existing fan knob; it does not connect to the fan's mains wiring.

## Firmware build / upload

Open `firmware/BreezeController/BreezeController.ino` in Arduino IDE.

- Board package: **esp32 by Espressif Systems**, tested with **3.3.7**.
- Board: **XIAO_ESP32C6** (`esp32:esp32:XIAO_ESP32C6`).
- Libraries: **Adafruit SHT4x Library 1.0.5** and its Library Manager dependencies.
- BLE, Preferences and LittleFS come with the Espressif board package.
- Use the default 4 MB flash partition layout. There is no OTA update feature in this project.
- Serial monitor: 115200.

CLI example:

```powershell
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 firmware/BreezeController
arduino-cli upload --fqbn esp32:esp32:XIAO_ESP32C6 --port <confirmed-port> firmware/BreezeController
```

No hardware was flashed automatically. Confirm the board/port before upload. Avoid erase-all-flash when records are pending. The firmware does not format storage automatically. On a new controller whose filesystem is not initialized, use **Setup → Initialize new controller storage**. Its confirmation explicitly describes the permanent formatting operation; it is allowed only when the partition will not mount and a session is not active.

## First setup

1. Open **Breeze** on the phone (Android 13 or later). Grant Nearby Devices; notifications are useful for download status.
2. Tap **Connect controller**. When found, tap again and select the advertised Breeze controller. Subsequent connections reuse that selection. USB is not used for normal operation.
3. Initialize fresh controller storage if required. The phone sets the controller clock on connection.
4. In **Setup**, bench-test the servo with the horn detached from the fan. The firmware maps 0–180° onto a conservative **1000–2000 μs at 50 Hz**. Confirm this range with the actual servo. Mount and align the linkage, then determine each of Off, High, Medium, Low without forcing the fan knob against its stops.
5. Enter the four angles and check **I verified all four positions on this fan**. Save. A session applies the saved configuration; setup changes are not applied to a session already in progress.
6. Set the desired temperature rules in **Rules**. Defaults (29/31/33 °C) are editable starting values, not an optimized comfort recommendation.
7. Tap **Start a session**. Until calibration is confirmed, this starts recording only, with no automatic servo movement.

The screen's **Preview a sample night** uses clearly labeled simulated data in memory only. It does not write to history or send commands.

## Rules and local controls

- Below the Low threshold: Off. Low/Medium/High thresholds select progressively stronger cooling.
- Cooling hysteresis defaults to 0.5 °C. Dwell defaults to 30 seconds to limit chatter.
- Behind-fan temperature weight is optional (0–50%). Its default is zero.
- Humidity can add an optional temperature offset above a chosen RH threshold. It defaults to zero influence.
- Effective control temperature = probe + (room − probe) × room weight + max(0, RH − RH threshold) × humidity boost per 10% / 10.
- Missing optional SHT40 readings fall back to probe-only for those contributions. Invalid probe data disables automatic movement and releases the servo signal; raw fault flags remain recorded. Resume Auto explicitly after correcting the fault.
- Joystick Y: one speed step per deflection from center. This switches to Manual.
- Joystick short press: starts a session if idle; while active toggles Auto/Manual when the probe and calibration are valid.
- Joystick hold for 1.5 seconds: ends recording and moves to calibrated Off.
- **End session + fan Off** does the same through the app.
- **Release servo signal** stops PWM and automatic movement but leaves recording running and the mechanical knob where it is.

No automatic movement occurs on boot. Without a battery-backed real-time clock, after controller power loss the phone must reconnect to establish wall time before another session can start. A running session retains its clock and works offline; a reboot ends it. History without an end marker is labeled open/interrupted. An external RTC would be needed for unattended starts with accurate wall timestamps after a power outage.

## Durable records and transfer

Records contain UTC timestamp, raw probe temperature, room temperature, RH, requested fan setting, automatic/manual state, sensor fault and start/end flags. There is no physical position-feedback sensor: the recorded fan setting is the command issued to the servo, not proof the fan knob reached it.

Each 20-byte record has a monotonically increasing index and CRC-8. It is appended to LittleFS and flushed before it is offered to Android. At 10-second intervals, raw samples use approximately 173 kB per day, plus filesystem and event overhead; the default partition is about 1.4 MB. Download regularly. Storage is finite: when nearly full or a write fails, the controller stops recording and automatic movement, holds the knob's last position, and reports a fault. It never silently overwrites unsent records.

Android accepts only contiguous, CRC-valid records. Record insertion and download-cursor update occur in one SQLite transaction. Duplicates are ignored. Missing/corrupt data cannot advance the acknowledgement cursor. Every completed download is acknowledged only after the database transaction commits. Download progress resumes after a Bluetooth disconnect or app restart.

The controller erases its current generation only when the acknowledgement exactly matches the end of its record file. If another sample arrived, it keeps the file until that sample is downloaded too. Before erasing the acknowledged file, it creates a durable new generation whose filename contains the next index; after a reboot, that generation also proves the older generation was already acknowledged. The phone retains session history. While connected, the app checks again every 30 seconds to download and clear subsequent records.

A power interruption during an append can leave a partial final record. Complete earlier records can still be downloaded; recording stays stopped until that generation is transferred and retired. CRC failure within a complete record stops transfer at that index and does not authorize erasure; keep the controller intact for recovery.

The firmware is a personal BLE prototype with no application-layer authentication or account system. Select your own controller. The XIAO has no SD card or external memory in this parts list.

## Weather, history and export

ZIP **53214** was resolved by Open-Meteo to Milwaukee city coordinates (43.0389, -87.90647); these are prefilled and labeled city weather. Change/search the place in Setup if you want another location.

Open **History**, select a session, then **Fetch outdoor weather**. The app queries hourly temperature and RH for that session, including one surrounding hour for graph continuity. It uses the forecast API's recent past range for recent sessions and the archive API for older dates. A still-running session can fetch the available portion; fetch again after it ends. Errors are shown without altering sensor records. Outdoor values are model data rather than a thermometer at the house. Attribution: [Open-Meteo](https://open-meteo.com/), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

The graph shares one real-time axis for probe, behind-fan and outdoor temperatures. Use Humidity for the RH view. Touch to inspect the exact sensor timestamp, reading and fan setting. Gaps remain gaps. The phone displays local time; CSV uses unambiguous ISO UTC timestamps and Celsius, regardless of display units.

Use **Export session as CSV** to save a portable copy through Android's document picker. App history is local; uninstalling the app or clearing its data removes it. The controller will already have erased acknowledged records, so export sessions you want to keep independently.

## Build and test Android

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/out/outputs/apk/debug/app-debug.apk`. Build output is directed to `app/out` to avoid a Windows lock encountered in a previous temporary build directory.

Tests include the binary protocol and dial mapping, host C++ control math, and actual Android SQLite recovery tests in an isolated temporary database. See `VALIDATION.md` for results and remaining hardware checks.

## Primary references

- [Seeed XIAO ESP32C6 pinout](https://wiki.seeedstudio.com/xiao_esp32c6_getting_started/), also verified against the installed board's `pins_arduino.h`.
- [Espressif Arduino LEDC API](https://docs.espressif.com/projects/arduino-esp32/en/latest/api/ledc.html).
- [Espressif BLE server UART example](https://github.com/espressif/arduino-esp32/blob/master/libraries/BLE/examples/UART/UART.ino).
- [Adafruit SHT4x library](https://github.com/adafruit/Adafruit_SHT4X).
- [Android BLE background connections](https://developer.android.com/develop/connectivity/bluetooth/ble/background).
- [Open-Meteo weather API](https://open-meteo.com/en/docs), [historical weather](https://open-meteo.com/en/docs/historical-weather-api), [geocoding](https://open-meteo.com/en/docs/geocoding-api).
