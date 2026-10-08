# Breeze BLE protocol, sensor/IR capability 7

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

Opcode 6 actions:

| Action | Value / argument |
| --- | --- |
| 1 Learn | slot 0–11 / raw fallback carrier 30–60 kHz |
| 2 Cancel learning | unused |
| 3 Clear learned | slot 0–11 |
| 4 Send learned | slot 0–11 |
| 5 Target speed | 1–9; requires a synchronized estimate in Normal mode |
| 6 Sync speed | observed speed 1–9 / argument 1 confirms fan ON in Normal mode |
| 7 Auto | 0 cancels queued target; 1 always returns error 30, rules not defined |
| 8 Verification flag | internal persisted flag; no UI and cannot enable automatic control |
| 9 Forget estimate | cancels target and invalidates speed |
| 10 Start probe test | idle recording required; starts stage 1 baseline |
| 11 User marker | 1 baseline, 2 lying, 3 off; only during an active probe test |

Slots 0–5 are Power, Speed +, Speed -, Mode, Horizontal, Vertical; 6–11 are Extra 1–6. No IR codes are supplied by the firmware. No old motor commands are accepted.

## Controller notifications

**Type 4 capability:** byte 0 = 4, byte 4 = 7. The app also accepts prior sensor capability 6 for existing D0/SHT40 operation; new commands require fresh capability-7 telemetry.

**Type 1 live sensors / acknowledgement:** byte 0 = 1; byte 1 flags (bit 0 recording, bit 3 D0 valid, bit 4 SHT temperature valid, bit 5 humidity valid, bit 6 active storage, bit 7 storage available); bytes 2–3 telemetry sequence; 4–5 D0 signed Celsius ×100; 6–7 SHT temperature signed Celsius ×100; 8–9 RH percent ×100; 10–11 reserved; 12–15 uptime seconds; 16–17 acknowledged command sequence; 18 sensor/storage error; 19 command result. Missing values are identified by flags, not a zero temperature. Freshness expires after six seconds in the app.

**Type 5 D1 / IR status:** byte 0 = 5; byte 1 flags (bit 0 D1 valid, bit 1 automatic [always off], bit 2 synchronized estimate, bit 3 verification flag, bit 4 Normal mode confirmed, bit 5 target in progress); 2–3 D1 signed Celsius ×100; 4 estimated speed (0 unknown); 5 queued target (0 none); 6 learning slot (255 none); 7 learning state (0 idle, 1 waiting, 2 saved, 3 timeout, 4 canceled, 5 failed); 8–9 learned-slot mask; 10 IR error; 11 raw warmer probe index (0 neither, 1 D0, 2 D1); 12–13 that raw temperature ×100; 14 result slot (255 none); 15 last sent slot (255 none); 16–17 transmit count; 18 user test stage; 19 = 7. The raw warmer value is informational only; no contact qualification or temperature-driven control uses it.

**Type 3 recording metadata:** byte 0 = 3; byte 1 active; 2–5 retained base index; 6–9 total record index; 10–13 free filesystem bytes; 14 storage available; 15–18 archive identity. The phone namespaces its cursor by BLE address and archive identity.

## Durable records

New **type 6** records keep the old 20-byte size. Byte 0 = 6; byte 1 flags (bit 0 recording, bit 1 D1 valid, bit 2 raw D1 warmer, bit 3 D0 valid, bit 4 SHT temperature sampled, bit 5 RH sampled, bit 6 session start, bit 7 session end); bytes 2–5 record index; 6–9 UTC epoch seconds; 10–11 D0 signed Celsius ×100; 12–13 SHT signed Celsius ×100; 14–15 RH percent ×100; 16–17 D1 signed Celsius ×100; 18 low six bits fault and high two bits user test stage; 19 CRC-8 polynomial 0x07 over bytes 0–18 with initial CRC zero.

Legacy **type 2** records remain readable: same index/time/D0/SHT/RH/CRC offsets, with byte 16 legacy speed, byte 17 legacy angle, byte 18 fault. Their old bit-1 mode flag is never mistaken for D1. Phone schema version 2 adds nullable `second_probe` and default-zero `phase` columns without dropping existing tables or records. A continuous raw drop remains a normal sample.

Both body probes and SHT40 temperature/humidity sample every 10 seconds. Start samples both immediately. Marking lying/off samples both thermistors immediately and records the user event without falsely labeling cached SHT data as a new measurement. Download accepts only contiguous, CRC-valid records, commits sample and cursor together, then acknowledges retirement. Disconnect/reboot cannot erase unacknowledged records through this protocol.

## Results

0 success; 1 unsupported command/sequence conflict; 3 both body probes unavailable; 4 stop recording first; 6 storage unavailable/full; 7 controller clock required; 20 command not learned; 21 synchronize fan; 22 busy/rate limited; 23 learning timeout; 24 IR storage failure; 25 invalid capture; 26 bad value; 27/28 reserved fallback/test errors; 29 external remote observed; 30 automatic rules not defined.

A successful send acknowledgement establishes software transmission only. Physical fan state is never confirmed by this one-way IR protocol.
