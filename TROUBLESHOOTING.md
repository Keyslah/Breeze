# Breeze IR transmitter troubleshooting handoff

Updated: October 8, 2026 (America/Chicago).
Source baseline: `6e707b33b8ba7213dd11a160f7c51bc46a24d6ac`.

## Current blocker

The original Dreo remote controls the fan and produces a flash visible through
the user's camera. The wired Breeze IR LED has not produced a visible camera
flash or a confirmed fan response. The user has repeatedly confirmed this
comparison. Do not restart by assuming the original remote was also invisible.

The cause remains unresolved. There is no established diagnosis of a defective
LED, wrong supplied part, incorrect transistor pinout, or bad learned command.
The immediate goal is a physically observed fan response to Breeze's learned
Power command. Temperature-based automatic fan rules remain unimplemented and
were explicitly requested as planning only.

This handoff combines the October 8 troubleshooting conversation with inspection
of the repository's current source. Measurements below were reported by the
user during that conversation; they were not remeasured when writing this file.
Prior software acknowledgements are identified separately from physical proof.

## Hardware and intended wiring

- Controller: Seeed XIAO ESP32C6, USB powered.
- Phone used in the session: Pixel 8 Pro.
- Purchased listing supplied by the user: Bridgold pack of five TSOP38238
  receivers and five TSAL6200 940 nm emitters, Amazon product `B09D3RGSHX`.
  This identifies the order; component authenticity was not independently tested.
- Transistor discussed in the session: N2222 / 2N2222. The actual manufacturer's
  package drawing and collector/emitter assignment remain unverified here.
- Receiver output: D6 / GPIO16. Transmitter control: D7 / GPIO17.

The normal circuit documented in [SETUP.md](docs/SETUP.md) is:

```text
USB VBUS (5 V) -> 47 ohm resistor -> IR LED anode
IR LED cathode -> NPN collector
NPN emitter -> GND
D7 -> 1 kohm resistor -> NPN base
All grounds common
```

This is the intended circuit, not proof of the present physical wiring. The
circuit was changed during direct-LED tests. Establish which configuration is
currently connected before interpreting another measurement. Keep the normal
47 ohm supply branch distinct from the proposed 1 kohm GPIO bypass below.
Unplug USB before rewiring, and identify transistor leads from the actual part
rather than assuming a universal N2222 lead order.

## Tests and observations already recorded

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

## Existing diagnostic button

The Android app already has **Power x3 after 5 seconds** (displayed with the
multiplication symbol) on both **Fan** and **Learn Remote**.

It checks fresh controller telemetry, a learned Power slot, and no in-progress
learning or pending fan command. It waits five seconds, then sends three Power
commands with one second between acknowledged sends. It provides immediate
feedback, a countdown, and beeps on acknowledged sends; the sequence times out
after fifteen seconds. It can run while Camera is open. Current source retains
Bluetooth for two minutes after leaving Breeze.

These controls were added during troubleshooting and are already in the source
baseline. A new diagnostic app is not needed just to request three delayed
Power sends. Confirm the installed app has this button before rebuilding.
Three real Power presses may toggle the fan three times; observe each response.

## Latest proposed test: no result recorded yet

The last troubleshooting reply proposed temporarily bypassing the transistor
with `D7 -> 1 kohm resistor -> LED -> GND`, fully disconnecting the LED from the
47 ohm supply branch, aiming at the fan from about four inches, and using the
existing delayed three-press button.

This is a record of the proposal, not a completed test or a proven repair.
No user result for it was present when this handoff was written. The 1 kohm
series resistor was specifically part of that low-current proposal; the normal
47 ohm VBUS circuit must not be mistaken for a GPIO-drive circuit. A failure at
low optical power would still not identify a unique cause.

An independent detector using the TSOP38238 and a visible indicator LED was
also proposed earlier, but was not performed in the retrieved record. The user
asked to use the already-working fan/remote comparison rather than adding that
detector setup. Neither proposal should be listed as having passed or failed.

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
- The receiver is disabled during a send and re-enabled afterward. Sending a
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
| [BreezeService.java](app/src/main/java/com/aaron/breeze/BreezeService.java) | `startDelayedPowerTest`, `sendDelayedPower`, command acknowledgements. |
| [MainActivity.java](app/src/main/java/com/aaron/breeze/MainActivity.java) | `delayedPowerControl`, Fan and Learn Remote UI. |
| [UiConnectionState.java](app/src/main/java/com/aaron/breeze/UiConnectionState.java) | Two-minute Bluetooth grace period. |
| [ir_test.py](tests/ir_test.py) | Real `FanRemote.cpp` logic exercised against fake Arduino, IR and NVS interfaces. |
| [SETUP.md](docs/SETUP.md) | Intended wiring, learning procedure and build dependencies. |
| [PC_WIFI_UPDATE.md](docs/PC_WIFI_UPDATE.md) | Current Windows firmware update workflow. Older Android OTA sections elsewhere are historical. |

The bundled IR library is IRremoteESP8266 2.9.0. The documented controller
toolchain is Arduino-ESP32 3.3.12. Source defaults to firmware 1.2.2; existing
validation records prove physical 1.2.1 operation and describe 1.2.2 as built
but not flashed. Read the connected controller's version before claiming a
current installed version. No device was queried or flashed for this handoff.

For an agent changing Android code, a fresh output directory avoids the older
Windows build-output locks:

```powershell
.\gradlew.bat -PbreezeBuildDir=out-ir-troubleshooting -I tests/preserve-results.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

The firmware logic check is `python tests/ir_test.py`. That script currently
expects Visual Studio 2022 Community C++ tools at its hard-coded Windows path;
it is not a portable hardware simulator. Builds and fake-interface tests do not
prove a D7 waveform, LED emission, or a fan response. No tests were rerun for
this documentation-only handoff. Build output directories and local toolchains
are excluded from Git; the repository contains the source and saved backups.

## What the next investigator needs to resolve

1. Record the actual current wiring and whether the latest 1 kohm bypass was
   ever attempted. Do not treat a proposed circuit as the present circuit.
2. Establish electrical drive and optical output as separate evidence. There
   is no captured D7 waveform, transistor collector waveform, or calibrated
   optical measurement in this handoff. The existing camera comparison and
   user measurements should not be dismissed or silently replaced with guesses.
3. If optical emission is established but the fan still does not respond,
   inspect the actual learned Power record, decoded/raw send selection,
   carrier/timing and repeat behavior. These are investigation targets, not
   diagnosed bugs. Preserve the original capture before any relearning.
4. Record a physical fan response before declaring success, then verify repeatable
   learned Power and speed commands with the final transmitter circuit.

Preserve phone history, controller recordings and learned IR slots. Do not
erase NVS, factory-reset the controller, clear app data, or implement the
planned temperature rules to perform this investigation. Update this file with
the exact circuit, readings, app/firmware version and observed result of each
new test so another agent can continue without repeating the same steps.
