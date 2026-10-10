# Breeze BLE protocol, sensor/IR capability 7

> Current app boundary: Android OTA commands and UI were removed. Firmware updates now use the physical BOOT button plus the Windows `firmware/wireless-update.ps1` uploader. OTA packet details retained later in this file describe the archived compatibility implementation, not the live Android client.

The existing GATT service and 20-byte little-endian framing are retained. Service: `689a1001-744d-4aca-89b3-028bc1fe3201`; notify/read TX: `689a1002-744d-4aca-89b3-028bc1fe3201`; write RX: `689a1003-744d-4aca-89b3-028bc1fe3201`.

## Phone commands

All commands: byte 0 = 1, byte 1 = opcode, bytes 2–3 = sequence, unused bytes zero. The phone waits for both GATT write completion and controller acknowledgement. Within a connection generation the controller caches its last eight commands; an identical sequence/payload retry returns its previous result without repeating IR. Reusing a cached sequence with different bytes returns error 1.

| Opcode | Payload |
| --- | --- |
| 2 | byte 4 action: 7 Start recording, 8 Stop recording; legacy aliases 1/0 retained. Action 6 initializes an unmounted filesystem only while idle; no routine format. |
| 3 | bytes 4–7 first requested record index; start download |
| 4 | bytes 4–7 UTC epoch seconds; clock set only while idle |
| 5 | bytes 4–7 durable phone cursor; retire only when it equals controller total |
| 6 | byte 4 fan/test action, byte 5 value, bytes 6–7 argument |
| 7 | byte 4 OTA action: 1 Enter OTA, 2 Cancel OTA, 3 Confirm running image |

Opcode 7 uses the same type-1 acknowledgement and sequence rules as every other command. Enter is idempotent while OTA mode is already active. It returns result 4 while recording, 22 while record download/IR work/upload is busy, and 31 if the temporary access point cannot start. Cancel is idempotent while OTA is inactive and returns 22 only while flash writing or reboot is in progress. Confirm is introduced by v1.1.1 and is sent only after that target image reconnects; it is idempotent when no image awaits verification, calls the ESP32 rollback-cancel operation when one is pending, and returns 32 if confirmation fails.

Opcode 6 actions:

| Action | Value / argument |
| --- | --- |
| 1 Learn | slot 0–11 / raw fallback carrier 30–60 kHz |
| 2 Cancel learning | unused |
| 3 Clear learned | slot 0–11 |
| 4 Send learned | slot 0–11 |
| 5 Target speed | 1–9; requires a synchronized estimate in Normal mode |
| 6 Sync speed | observed setting 0–9, with 0 meaning Off / argument 1 confirms Normal mode |
| 7 Auto | 0 pauses automatic control; 1 resumes the saved profile when the estimate is usable |
| 8 Verification flag | internal persisted flag; no UI and cannot enable automatic control |
| 9 Forget estimate | cancels target and invalidates speed |
| 10 Start probe test | idle recording required; starts stage 1 baseline |
| 11 User marker | 1 baseline, 2 lying, 3 off; only during an active probe test |
| 13 Logic global | probe mask, independent room thresholds, averaging and transition times; schema 2 |
| 14 Logic rule | rule index 0 warm / 1 cool, body threshold, per-level temperature step, minimum and off-below flag |
| 15 Logic boost | per-rule enable, extra levels, trigger/window, rise/hold/return times and curve |
| 16 Logic commit | atomically validate/persist the complete staged profile and enable automatic control |

Firmware 1.2.7 advertises capability byte 8 bit 3 for Logic, bit 4 for D2, and byte 10 = 2 for independent-threshold configuration. The app requires that schema before uploading Logic. Global action 13 uses byte 5 probe mask; signed centi-Fahrenheit warm threshold at 6–7; averaging seconds at 8–9; full-range rise/fall seconds at 10–11/12–13; maximum speed at 14; schema at 15; signed cool threshold at 16–17. Schema 1 remains accepted with cool equal to warm. Commit requires one global, both rules and both boosts; failed validation or persistence retains the previous profile.

Warm applies at/above its threshold and Cool below its threshold. In the gap, the controller holds its current estimate, cancels unfinished ramp/boost work and sends no new IR. The body average uses the maximum valid selected D0/D1/D2 reading at each 1 Hz sample. Profiles and known fan estimates survive reboot independently of learned IR slots. Manual sends/targets pause automatic control; an unknown estimate never causes a blind Power command.

**Type 9 Logic status:** byte 1 flags automatic=1, boost=2, ready=4, needs sync=8, no body=16, D2 valid=32; byte 2 rule (0 warm, 1 cool, 255 gap/unavailable); byte 3 target (0 Off); 4–5 averaged body centi-Fahrenheit; 6–7 room centi-Fahrenheit; 8–9 remaining boost seconds; 10–11 revision; 12 error; 13 selected probe mask; 14–15 signed D2 centi-Celsius; 19 = 1. Missing body and D2 values are identified by their flags.

Slots 0–5 are Power, Speed +, Speed -, Mode, Horizontal, Vertical; 6–11 are Extra 1–6. No IR codes are supplied by the firmware. No old motor commands are accepted.

## Controller notifications

**Type 4 capability:** byte 0 = 4; bytes 1–3 reserved; byte 4 = sensor/IR capability 7; bytes 5–7 firmware major/minor/patch. Byte 8 feature flags: bit 0 Wi-Fi OTA, bit 1 receiver test, bit 2 receiver input, bit 3 Logic, bit 4 D2/type-10 records, bit 5 fan-setting/type-11 records, bit 6 advertised telemetry cadence. Byte 9 bit 0 identifies a running image pending rollback health confirmation. Byte 10 is Logic configuration schema (2). With bit 6 set, byte 11 declares full periodic telemetry seconds (1–60); firmware 1.2.11 advertises 10. Other bytes remain reserved. Clients fall back to the legacy one-second cadence when its feature flag or a valid interval is absent.

Firmware 1.2.11 retains one-second internal body sampling and ten-second durable recording, while full live Bluetooth bundles run every ten seconds. Command responses stay immediate with type 1 ACK first. Meaningful fan/Logic status changes are sent separately; ordinary temperature changes do not trigger these event packets. Receiver diagnostics are limited to once per second, except prompt terminal test results. Android uses a 25-second freshness window and 30-second link watchdog for the ten-second cadence, preserving legacy six/fifteen-second limits and the six-second command timeout. The 120-second visibility grace remains independent of these timers.

**Type 1 live sensors / acknowledgement:** byte 0 = 1; byte 1 flags (bit 0 recording, bit 1 D2 valid, bit 3 D0 valid, bit 4 SHT temperature valid, bit 5 humidity valid, bit 6 active storage, bit 7 storage available); bytes 2–3 telemetry sequence; 4–5 D0 signed Celsius ×100; 6–7 SHT temperature signed Celsius ×100; 8–9 RH percent ×100; 10–11 D2 signed Celsius ×100; 12–15 uptime seconds; 16–17 acknowledged command sequence; 18 sensor/storage error; 19 command result. Missing values are identified by flags, not a zero temperature. D2 is decoded only after capability byte 8 bit 4 advertises support. Older sensor firmware leaves these fields unused; legacy motor firmware used them for mode/speed/angle, which must not become a D2 reading. Freshness uses the advertised cadence described above.

**Type 5 D1 / IR status:** byte 0 = 5; byte 1 flags (bit 0 D1 valid, bit 1 automatic, bit 2 synchronized estimate, bit 3 verification flag, bit 4 Normal mode confirmed, bit 5 target in progress); 2–3 D1 signed Celsius ×100; 4 estimated speed (0 Off when synchronized, otherwise unknown); 5 queued target (0 none); 6 learning slot (255 none); 7 learning state (0 idle, 1 waiting, 2 saved, 3 timeout, 4 canceled, 5 failed); 8–9 learned-slot mask; 10 IR error; 11 highest valid probe index (0 unavailable, 1 D0, 2 D1, 3 D2); 12–13 that raw temperature ×100; 14 result slot (255 none); 15 last sent slot (255 none); 16–17 transmit count; 18 user test stage; 19 = 7. Type 9 reports the separate rolling average used by automatic control.

**Type 7 OTA status:** all integer fields are little-endian.

| Byte(s) | Meaning |
| --- | --- |
| 0 | notification type 7 |
| 1 | state: 0 Idle, 1 Preparing, 2 Ready, 3 Uploading, 4 Verifying, 5 Rebooting, 6 Complete (reserved), 7 Error |
| 2 | progress, 0–100 |
| 3 | OTA error: 0 None, 1 Auth, 2 Bad length, 3 Bad SHA-256, 4 Update begin, 5 Update write, 6 Update verify, 7 Upload aborted, 8 Timeout, 9 AP start, 10 Wrong state |
| 4–7 | received byte count, unsigned 32-bit |
| 8–11 | next OTA partition capacity in Preparing/Ready; declared upload length after request headers are accepted, unsigned 32-bit |
| 12–19 | raw eight-byte session token |

The app must reject a selected image larger than the capacity reported in Ready. The token is valid only for that temporary OTA session and must not be logged.

**Type 3 recording metadata:** byte 0 = 3; byte 1 active; 2–5 retained base index; 6–9 total record index; 10–13 free filesystem bytes; 14 storage available; 15–18 archive identity. The phone namespaces its cursor by BLE address and archive identity.

## Durable records

New **type 6** records keep the old 20-byte size. Byte 0 = 6; byte 1 flags (bit 0 recording, bit 1 D1 valid, bit 2 raw D1 warmer, bit 3 D0 valid, bit 4 SHT temperature sampled, bit 5 RH sampled, bit 6 session start, bit 7 session end); bytes 2–5 record index; 6–9 UTC epoch seconds; 10–11 D0 signed Celsius ×100; 12–13 SHT signed Celsius ×100; 14–15 RH percent ×100; 16–17 D1 signed Celsius ×100; 18 low six bits fault and high two bits user test stage; 19 CRC-8 polynomial 0x07 over bytes 0–18 with initial CRC zero.

Each newly written type-6 record is immediately followed by a **type 10 D2 companion**, also exactly 20 bytes:

| Byte(s) | Meaning |
| --- | --- |
| 0 | record type 10 |
| 1 | bit 3 D2 valid; all other bits zero |
| 2–5 | companion's own contiguous record index |
| 6–9 | UTC epoch seconds, identical to its parent type-6 record |
| 10–11 | D2 signed Celsius ×100; ignored when validity bit is clear |
| 12–15 | parent type-6 record index, equal to companion index minus one |
| 16–18 | reserved, zero |
| 19 | CRC-8 polynomial 0x07 over bytes 0–18, initial CRC zero |

Firmware advertising capability byte 8 bit 5 follows that D2 companion with a **type 11 fan-setting companion**, also exactly 20 bytes:

| Byte(s) | Meaning |
| --- | --- |
| 0 | record type 11 |
| 1 | bit 0 setting known; all other bits zero |
| 2–5 | companion's own contiguous record index |
| 6–9 | UTC epoch seconds, identical to its parent type-6 record |
| 10 | known setting: 0 Off, 1–9 speed; must be zero when the known bit is clear |
| 11 | reserved, zero |
| 12–15 | parent type-6 record index, equal to companion index minus two |
| 16–18 | reserved, zero |
| 19 | CRC-8 polynomial 0x07 over bytes 0–18, initial CRC zero |

The type-6/type-10/type-11 sequence preserves the original type-6 layout and consumes three archive indices for one recorded timepoint. Neither companion starts a second session or adds another graph point. A known setting requires a synchronized controller estimate with known power state. Known Off has bit 0 set and value zero; Unknown has bit 0 clear and value zero. These are controller estimates, not confirmation of physical fan response. Invalid CRC, flags, reserved bytes, parent linkage, timestamp, valid-D2 range or fan-setting range prevents acknowledgement. A fan-setting companion must follow the matching D2 companion. Existing type-2/type-6 records and earlier type-6/type-10 pairs remain readable; absent companion values stay missing. Older apps cannot acknowledge past an unsupported type-10 or type-11 record, leaving those controller records retained for an updated client.

Legacy **type 2** records remain readable: same index/time/D0/SHT/RH/CRC offsets, with byte 16 legacy speed, byte 17 legacy angle, byte 18 fault. Their old bit-1 mode flag is never mistaken for D1. Phone schema version 2 adds nullable `second_probe` and default-zero `phase` columns without dropping existing tables or records. A continuous raw drop remains a normal sample.

Phone schema version 5 adds nullable `third_probe` and `third_probe_record` columns. In one transaction, companion ingestion finds the exact parent sample by device, parent index and timestamp, stores its D2 reading and companion index, resolves any eligible pending feeling entries, and advances the durable cursor. `third_probe_record` preserves the companion-index mapping for voice anchors without introducing duplicate samples. Existing sessions and readings are retained; older D2 values stay null. CSV appends `third_body_temperature_c` as column 11, preserving the original ten-column order and leaving older D2 values blank.

Phone schema version 6 adds nullable `fan_setting` and `fan_setting_record` columns. Type-11 ingestion matches device, parent index, timestamp and the preceding D2 companion index, then stores the known setting (0–9) or null for Unknown. The companion index is retained even when its setting is unknown, so voice anchors at any of the three archive indices resolve to the same sample. Updating the sample, resolving eligible pending feeling entries and advancing the durable cursor share one transaction. CSV appends `fan_setting` as column 12, preserving the first eleven columns; Unknown and legacy values are blank. Legacy type-2 speed bytes are never inferred as a recorded fan setting.

Regular durable samples include D0, D1, D2, SHT40 temperature/humidity and the current fan-setting estimate every 10 seconds. Fan settings use those sample timestamps rather than creating a record for every intermediate control change. The firmware also refreshes body sensors at 1 Hz for control; that faster sampling does not create extra regular graph points. Start samples the sensors immediately. Marking lying/off samples all three thermistors immediately and records the user event without falsely labeling cached SHT data as a new measurement. Download accepts only contiguous, CRC-valid records, commits each sample or companion update together with its cursor, then acknowledges retirement. Disconnect/reboot cannot erase unacknowledged records through this protocol.

The graph's highest-body line is the maximum finite D0/D1/D2 value in one recorded sample. SHT40 room temperature never contributes, and all-missing body readings remain missing. D0, D1 and D2 have independent numerical readouts and optional traces; the highest trace is enabled by default and the three individual body traces are unchecked.

Fan setting is an optional stepped line, enabled by default, on the same plotting rectangle as temperature and humidity. It uses a fixed 0–9 scale with colored labels on the left, inset when humidity is also shown, and shares the existing timestamps, zoom and pan. Enabling it adds no panel or graph height and does not move the temperature or humidity traces. Known values hold until the next recorded setting; Unknown readings and gaps longer than 35 seconds break the trace. The selected numerical readout shows Off, 1–9 or Unknown, including when the line is unchecked. Fan settings do not affect temperature or humidity scaling, and the existing feeling display is unchanged.

## Results

0 success; 1 unsupported command/sequence conflict; 3 all body probes unavailable; 4 stop recording first; 6 storage unavailable/full; 7 controller clock required; 20 command not learned; 21 synchronize fan; 22 busy/rate limited (including OTA upload active); 23 learning timeout; 24 IR storage failure; 25 invalid capture; 26 bad value or unknown OTA action; 27/28 reserved fallback/test errors; 29 external remote observed; 30 automatic rules not defined; 31 OTA access-point start failed; 32 running-image confirmation failed.

A successful send acknowledgement establishes software transmission only. Physical fan state is never confirmed by this one-way IR protocol.

## Wi-Fi OTA transport

BLE remains the control and status channel. After opcode 7/action 1 is acknowledged, the controller creates a temporary local-only WPA2 SoftAP:

- SSID: `Breeze-OTA-` followed by uppercase hex for token bytes 0–2 (six characters).
- WPA2 passphrase: uppercase hex for all eight token bytes (16 characters).
- Controller address: `192.168.4.1`.

On Android 13+, the app requests that exact network with `WifiNetworkSpecifier`. Android presents the normal one-time system approval prompt for that connection. The network has no home-router or Internet access; the app binds only the update request to it and does not change the device-wide default network.

The app sends the selected image as a raw request, not multipart data:

```text
POST http://192.168.4.1/firmware
Content-Type: application/octet-stream
Content-Length: exact .bin byte count
X-Breeze-Token: uppercase 16-character session-token hex
X-Firmware-SHA256: 64-character SHA-256 hex
```

The image must start with ESP image magic `0xE9` and contain one `BREEZE-FW:<major>.<minor>.<patch>` marker. Firmware checks the session token, exact length, inactive-slot capacity and SHA-256 while streaming to the inactive OTA partition, then verifies before boot selection. A successful response is HTTP 200 JSON `{"ok":true,"status":"rebooting"}` followed by a delayed reboot. Error responses use JSON `{"ok":false,"error":"..."}` with HTTP 400 (length/hash), 401 (token), 409 (state/busy), or 500 (update/write/verify). A stalled request is aborted after 30 seconds; an idle OTA session times out after five minutes. On reconnect, Android first requires the capability version to equal the selected marker version. If capability byte 9 says the image is pending verification, Android then sends opcode 7/action 3; only its successful acknowledgement cancels ESP rollback and allows the app to report completion.
