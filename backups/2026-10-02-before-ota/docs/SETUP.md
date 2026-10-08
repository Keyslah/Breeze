# Breeze: raw probe experiment and learned manual IR

## Wiring

| Function | XIAO ESP32C6 label | GPIO |
| --- | --- | --- |
| Existing body thermistor divider | D0 | 0 |
| Second body thermistor divider | D1 | 1 |
| Existing SHT40 SDA | D4 | 22 |
| Existing SHT40 SCL | D5 | 23 |
| TSOP38238 OUT | D6 | 16 |
| IR transistor base through 1 kΩ | D7 | 17 |

Pins are centralized in `firmware/BreezeController/Pins.h`. The assignments match the [Seeed XIAO ESP32C6 pin map](https://wiki.seeedstudio.com/xiao_esp32c6_getting_started/). D6/D7 are used for IR, not Serial1. Serial diagnostics use USB at 115200 baud.

```text
3V3 -- 10 kΩ --+-- D0       Existing divider
              +-- body thermistor -- GND

3V3 -- 10 kΩ --+-- D1       Separate second divider
              +-- body thermistor -- GND

SHT40: VCC -> 3V3; GND -> GND; SDA -> D4; SCL -> D5

TSOP38238: pin 1 OUT -> D6; pin 2 GND -> GND; pin 3 VS -> 3V3

VBUS (USB 5 V) -- 47 Ω -- TSAL6200 anode
                         TSAL6200 cathode -- 2N2222 collector
D7 -- 1 kΩ -- 2N2222 base
GND -------- 2N2222 emitter
All grounds common
```

Use the numbered package drawing in the [Vishay TSOP38238 datasheet](https://www.vishay.com/docs/82491/tsop382.pdf) to identify receiver leads. Its output is demodulated; the receiver cannot measure the original carrier. Raw replay defaults to configured 38 kHz, adjustable 30–60 kHz in Learn Remote; this is not a measured Dreo carrier. Known protocol senders use their library-defined timing/carrier.

The [TSAL6200 datasheet](https://www.vishay.com/docs/81010/tsal6200.pdf) identifies LED polarity. Verify collector/base/emitter using the datasheet for the **actual transistor package**: “2N2222” alone is insufficient to determine physical lead order. No package-specific lead order is assumed here. A 10 kΩ base-to-emitter pull-down is an optional addition to keep the transistor off while the ESP pin floats during reset. Keep the existing 47 Ω LED resistor; the LED is powered through the transistor, never directly from a GPIO. This diagram assumes USB-powered VBUS; it has not been physically inspected.

The original 3.3 V, 10 kΩ series resistor, 10 kΩ NTC, B3950 and zero-offset conversion is preserved. D1 uses the same conversion with a separate zero offset. “MF55 10K” alone does not establish its beta value; confirm the actual probe specification and compare the two probes together before interpreting differences. No calibration values were invented or tuned.

## Home recording and graphs

Start recording is directly below humidity on Home and switches to Stop recording during a session. The Setup recording control remains available. Temperature colors: D0 green, D1 coral, SHT40 gold, outdoors blue (dashed). Humidity colors: SHT40 purple, outdoors cyan (dashed). History combines all temperature and humidity traces on one graph: temperature on the left axis, humidity on the right (0–100%). Six colored checkboxes independently show/hide traces, and the app remembers those choices. Open History → Choose recorded night → tap a date to load its graph. The list explicitly shows how many nights are saved, including when there is only one. Old sessions retain their original sampling cadence and have no fabricated D1 readings.

## Collect the probe test first

1. Connect the updated controller in Setup. Stop any current recording before starting a separate experiment.
2. Open Fan and scroll to **Probe contact test**. Tap **Start probe test** to begin baseline recording.
3. Lie on the probes and tap **Mark: lying on probes**. Let the readings settle while recording continues.
4. Get off and tap **Mark: off probes**. Keep recording while both probes cool. Repeat the lying/off markers if useful.
5. Tap **Stop recording**, download, and open History. The separate D0/D1 curves have user event markers; export CSV to examine exact samples.

Both body probes and the SHT40 temperature/humidity sample every 10 seconds. Outdoor weather remains hourly modeled data. A marker also takes an immediate body sample. No contact detector, sudden-drop rejection, recovery threshold, inferred valid-contact range, or temperature-driven IR transmission is applied. A quick change between regular samples will only be localized to that sampling interval. Existing electrical/conversion fault checks remain; an unavailable probe shows a missing reading, not an invented temperature.

The firmware records locally without the phone after Start. The phone durably saves each record before acknowledging its retirement. Old sessions remain readable. CSV stores Celsius internally plus a second-probe column and test phase; the app displays Fahrenheit.

## Learn and verify manual IR

1. With IR hardware connected, open Fan → Learn Remote. Start with **Power → Learn**.
2. Point the original Dreo remote at the receiver and press Power once within 30 seconds. The app waits for the controller's **Learned** confirmation. Noise, repeat-only and overflow captures are ignored; timeout/save errors are shown.
3. Reboot the ESP32 and reconnect. Confirm Power still shows Learned.
4. Aim the transmitter at the fan and tap **Test** for Power. Personally observe the fan's response; a transmit count only proves software attempted a send.
5. Learn/test Speed + and Speed -, then Mode, Horizontal and Vertical. Six Extra slots are available for other buttons. Relearn replaces a slot after a new capture is saved; Cancel ends waiting; Clear erases the selected slot.
6. Verify both raw thermistors and unchanged SHT40 readings, then test the app's manual controls.
7. Set the physical fan ON in Normal/manual mode and a known speed. Open **Sync / set current speed**, select that observed speed, and confirm the mode. The 1–9 targets now issue spaced relative steps from this estimate. First verify that the learned +/- buttons actually change exactly one speed per press.

There is no fan feedback. Missed IR commands can invalidate the estimate. Re-sync after fan power loss, physical buttons, original-remote use, or unexpected response. Observed external IR invalidates the estimate conservatively; remote use outside receiver range cannot be detected. Power, Mode and Extra commands invalidate the estimate. Manual +/- at an estimated endpoint also invalidates it because actual wrap/clamp behavior is unknown. Reboot starts with unknown speed. Disconnect cancels an unfinished target or learning operation. No command transmits on boot.

**Automatic control remains unavailable.** We will define it only after inspecting the probe experiment. There are no adjustable automatic temperature parameters yet, and attempts to enable auto return “rules not defined.”

Learned signals live in the ESP32 Preferences namespace `breeze-ir`; raw timing data is retained even for decoded captures. The implementation bounds capture size (768 timing entries) and reports storage/capture failure rather than claiming every possible signal can be replayed. Available NVS space can limit the number of slots. The actual Dreo protocol, carrier, frame/repeat behavior, transistor circuit, range and reliable single-step response still require hardware testing. Serial prints decoded protocol/address/command, raw timings, saved slots, sends, sensor samples and received app commands.

## Build and install

Verified toolchain: Arduino-ESP32 **3.3.12**, project-local **IRremoteESP8266 2.9.0**, installed Adafruit SHT4x **1.0.5**, Adafruit BusIO **1.17.4**, Adafruit Unified Sensor **1.1.15**. Preferences, BLE and LittleFS come with the ESP32 core. The IR library APIs were checked against the installed [v2.9.0 source/release](https://github.com/crankyoldgit/IRremoteESP8266/releases/tag/v2.9.0); the isolated [ESP32 3.3.12 core](https://github.com/espressif/arduino-esp32/releases/tag/3.3.12) leaves the previous global installation intact.

From the Breeze project directory in PowerShell:

```powershell
.\gradlew.bat -I tests/preserve-results.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python tests/session_test.py
python tests/ir_test.py
$arduinoCli = 'C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe'
& $arduinoCli --config-file firmware/arduino-dreo.yaml compile --fqbn esp32:esp32:XIAO_ESP32C6 --libraries firmware/libraries --build-path firmware/out-dreo firmware/BreezeController
& $arduinoCli --config-file firmware/arduino-dreo.yaml board list
# Replace COM_PORT with the currently verified XIAO USB port, not a Bluetooth COM port.
& $arduinoCli --config-file firmware/arduino-dreo.yaml upload --fqbn esp32:esp32:XIAO_ESP32C6 --port COM_PORT --input-dir firmware/out-dreo firmware/BreezeController
adb -s PHONE_SERIAL install -r app/out-sensors/outputs/apk/debug/app-debug.apk
```

The checked-in local CLI configuration contains absolute paths for this workspace; update those paths if moving the project. Do not use erase-all or format the controller filesystem when uploading. Normal upload resets the board automatically; no second reboot is normally needed. If it stays in the bootloader, press RESET once and reconnect the app. The explicit reboot in the learning procedure is a separate persistence test. `install -r` retains Android data; no uninstall is required.

The app is installed on the Pixel 8 Pro. On October 2, the matching firmware was uploaded to the verified XIAO ESP32-C6 on COM3, with all written flash hashes verified and an automatic reset. Serial confirms capability-7 boot and Auto OFF. Initial readings: D0 22.78 °C, SHT40 22.67 °C / 63.0% RH; D1 unavailable. A later check after the 10-second update confirmed valid D1 readings and the visible phone UI. IR reception/transmission still requires physical validation.

## Changed source

Firmware: `BreezeController.ino`, `Sensors.h`, new `Pins.h`, new `FanRemote.h/.cpp`, local CLI configuration and IR library. App: `MainActivity.java`, `Protocol.java`, `BreezeService.java`, `Store.java`, `GraphView.java`. Tests cover protocol/records, firmware sampling and IR state, migration/export and fake-controller UI. The previous source snapshot is under `backups/2026-10-02-dreo-ir`; discarded provisional policy files were moved to the Recycle Bin. Existing historical backups remain outside the active build.
