# Breeze: sensors, learned manual IR, and computer Wi-Fi OTA

> Current workflow: firmware updates are sent from Windows with `firmware/wireless-update.ps1`; the Android OTA experiment described in older historical sections below is archived and is not part of the live app. Follow [PC_WIFI_UPDATE.md](PC_WIFI_UPDATE.md) for the working procedure.

## Wiring

> October 9 status: the user-confirmed working transmitter is `D7 -> 220 Ω -> TSAL6200 anode`, with its cathode to GND and the VBUS/47 Ω/transistor branch disconnected. It operates the fan at about eight feet but can lose reception during oscillation. The transistor diagram below is the earlier design, not this working bypass; see [the current handoff](../TROUBLESHOOTING.md) before changing the transmitter.

| Function | XIAO ESP32C6 label | GPIO |
| --- | --- | --- |
| Existing body thermistor divider | D0 | 0 |
| Second body thermistor divider | D1 | 1 |
| Third body thermistor divider | D2 | 2 |
| Existing SHT40 SDA | D4 | 22 |
| Existing SHT40 SCL | D5 | 23 |
| TSOP38238 OUT | D6 | 16 |
| IR transistor base through 1 kΩ | D7 | 17 |

Pins are centralized in `firmware/BreezeController/Pins.h`. The assignments match the [Seeed XIAO ESP32C6 pin map](https://wiki.seeedstudio.com/xiao_esp32c6_getting_started/). D2 = GPIO2 is also verified in the installed build toolchain's `.arduino-dreo/data/packages/esp32/hardware/esp32/3.3.12/variants/XIAO_ESP32C6/pins_arduino.h`, selected by `firmware/arduino-dreo.yaml` and `firmware/build-ota.ps1`. D6/D7 are used for IR, not Serial1. Serial diagnostics use USB at 115200 baud.

```text
3V3 -- 10 kΩ --+-- D0       Existing divider
              +-- body thermistor -- GND

3V3 -- 10 kΩ --+-- D1       Separate second divider
              +-- body thermistor -- GND

3V3 -- 10 kΩ --+-- D2       Separate third divider
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

The original 3.3 V, 10 kΩ series resistor, 10 kΩ NTC, B3950 and zero-offset conversion is preserved. D1 and D2 use the same conversion, each with its own divider. “MF55 10K” alone does not establish its beta value; confirm the actual probe specification and compare the three probes together before interpreting differences. No calibration values were invented or tuned.

## Home recording and graphs

Home needs no scrolling. Its top half shows D0, D1, D2, Highest body temp, SHT40 room temperature and humidity using History's readout style, without checkboxes. Its bottom half contains Power, Speed +, Speed -, Mode, Horizontal, Vertical, Logic, Start recording, Reconnect and Current fan setting. Start recording changes to Stop recording during a session. Logic is blue when confirmed active and purple when paused. Voice controls and the saved-feelings shortcut remain on Setup. Temperature colors: D0 green, D1 coral, D2 lavender, highest body orange, SHT40 gold, outdoors blue (dashed). Humidity colors: SHT40 purple, outdoors cyan (dashed). History combines all temperature and humidity traces on one graph: temperature on the right axis, humidity on the left (0–100%). **Highest body temp** uses the highest available D0/D1/D2 reading at each recorded timestamp; room temperature never contributes. Highest starts enabled, while the three individual body traces start unchecked. Each History readout has its own colored checkbox to show/hide that graph line while keeping the reading visible, and the app remembers those choices. Open History → Choose recorded night → tap a date to load its graph. The list explicitly shows how many nights are saved, including when there is only one. Old sessions retain their original sampling cadence and have no fabricated D1 or D2 readings; their highest line uses whichever body readings were actually recorded.

**Fan setting** appears as a stepped line on that same graph and starts checked. Its fixed 0–9 scale is independent of temperature and humidity, with 0 meaning Off. Unchecking it keeps the numerical reading available. Installed firmware 1.2.10 includes the sampled fan-setting estimate in new recordings every ten seconds; unknown or older readings remain Unknown. It also preserves radio storage headroom and has passed a complete wireless update with the USB reader closed. No historical fan setting is reconstructed from old motor fields.

## Collect the probe test first

1. Connect the updated controller in Setup. Stop any current recording before starting a separate experiment.
2. Open Fan and scroll to **Probe contact test**. Tap **Start probe test** to begin baseline recording.
3. Lie on the probes and tap **Mark: lying on probes**. Let the readings settle while recording continues.
4. Get off and tap **Mark: off probes**. Keep recording while the three probes cool. Repeat the lying/off markers if useful.
5. Tap **Stop probe test**, download, and open History. Enable the separate D0/D1/D2 curves to compare them alongside the user event markers; export CSV to examine exact samples.

Regular durable history samples include all three body probes and the SHT40 temperature/humidity every 10 seconds. The firmware also refreshes body readings once per second for control; this does not change the regular graph-recording cadence. Outdoor weather remains hourly modeled data. A marker also takes an immediate body sample. No contact detector, sudden-drop rejection, recovery threshold, inferred valid-contact range, or temperature-driven IR transmission is applied. A quick change between regular history samples will only be localized to that recording interval. Existing electrical/conversion fault checks remain; an unavailable probe shows a missing reading, not an invented temperature.

The firmware records locally without the phone after Start. Each unchanged 20-byte type-6 record is followed by a 20-byte type-10 D2 companion. The phone merges the companion into the same recorded sample and durably advances its cursor before acknowledging retirement. Old sessions remain readable. CSV keeps its existing ten columns and appends `third_body_temperature_c` as column 11; D2 is blank for older rows that lack it. CSV temperatures remain Celsius; the app displays Fahrenheit.

## Learn and verify manual IR

1. With IR hardware connected, open Fan → Learn Remote. Start with **Power → Learn**.
2. Point the original Dreo remote at the receiver and press Power once within 30 seconds. The app waits for the controller's **Learned** confirmation. Noise, repeat-only and overflow captures are ignored; timeout/save errors are shown.
3. Reboot the ESP32 and reconnect. Confirm Power still shows Learned.
4. Aim the transmitter at the fan and tap **Test** for Power. Personally observe the fan's response; a transmit count only proves software attempted a send.
5. Learn/test Speed + and Speed -, then Mode, Horizontal and Vertical. Six Extra slots are available for other buttons. Relearn replaces a slot after a new capture is saved; Cancel ends waiting; Clear erases the selected slot.
6. Verify all three raw thermistors and unchanged SHT40 readings, then test the app's manual controls.
7. Set the physical fan ON in Normal/manual mode and a known speed. Open **Sync / set current speed**, select that observed speed, and confirm the mode. The 1–9 targets now issue spaced relative steps from this estimate. First verify that the learned +/- buttons actually change exactly one speed per press.

There is no physical fan feedback. Missed IR commands can invalidate the estimate. Correct it after fan power loss, physical buttons, original-remote use, or unexpected response. Observed external IR invalidates the estimate conservatively; remote use outside receiver range cannot be detected. Known Power changes track Off and the retained speed; Mode and Extra commands invalidate the estimate. Reboot restores a valid saved estimate; otherwise it waits for explicit synchronization. Disconnect cancels unfinished manual target or learning work while automatic rules continue.

## Automatic fan logic

**Logic** is the first section on Setup. Its renameable `default` profile starts with automatic control enabled. Valid edits save automatically after a short pause, on Done, or when leaving Setup; the top and bottom labels show **Saving**, **Saved**, or **Not saved** with the reason. **Save profile** and **Load profile** sit beside each other at both ends. Enter a name and use **Save profile** to keep a named copy on the phone; saving the same name updates that copy. **Load profile** lists those names and restores the selected settings. Automatic edits update the active settings without replacing named copies. The first app upgrade preserves the existing active profile as a named copy. Save and Load apply the active rules again, resuming automatic control after a manual override; an offline selection applies when the controller reconnects. The ESP stores only the single active profile, so additional named copies do not consume its recording storage.

Warm room and Cool room each have their own editable room-temperature threshold. Warm applies at or above its threshold; Cool applies below its threshold. Between them, the controller holds the current estimated fan setting and cancels an unfinished ramp or boost. The cool threshold must be at or below the warm threshold. Both default to 71°F, leaving no gap until edited.

The body input is the highest valid selected D0/D1/D2 reading at each sample, averaged over an editable window (default 60 seconds). Warm defaults to minimum speed 3, with another level per completed 0.4°F above 98°F. Cool defaults to Off below 98°F and starting speed 1 above it, with the same 0.4°F steps. Each rule has an independent optional sudden-warming boost, with editable extra levels, rise trigger/window, rise/hold/return times and Smooth or Linear curve. Normal full-range transitions default to 10 seconds up and 5 seconds down; both are editable.

On Home, **Current fan setting** beside Reconnect shows the current or last estimate; 0 means Off and the initial display defaults to 1. Correct it when physical buttons or the original remote change the fan, then press the keyboard's **Done**. An unknown controller estimate needs explicit confirmation before automatic commands can run; the displayed default never establishes a physical setting by itself. App-controlled changes update the field. A fan-button tap sends its command first; any edited-setting correction follows after acknowledgement and reflects that command's resulting setting. Unknown effects retain the text for explicit confirmation. Accepted manual app commands pause automatic control. Tap **Logic** to resume or pause; setting corrections do not override a pause. Explicit **Save profile** / **Load profile** also resume the chosen rules. The controller runs the saved rules independently of the phone connection. IR acknowledgements do not prove physical fan response.

Learned signals live in the ESP32 Preferences namespace `breeze-ir`; raw timing data is retained even for decoded captures. The implementation bounds capture size (768 timing entries) and reports storage/capture failure rather than claiming every possible signal can be replayed. Available NVS space can limit the number of slots. The actual Dreo protocol, carrier, frame/repeat behavior, transistor circuit, range and reliable single-step response still require hardware testing. Serial prints decoded protocol/address/command, raw timings, saved slots, sends, sensor samples and received app commands.

## First USB bootstrap and later wireless updates

Firmware v1.1.0 introduced the OTA-capable custom 4 MiB partition table. It must be installed once over USB before the app can perform later wireless firmware updates. The physical Breeze controller has now received that v1.1.0 USB bootstrap; the procedure remains required for a replacement board or USB recovery. Current source and the prepared first wireless test image are v1.1.1.

| Partition | Offset | Size |
| --- | --- | --- |
| NVS | `0x9000` | `0x5000` |
| OTA data | `0xE000` | `0x2000` |
| Application slot 0 | `0x10000` | `0x1B0000` |
| Application slot 1 | `0x1C0000` | `0x1B0000` |
| LittleFS (`spiffs` subtype) | `0x370000` | `0x80000` |
| Core dump | `0x3F0000` | `0x10000` |

`firmware/BreezeController/partitions.csv` is the authoritative flash layout. The `PartitionScheme=huge_app` FQBN option in the commands below only raises the Arduino compile/upload ceiling enough to produce the image; it does **not** replace the sketch-local CSV. Do not remove, rename, or omit `partitions.csv`. Add a post-build size check against the real `0x1B0000` OTA slot rather than relying on the larger menu-scheme ceiling.

Before the first OTA-capable USB bootstrap (v1.1.0 on the current controller):

1. Stop recording.
2. Connect with the currently installed app and download every controller-held record.
3. Confirm the expected nights/readings are present on the phone and export anything important.
4. Only then run the USB upload command below. The new table moves and shrinks LittleFS, so old controller-held log data at the prior filesystem offset is not preserved by the partition migration. NVS stays at the same offset, but learned Dreo slots must still be checked after the update rather than assumed intact.

The initial USB upload must flash the build's bootloader, partition table, boot-app image and application as Arduino CLI normally does. Do not write only `BreezeController.ino.bin` at `0x10000`: the v1.1.x application is larger than the prior application slot and using it with the old partition table can overlap flash regions.

For each later wireless update:

1. Build the next firmware with the exact command below. For the planned first proof, use the prepared v1.1.1 image at `firmware/out-dreo/BreezeController.ino.bin`; keep the v1.1.0 USB-bootstrap artifacts in `firmware/out-ota-bootstrap` distinct.
2. In the app, connect to Breeze and open **Setup → Firmware Update**. For the first proof, confirm the controller reports current firmware v1.1.0, the selected image reports v1.1.1, and recording is stopped.
3. Tap **Select firmware file** and select only the ESP32-C6 application `.bin`. Android's `ACTION_OPEN_DOCUMENT` picker grants access to that file; no broad storage permission is required. The app checks size, `0xE9` image magic, the ESP32-C6 image header, application descriptor, and one `BREEZE-FW:<major>.<minor>.<patch>` marker before enabling the update.
4. Tap **Update ESP32**. BLE opcode 7 starts a temporary WPA2 access point. On Android 13+, approve the single normal system Wi-Fi prompt for that OTA join attempt. Breeze uses `WifiNetworkSpecifier` to bind only the raw `POST http://192.168.4.1/firmware` upload to this local network; it neither joins a home router nor provides Internet access.
5. Keep the phone near the controller and leave Breeze running while progress advances through Ready, Uploading, Verifying and Rebooting. Do not remove controller power during flash writing or verification.
6. After the controller reboots, wait for BLE reconnection. Completion is valid only when capability telemetry reports the same firmware version embedded in the selected file and Android's opcode 7/action 3 health confirmation succeeds. Until then, capability byte 9 bit 0 reports that the new image remains pending ESP rollback confirmation.
7. Recheck live D0/D1/SHT40/humidity readings and every needed Dreo manual/learned command. Then power-cycle the controller and repeat the version, sensor and Dreo checks.

The Android 13+ manifest permissions used by this flow are `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, and runtime `NEARBY_WIFI_DEVICES` with `neverForLocation`, in addition to the existing BLE and foreground-service permissions. Cleartext HTTP remains disabled globally and is allowed only for the controller host `192.168.4.1`. No location permission or storage permission is added.

If an update fails before reboot, keep USB available as the recovery path. First try RESET and reconnect. To place a XIAO ESP32C6 into download mode, use Seeed's documented sequence: hold **BOOT**, press and release **RESET** while still holding BOOT, then release BOOT; alternatively, hold BOOT while connecting USB and then release it. Verify the actual USB port with `board list`, rerun the full USB upload command, press RESET once if the board remains in the bootloader, and reconnect. Do not erase all flash or format LittleFS as a routine recovery step. See the [Seeed XIAO ESP32C6 getting-started guide](https://wiki.seeedstudio.com/xiao_esp32c6_getting_started/) for the board procedure.

The OTA server, Wi-Fi, SHA-256, Update, Preferences, BLE and LittleFS facilities all come from Arduino-ESP32 3.3.12. No new external firmware library is required. Android uses platform networking/document APIs and adds no dependency.

## Build and install

Verified toolchain: Arduino-ESP32 **3.3.12**, project-local **IRremoteESP8266 2.9.0**, installed Adafruit SHT4x **1.0.5**, Adafruit BusIO **1.17.4**, Adafruit Unified Sensor **1.1.15**. Preferences, BLE and LittleFS come with the ESP32 core. The IR library APIs were checked against the installed [v2.9.0 source/release](https://github.com/crankyoldgit/IRremoteESP8266/releases/tag/v2.9.0); the isolated [ESP32 3.3.12 core](https://github.com/espressif/arduino-esp32/releases/tag/3.3.12) leaves the previous global installation intact.

From the Breeze project directory in PowerShell:

```powershell
.\gradlew.bat -I tests/preserve-results.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python tests/session_test.py
python tests/ir_test.py
$arduinoCli = 'C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe'
& $arduinoCli --config-file firmware/arduino-dreo.yaml compile --fqbn 'esp32:esp32:XIAO_ESP32C6:PartitionScheme=huge_app' --libraries firmware/libraries --build-path firmware/out-dreo firmware/BreezeController
$firmwareBin = Get-Item -LiteralPath 'firmware/out-dreo/BreezeController.ino.bin'
if ($firmwareBin.Length -gt 0x1B0000) { throw "Firmware image exceeds the 0x1B0000 OTA slot: $($firmwareBin.Length) bytes" }
& $arduinoCli --config-file firmware/arduino-dreo.yaml board list
# Replace COM_PORT with the currently verified XIAO USB port, not a Bluetooth COM port.
& $arduinoCli --config-file firmware/arduino-dreo.yaml upload --fqbn 'esp32:esp32:XIAO_ESP32C6:PartitionScheme=huge_app' --port COM_PORT --input-dir firmware/out-dreo firmware/BreezeController
adb -s PHONE_SERIAL install -r app/out-sensors/outputs/apk/debug/app-debug.apk
```

The checked-in local CLI configuration contains absolute paths for this workspace; update those paths if moving the project. The quoted FQBN must be used for both compile and upload, and the sketch-local `partitions.csv` must remain beside `BreezeController.ino`. The explicit binary-length check enforces the real OTA slot because the `huge_app` menu ceiling is larger. Do not use erase-all or format the controller filesystem when uploading. Normal upload resets the board automatically; no second reboot is normally needed. If it stays in the bootloader, press RESET once and reconnect the app. The explicit reboot in the learning procedure is a separate persistence test. `install -r` retains Android data; no uninstall is required.

Historical pre-OTA validation: the app was installed on the Pixel 8 Pro and the earlier capability-7 sensor/Dreo firmware was uploaded to the verified XIAO ESP32-C6 on COM3, with all written flash hashes verified and an automatic reset. Serial confirmed capability-7 boot and Auto OFF. Initial readings were D0 22.78 °C and SHT40 22.67 °C / 63.0% RH; D1 was initially unavailable, then became valid after the 10-second update and was visible in the phone UI.

Current OTA baseline: the physical controller was subsequently USB-bootstrapped with v1.1.0 and the new partition layout. That proves only the required wired bootstrap. Current source and the prepared wireless test image are v1.1.1. The real v1.1.0 → v1.1.1 Android approval/upload/reboot/version-and-health-confirmation flow, post-update sensors/Dreo regression, power-cycle, interruption and USB-recovery checks remain pending until performed on the physical controller and phone.

## Changed source

Firmware: `BreezeController.ino`, `Sensors.h`, `Pins.h`, `FanRemote.h/.cpp`, `OtaUpdate.h/.cpp`, `partitions.csv`, local CLI configuration and the existing IR library. App: `MainActivity.java`, `Protocol.java`, `BreezeService.java`, `FirmwareImage.java`, `Store.java`, `GraphView.java`, the manifest and host-scoped network-security configuration. Tests cover protocol/records, firmware sampling and IR state, OTA image/protocol handling, migration/export and fake-controller UI. OTA adds no external firmware library or Android dependency. The previous source snapshot is under `backups/2026-10-02-dreo-ir`; discarded provisional policy files were moved to the Recycle Bin. Existing historical backups remain outside the active build.
