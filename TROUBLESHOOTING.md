# Breeze IR transmitter troubleshooting handoff

## Current handoff: Setup and Logic review — October 9, 2026

Start with [Setup redesign and fan Logic investigation](docs/SETUP_REDESIGN_REVIEW.md).
Firmware 1.2.14 is installed and startup-confirmed. The latest reported failure
to turn Off is explained by room-gap HOLD taking precedence over the checked
Cool-rule body shutoff: room about 72.6°F, Cool below 72°F, Warm at/above 73°F,
body average about 75°F, target held at 4, and zero IR transmissions since boot.
The user has requested a complete Setup-page review and proposed solutions,
**without implementation**; no new precedence policy has been chosen.

Firmware 1.2.13 restored full learned Symphony bursts. Version 1.2.14 enforces
one second after the latest completed transmission before another automatic
action, preserving the 300 ms idle wake repeat. Off/1–9/H support is present.
The user reported manual presses roughly one second apart working; that does
not establish reliability at faster rates or physical reception of every send.
The sections below retain earlier observations and recovery history.

Updated: October 9, 2026 (America/Chicago).
Current software: manual-send changes and receiver diagnostics published with
this handoff; the latest recorded physical firmware installation is **1.2.10**.
The original October 8 investigation used source baseline
`6e707b33b8ba7213dd11a160f7c51bc46a24d6ac`.

## Current status: October 9, firmware 1.2.10 installed; wireless updates verified

**The NVS and USB logging recovery is complete.** Firmware 1.2.10 was installed
over Wi-Fi, then passed another complete wireless update to the same build with
**USB connected and its serial reader closed**, taking 53.2 seconds. Fresh BLE
after reboot verified 1.2.10, rollback pending false and all six learned slots
(`0x03f`). A final OTA re-entry after the backup/reset reached READY with ACK 0;
normal cancellation then returned it to IDLE and stopped the temporary network.
No IR commands were sent, and recording remains stopped.

The post-repair flash comparison verified every metadata field and active pulse
of all six learned codes through version-1 → version-2 migration. All 45 committed
keys remain: 35 other keys are byte-identical and four radio configuration /
calibration keys changed during radio operation. NVS now has **281 of 504 usable
entries available and zero orphaned blob entries**. The full physical first-press
IR symptom and unavailable D2 reading remain separate, unresolved observations.

### Recovery findings and earlier 1.2.9 validation

The controller is now connected and identified on **COM3**. USB serial reported
`phy_init: store calibration failed (0x1105)`, which is
`ESP_ERR_NVS_NOT_ENOUGH_SPACE`. The saved flash snapshot independently confirms
that all **504 usable NVS entries were occupied before repair**; the fifth page is the required
126-entry spare, not available application capacity. This establishes the
storage failure behind the radio-startup investigation, superseding the earlier
unknown-cause and USB-unavailable notes.

Initial recovery installed and hash-verified firmware **1.2.9** by an application-only USB
write to the existing valid slot at `0x10000`. Its first boot logged
`migrated=6 available NVS entries=280 reserve=252` and learned mask `0x03f`,
with no calibration error `0x1105` in the captured boot log. Fresh BLE confirmed
1.2.9, schema 2, the fan-history capability and rollback pending false. A new
correlated OTA command reached **PREPARING → READY, error 0**, with sequence 1
acknowledged successfully; Windows detected its temporary update network.
A complete Wi-Fi upload of 1.2.9 subsequently passed **while a USB serial reader
was actively draining output**: image/SHA-256 validation, reboot and startup
health confirmation all succeeded. The next boot logged `migrated=0`, 281
available entries and the same six learned slots; subsequent BLE verification
passed with learned mask `0x03f`. The NVS repair therefore survived a real
wireless update and reboot.

The first transfer with USB attached but its serial reader closed timed out
after 150 seconds at 28.7%. The installed Arduino `HWCDC.write` can wait about
two seconds on a full output buffer; the new once-per-second sensor diagnostic
was repeatedly delaying the main loop. Opening a read-only serial drainer
reduced measured OTA startup from about eight seconds to 156 ms and allowed
the full transfer above. Firmware **1.2.10 fixes that recurring log output**:
format each sensor diagnostic into a bounded buffer and emit it only when the
entire line fits the available output space. Full/partial/exact-fit buffer and
sustained sampling tests pass; one-second measurements and OTA code are
unchanged. The completed 1.2.10 wireless update with the serial reader closed
verifies that the recurring diagnostic no longer causes the observed timeout.

Before the repair, the 20 KiB NVS area contained six valid version-1 learned codes, each stored in a
fixed 1,576-byte structure despite using only 191 or 215 raw pulse timings.
Those six blobs occupied **314 entries**, plus one for the verified flag.
Radio calibration also had **64 orphaned entries** from an incomplete replacement;
the prior complete calibration remains valid. The learned-code checksums and
every active pulse in all six slots passed inspection. Neither `logic-profile`
nor `fan-estimate` is present in this controller snapshot; the phone's saved
settings are separate. The profile-persistence error 24 is consistent with the
confirmed exhausted NVS, not evidence that the learned commands are damaged.

The installed repair stores the same learned metadata and active pulses in a
compact version-2 format: 466 bytes for the two 215-pulse codes and 418 bytes for
the four 191-pulse codes. Boot migration runs before BLE initialization, accepts
validated legacy records, and replaces/readbacks one existing key at a time
without clearing it first. Normal learned-code writes must leave **252 available
entries** for radio work, in addition to NVS's internal spare page and the new
blob's temporary write cost. The migration exception applies only to validated,
space-shrinking replacements. Partition sizes, OTA protocol and rollback
confirmation are unchanged.

Preflight tests use the actual six backed-up codes and passed exact metadata /
pulse preservation, migration starting with only 64 available entries, an
interrupted migration followed by reboot, idempotent subsequent boots, malformed
record rejection, failed replacement preservation and the reserve guard.
Existing IR checks and all 432 first-send cases also passed. Those host tests
are separate from the live migration and OTA-startup evidence above. See
[validation details](docs/VALIDATION.md) for the flash layout and evidence.

Recording data is separate from NVS: the controller uses 512 KiB LittleFS,
while downloaded history lives on the phone. The last confirmed archive base,
total and phone cursor were all **40,974**, with recording stopped. Preserved
phone data includes 7 sessions and 40,974 samples. No recording format or data
partition is being resized for this repair.

Evidence: `firmware/out-fan-history-20261009/verification/usb-initial-diagnostics.txt`
and the read-only, value-redacted `verification/nvs-summary.py` in that same
build directory; the inspected snapshot is
`backups/2026-10-09-ota-nvs-recovery/boot-partition-nvs-before-repair.bin`.
Deployment evidence is under `firmware/out-nvs-recovery-20261009`:
`preflight.json`, `usb-install.log`, `usb-first-boot.txt`, the first failed
`wireless-roundtrip.log`, and the successful `wireless-drained-roundtrip-retry.log`
with `wireless-drained-serial.txt`. Final 1.2.10 evidence is under
`firmware/out-nvs-recovery-final-20261009`: `wireless-install.log`,
`wireless-unread-usb-roundtrip.log`, `after-unread-usb-verify.txt`,
`nvs-preservation.json`, `final-ota-reentry.txt` and `final-ota-idle.txt`.
The earlier successful OTA-startup trace is
`firmware/out-fan-history-20261009/verification/ota-correlated-1791575881778848200.json`.
The available backups must not be described as a complete 4 MiB flash image.
Keep the verified recovery files: firmware **1.2.8 and earlier cannot read
compact version-2 learned codes**. A downgrade requires a planned USB recovery
with the verified compatible backup, not blindly flashing the older firmware.

### Earlier October 9 app/IR observations

**Latest user direction: set aside the fan-not-working investigation.** The user
attributed the recent complete loss of response to the sensor having moved /
positioning and asked to return to the other problems. This is not a confirmed
firmware defect, and no further hardware diagnostics are requested. The remaining
scope at that stage was the original first-press issue, one-plot graph work, and
the then-blocked firmware update needed for recorded fan settings. The firmware
update and recovery are now complete as recorded above.

Before that clarification, the user reported **no physical fan response** to the
captured taps despite successful software acknowledgements. Preserve that trace
as evidence of the software path, not as proof of the cause of the positioning
problem or a reason to continue the set-aside investigation.

- The bounded local command trace captured six real user taps, including the
  first tap after returning to Fan. Each reached the click handler, generated a
  manual request, received a successful ACK, and advanced the controller's
  `sendCounter` from 10 through 16. Later telemetry reached 19. See
  [captured touch/request/ACK trace](app/out-fan-history-20261009/verification/phone-first-touch-trace.txt).
  These counters prove execution of the firmware send path, **not optical
  emission or acceptance by the fan**.
- The first-touch UI checks pass. The host test runs the actual firmware command
  and fan logic through **432 cases** covering first sends, reconnects, duplicate
  ACKs, profile commits, and decoded/raw paths. Its hardware spies cannot prove
  a physical IR waveform or fan response. Live Android/BLE inspection also found
  the controller connected on **1.2.7**, a fresh packet age of **506 ms**, and no
  pending command.
- A separate reconnect profile upload reported `fanError=24` and
  `commandError=24` while the send counter remained unchanged and no manual
  request was traced. The automatic profile commit can return this error when
  NVS writing, committing, or readback verification fails. The underlying cause
  was unresolved at that stage; the USB/snapshot evidence above now establishes
  exhausted NVS and intact learned commands. The profile persistence code
  is unchanged from the actual 1.2.7 build, and this error does not itself block
  the next manual send after the upload finishes failing.
- The **1.2.8** controller image is built at
  [BreezeController.ino.bin](firmware/out-fan-history-20261009/BreezeController.ino.bin).
  OTA returned **AP_START error 9 twice**, including after cancel/retry;
  **1.2.8 was not flashed**. At that stage, the physically verified version remained
  1.2.7. After the user restarted the ESP, a fresh uptime of 119 seconds
  confirmed the restart; another OTA attempt still returned AP_START error 9,
  and Windows detected zero Breeze update networks. Restarting did not resolve it.
- Fan setting now overlays the **one existing graph plot**, with no added
  plot or height. The corrected APK at `app/out-fan-overlay-20261009/outputs/apk/debug/app-debug.apk`
  is installed and hash-verified on Pixel 8 Pro. Its graph rendering checks
  passed. **D2 investigation is set aside at the user's request.**

Preserve the learned slots, NVS, phone history, and controller records. Do not
guess a duplicate-send workaround or reset NVS. Firmware 1.2.10 passed the
wireless round trip with its serial reader closed. The earlier first-press symptom is not proved
fixed by the software-path tests. The sections below preserve the earlier
investigation and do not override the user's latest direction.

## October 9: first ineffective press after returning to Breeze

The user reports that every return to Breeze, including a quick switch from
another app, needs two Power/Speed taps before the fan responds. Two Speed+
taps advanced the displayed estimate from 1 to 3 while the physical fan only
reached 2. This was observed on firmware 1.2.5; the subsequent 1.2.7 installation
does not establish a fix for that symptom.

A quick foreground return preserves BLE within the 120-second grace period;
it sends no fan command or speed correction. Android displays the controller's
estimate, and the 1.2.5 firmware increments it after calling `transmit()`.
That narrows the investigation to controller transmission/fan acceptance, but
still does not prove optical emission or identify the cause. Do not add an
unconditional second Power/Speed send: both transmissions could take effect.

Installed diagnostic APK: `app/out-fan-resume-diagnostic/outputs/apk/debug/app-debug.apk`.
`BreezeBLE` logcat now records manual request sequence/slot/previous send count
and acknowledgement. `dumpsys activity service com.aaron.breeze/.BreezeService`
also reports current speed, synchronization, send counter, last slot and readiness.
The next useful evidence is one failed first press after a foreground return,
paired with the physical fan's beep/display response and these diagnostics.

At this earlier stage, firmware 1.2.7 was installed with rollback cleared and
six learned slots intact. All phone records were saved before that update.
D2 firmware support was installed, but the live D2 reading remained unavailable;
the physical input cause has not been established. See `docs/VALIDATION.md` for
artifact hashes, preserved data and exact live observations. Older findings below
describe their original firmware versions.

## Current result: 220 ohms works, oscillation coverage remains open

Later October 8 testing established real fan response to the learned Power
command. The working circuit bypasses the transistor:
`D7 -> 220 ohm resistor -> TSAL6200 positive; LED negative -> GND`.
The 47 ohm supply branch, VBUS and transistor are disconnected from this LED
branch. The user reports that replacing the direct-drive 1 kohm resistor with
220 ohms works at about eight feet, but fan oscillation can still lose reception.
This is user-observed physical fan operation, not just a software acknowledgement.
It does not establish reliable reception throughout the fan's oscillation.

The successful sequence was:

1. With the direct D7 / 1 kohm circuit, the user repeated the LED-to-ESP-receiver
   test and reported **all three bursts received**. The receiver test passed 3/3.
2. The user then reported that the learned Power button **turned the fan on and
   off**. The direct 1 kohm bypass was therefore tested successfully at close range.
3. At the intended room distance, about eight feet, 1 kohm was insufficient.
   After replacing that LED series resistor with **220 ohms**, the user reported
   that it worked, but oscillation could still move the fan out of reception.

Do not restart from "the LED never emits" or "the learned Power code has never
worked." Both emission and physical fan operation now have positive test evidence.
The earlier transistor circuit's failure was not uniquely diagnosed.

The Android app's delayed Power helper is removed and the canceled receiver
monitor panel is hidden. **Test LED → receiver** remains on Fan and Learn Remote;
it is separate from the canceled monitor panel. Manual sends bypass the Android command FIFO: busy taps report
not sent immediately and are not retained or retried. Feedback replaces the
previous short message rather than accumulating long messages.

Firmware 1.2.5 was uploaded with the existing computer Wi-Fi OTA updater.
Fresh BLE verification confirmed version 1.2.5, rollback pending false and
the unchanged learned-slot mask `0x03f`. The manual 450 ms cooldown is removed;
multi-step target pacing remains. Firmware logic and phone fake-transport tests
passed. A visible UI test was skipped while the phone was locked; post-update
physical fan response and oscillation-safe placement still require user testing.
The user's successful 220 ohm result preceded this software update; it does not
by itself prove the updated app's perceived latency or full-sweep reliability.

## What remains to solve

- **Coverage during oscillation:** receiver-window location and a mounting
  position that works throughout the sweep have not been verified. Extending
  the LED's two wires and positioning it close to the receiver was proposed,
  but no completed mounting/full-sweep test is recorded.
- **Stronger transistor driver:** after the user asked for remote-like room
  coverage, the latest proposal was `VBUS / 5 V -> 47 ohms -> LED -> N2222
  collector`, with emitter to ground and `D7 -> 220 ohms -> base`. This has
  not been built and verified in the retrieved record. The outside-leg
  collector/emitter assignment remains unresolved. In that proposal the
  220 ohm resistor is in the **base drive**; in the currently working bypass
  it is in the **LED branch**. Keep those configurations distinct and preserve
  the working bypass until a change is deliberately tested.
- **Manual-button response after the update:** the user identified Power /
  Speed + / Speed - as the buttons that queued. The software now rejects busy
  taps immediately instead of executing them later, and the manual firmware
  cooldown is removed. Bluetooth/write/IR transmission still takes finite time;
  literal zero latency and reception of every rapid tap are not claimed.
- **Automatic temperature rules:** still planning only, not part of this fix.

## Earlier investigation record (superseded where noted)

The historical measurements below explain how the investigation reached the
working bypass. They do not override the successful receiver/fan tests above.

### Earlier blocker

The original Dreo remote controlled the fan and produced a flash visible through
the user's camera. At this earlier stage, the wired Breeze IR LED had not produced
a visible camera flash or a confirmed fan response. The user repeatedly confirmed this
comparison. Do not restart by assuming the original remote was also invisible.

The original circuit's failure remains unresolved. There is no established diagnosis of a defective
LED, wrong supplied part, incorrect transistor pinout, or bad learned command.
The original immediate goal was a physically observed fan response to the learned
Power command; that milestone has since passed with direct D7 drive.

This handoff combines the October 8 troubleshooting conversation with inspection
of the repository's current source. Measurements below were reported by the
user during that conversation; they were not remeasured when writing this file.
Prior software acknowledgements are identified separately from physical proof.

### Hardware and earlier transistor wiring

- Controller: Seeed XIAO ESP32C6, USB powered.
- Phone used in the session: Pixel 8 Pro.
- Purchased listing supplied by the user: Bridgold pack of five TSOP38238
  receivers and five TSAL6200 940 nm emitters, Amazon product `B09D3RGSHX`.
  This identifies the order; component authenticity was not independently tested.
- Transistor discussed in the session: N2222 / 2N2222. The actual manufacturer's
  package drawing and collector/emitter assignment remain unverified here.
- Receiver output: D6 / GPIO16. Transmitter control: D7 / GPIO17.

The earlier transistor circuit documented in [SETUP.md](docs/SETUP.md) was:

```text
USB VBUS (5 V) -> 47 ohm resistor -> IR LED anode
IR LED cathode -> NPN collector
NPN emitter -> GND
D7 -> 1 kohm resistor -> NPN base
All grounds common
```

This is historical wiring, not the current working 220 ohm bypass. The
circuit changed during direct-LED tests. Establish which configuration is
connected before interpreting another measurement. Keep the transistor's
47 ohm supply branch distinct from any GPIO-driven LED branch.
Unplug USB before rewiring, and identify transistor leads from the actual part
rather than assuming a universal N2222 lead order.

### Earlier tests and measurements

| Check | Recorded result | What it establishes / does not establish |
| --- | --- | --- |
| Original remote and fan | User reports the remote operates the fan. | A working reference transmitter exists. It does not verify Breeze's waveform. |
| Camera reference | Original remote visibly flashes; wired LED remains dark. | Accept this reported comparison. It is not a calibrated optical-power measurement. |
| Learning | User completed learning and saw Relearn; later delayed Power commands were acknowledged. | Consistent with saved learned slots and an accepted send path. Exact captured Power protocol/value/raw timings were not recovered for this handoff. |
| Receiver unplugged | User removed the receiver after learning. | Stored sends do not require a new capture. Current reconnect status is unknown. |
| Transistor circuit, 3.3 V supply | Fan did not respond. User then tried swapping the two outside transistor connections. | Neither orientation produced the desired result; this did not identify collector/emitter. |
| Transistor circuit, VBUS supply | Moving the 47 ohm supply feed to VBUS did not fix it. | Raising the supply was already tried. |
| Supply measurement | User reported exactly 5 V between the VBUS end of the 47 ohm resistor and the transistor's ground connection. | Supply reached those measured points at that time. |
| LED diode test | One probe direction displayed `1`; reversed probes displayed `870`, with red on the LED leg toward the 47 ohm resistor. User clarified the probes were on the LED legs, with the supply side disconnected. | The prior session interpreted these as open in one direction and about 0.87 V forward drop in the other. This does not establish optical emission. |
| Direct LED test | Temporary circuit was `3V3 -> 47 ohm resistor -> LED -> GND`, bypassing the transistor and all learned-command transmission. User still saw no light through the camera. | This test did not depend on IR learning, D7 modulation, or transistor switching. It was a DC test, not a valid remote-control command to the fan. |
| Voltage across series resistor in that direct test | 2.33 V. | Use together with the measured resistance below. |
| Resistance, power disconnected and one resistor end isolated | 46.7 ohms. | Confirms the resistor was approximately 47 ohms, not 47 kohms. |
| Calculated branch current | `2.33 V / 46.7 ohms = 0.0499 A`, about 50 mA. | Calculated from the two reported readings, not a separate ammeter measurement; it does not prove optical output. |
| Another LED from the same pack | User tried a second LED and again reported no light. | A single failed LED is not an established explanation. The second LED was not separately characterized electrically in the retrieved record. |
| Powered LED voltage | User explicitly clarified `0.96 V`, measured across the LED in the direct 3V3 test. | Do not reinterpret the reading as 96 V or diagnose the part solely from this value. |
| Delayed Power run | Prior session reported one completed run with all three controller commands acknowledged. User still reported no wired-LED flash. | Software command completion is not optical or fan-response proof. |

### Removed diagnostic button

The earlier Android app had **Power x3 after 5 seconds** (displayed with the
multiplication symbol) on both **Fan** and **Learn Remote**. It was removed at
the user's request after physical fan operation was established.

It checked fresh controller telemetry, a learned Power slot, and no in-progress
learning or pending fan command. It waited five seconds, then sent three Power
commands with one second between acknowledged sends. It provided immediate
feedback, a countdown, and beeps on acknowledged sends; the sequence timed out
after fifteen seconds. It could run while Camera was open. Current source retains
Bluetooth for two minutes after leaving Breeze.

Do not re-add this removed helper to continue troubleshooting. The current
manual Power button and separate LED-to-receiver test serve different purposes.

### Earlier bypass proposal: subsequently tested successfully

The earlier troubleshooting reply proposed temporarily bypassing the transistor
with `D7 -> 1 kohm resistor -> LED -> GND`, fully disconnecting the LED from the
47 ohm supply branch, aiming at the fan from about four inches, and using the
existing delayed three-press button.

The later 3/3 receiver result and physical fan Power response above supersede
the initial "no result yet" status. The direct-drive resistor was subsequently
changed from 1 kohm to 220 ohms for range. Neither circuit uses VBUS directly.

An independent detector using the TSOP38238 and a visible indicator LED was
also proposed earlier, but was not performed in the retrieved record. The user
asked to use the already-working fan/remote comparison rather than adding that
detector setup. That proposed visible-indicator circuit remains untested; it is
different from the subsequently successful firmware LED-to-receiver test.

## What the software actually does

- Learned commands persist in controller NVS namespace `breeze-ir`, under keys
  `ir0` onward. Power is slot 0. Preserve these records during investigation.
- The receiver captures demodulated timings. Raw replay uses the configured
  carrier (default 38 kHz, adjustable 30-60 kHz), not a measured factory-remote
  carrier frequency.
- `FanRemote::transmit` first attempts the decoded protocol sender with repeat
  argument `0`, if decoded metadata is available. It falls back to the saved
  raw timings only if that decoded send reports false. Both decoded metadata
  and raw timings are retained in the learned record.
- `sendCounter` increments after the send-library call. The serial log explicitly
  says physical response is unconfirmed. There is no sensor measuring LED light
  output or feedback from the fan.
- Current firmware keeps the receiver enabled during sends and records observed
  input, with a 250 ms post-send window for classifying it as during an LED send.
  That timing label alone is not proof of the input's optical source. Sending a
  stored slot does not require a connected receiver. A disconnected, floating
  receiver input could be interpreted as outside remote activity and invalidate
  a speed estimate; that is separate from proof of transmitter failure.
- Firmware rejects automatic-control enable with `RULES_PENDING`; temperature
  rules should not be added as part of diagnosing this transmitter.

## Source map and reproducibility

| File | Relevant area |
| --- | --- |
| [Pins.h](firmware/BreezeController/Pins.h) | D6 receiver / D7 transmitter assignments. |
| [FanRemote.h](firmware/BreezeController/FanRemote.h) | Learned record, receiver/sender configuration, slots and status. |
| [FanRemote.cpp](firmware/BreezeController/FanRemote.cpp) | `capture`, `load`, `transmit`, command handling and send counter. |
| [Protocol.java](app/src/main/java/com/aaron/breeze/Protocol.java) | Current receiver-test and receiver-input packet decoding. |
| [BreezeService.java](app/src/main/java/com/aaron/breeze/BreezeService.java) | `sendManualIr`, immediate busy rejection, `startIrReceiverTest`, receiver telemetry and acknowledgements. |
| [MainActivity.java](app/src/main/java/com/aaron/breeze/MainActivity.java) | Manual send feedback, `irReceiverTestControl`, Fan and Learn Remote UI. |
| [UiConnectionState.java](app/src/main/java/com/aaron/breeze/UiConnectionState.java) | Two-minute Bluetooth grace period. |
| [ir_test.py](tests/ir_test.py) | Real `FanRemote.cpp` logic exercised against fake Arduino, IR and NVS interfaces. |
| [SETUP.md](docs/SETUP.md) | Intended wiring, learning procedure and build dependencies. |
| [PC_WIFI_UPDATE.md](docs/PC_WIFI_UPDATE.md) | Current Windows firmware update workflow. Older Android OTA sections elsewhere are historical. |

Protocol documentation caveat: [PROTOCOL.md](docs/PROTOCOL.md) still describes
an older OTA notification as type 7. Current code uses notification **type 7
for the IR receiver-test result** and **type 8 for receiver input**, adds fan
action **12** for the receiver test, and advertises receiver-test/monitor
support in capability bits 1/2. Consult `Protocol.java` and the firmware packet
writers for current layouts rather than applying the historical type-7 layout.

The bundled IR library is IRremoteESP8266 2.9.0. The documented controller
toolchain is Arduino-ESP32 3.3.12. **Source and build-script version defaults
still say 1.2.2.** The verified 1.2.10 build explicitly overrides the patch version;
the number cannot be inferred from the source defaults alone. To reproduce that
version, build explicitly and pass the resulting image to the existing updater:

```powershell
.\firmware\build-ota.ps1 -BuildPath .\firmware\out-nvs-recovery-final-20261009 -VersionPatch 10
.\firmware\wireless-update.ps1 -FirmwarePath .\firmware\out-nvs-recovery-final-20261009\BreezeController.ino.bin
```

The upload command changes the controller. The October 9 recovery above verified
1.2.10 after a complete wireless update, rollback pending false and learned slots
`0x03f` retained. Read live telemetry before claiming a future controller's
installed version; preserve the backup requirement before any legacy downgrade.

For an agent changing Android code, a fresh output directory avoids the older
Windows build-output locks:

```powershell
.\gradlew.bat -PbreezeBuildDir=out-ir-troubleshooting -I tests/preserve-results.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

The firmware logic check is `python tests/ir_test.py`. That script currently
expects Visual Studio 2022 Community C++ tools at its hard-coded Windows path;
it is not a portable hardware simulator. Builds and fake-interface tests do not
prove a D7 waveform, LED emission, or a fan response. Before this GitHub update,
the firmware host regression script and Android `testDebugUnitTest` both passed.
No live-device or full-oscillation test was performed for this publication.
Build output directories and local toolchains
are excluded from Git; the repository contains the source and saved backups.

## What the next investigator needs to resolve

1. Keep the proven `D7 -> 220 ohms -> LED -> GND` configuration as the physical
   baseline. Confirm placement and identify where in the oscillation reception fails.
2. Choose and test either emitter placement or the proposed stronger transistor
   driver. For the latter, establish the actual transistor's collector/emitter
   assignment first. Neither remedy has a verified full-sweep result yet.
3. Check the updated manual Power / Speed + / Speed - behavior on the real fan.
   Busy taps should be visibly rejected rather than queued, and successful taps
   should not play back as a delayed backlog. Target-speed stepping is separate.
4. Record repeatable Power and speed responses from the intended room position
   throughout the full oscillation. Preserve learned captures; working fan
   operation is already established, so relearning is not a default first step.

Preserve phone history, controller recordings and learned IR slots. Do not
erase NVS, factory-reset the controller, clear app data, or implement the
planned temperature rules to perform this investigation. Update this file with
the exact circuit, readings, app/firmware version and observed result of each
new test so another agent can continue without repeating the same steps.
