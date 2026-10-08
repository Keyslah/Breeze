# Breeze BLE protocol v1

Service: `689a1001-744d-4aca-89b3-028bc1fe3201`.
Notify/read: `689a1002-744d-4aca-89b3-028bc1fe3201`.
Write with response: `689a1003-744d-4aca-89b3-028bc1fe3201`.

All packets are exactly 20 bytes and all multi-byte integers are little-endian. Default BLE ATT MTU is sufficient. Subscribe to CCCD `0x2902` before commands. Commands are serialized; Android requires both GATT write completion and an application acknowledgement before advancing.

## Commands

Bytes 0–3: version 1, opcode, command sequence u16. Remaining bytes default to zero.

| Opcode | Payload |
|---|---|
| 1 config | bytes4/6/8: Low/Medium/High signed centi-°C; bytes10–13 angles in logical Off/Low/Medium/High order; byte14 hysteresis in tenths °C; 15 dwell seconds; 16 room weight percent; 17 RH threshold; 18 tenths °C per 10% RH; 19 calibration confirmed 0/1 |
| 2 control | byte4 action; byte5 value |
| 3 download | byte4–7 first desired record index u32 |
| 4 clock | byte4–7 UTC Unix seconds u32; does not shift a running session's clock |
| 5 retire | byte4–7 next record index after last durably saved record u32 |

Actions: 0 stop and fan Off, 1 start recording (Auto if calibrated and probe valid), 2 manual speed 0–3, 3 resume Auto, 4 detach servo/pause Auto, 5 bench angle test 0–180 (idle only), 6 explicitly format unmountable new storage (idle only), 7 start rules/recording and apply the current rules immediately, 8 stop rules/recording while preserving fan speed, servo angle and PWM. The app uses 7/8; the legacy joystick hold still uses stop-and-Off. Configuration writes do not move or detach the servo. Older firmware rejects 7/8, so upload the matching firmware for these controls.

## Telemetry (byte0 = 1)

Byte1 flags: bit0 active, bit1 Auto, bit2 calibrated, bit3 valid probe, bit4 valid room, bit5 valid RH, bit6 recording, bit7 storage healthy.
Bytes2–3 telemetry sequence. Bytes4–5 probe centi-°C signed. Bytes6–7 room centi-°C signed. Bytes8–9 RH centi-percent unsigned. Byte10 logical speed (0 Off / 1 Low / 2 Medium / 3 High). Byte11 last commanded angle. Bytes12–15 uptime seconds. Bytes16–17 latest command sequence. Byte18 ongoing sensor/storage fault. Byte19 command error, separately from sensor faults.

## Durable record (byte0 = 2)

Byte1 flags: same bits0–5, bit6 start marker, bit7 end marker.
Bytes2–5 global record index. Bytes6–9 UTC Unix seconds. Bytes10–11 probe centi-°C. Bytes12–13 room centi-°C. Bytes14–15 RH centi-percent. Byte16 speed. Byte17 angle. Byte18 ongoing fault. Byte19 CRC-8 over bytes0–18 (polynomial 0x07, initial 0, no reflection/xorout).

Body sampling and regular records occur every 30 seconds; fan temperature and RH sampling occur every 60 seconds. Regular body-only records clear room/RH validity flags, so Android stores nulls for those fields. Event markers retain the latest snapshot. The live telemetry cadence remains one second.

## Storage metadata (byte0 = 3)

Byte1 active. Bytes2–5 first retained record index. Bytes6–9 next record index after last stored record. Bytes10–13 free filesystem bytes. Byte14 storage healthy. Bytes15–18 archive identity generated on initial setup or explicit reformat. Byte19 reserved.

Android namespaces its durable cursor by controller Bluetooth address plus archive identity. A reformatted controller cannot reuse the previous archive's acknowledgement cursor. Android inserts only contiguous records with a valid checksum; it commits each sample and cursor together with SQLite `synchronous=FULL`. It requests missing records again. Firmware only accepts retirement of the entire currently retained generation when the cursor exactly equals its current end; additional records prevent premature retirement.

The protocol does not authenticate callers. It is intended for this personal controller, not a shared/public control network. Firmware and app must be updated together if protocol layout changes.

## Continuous motor-test extension v4

This build targets the user's three-wire continuous-rotation servo on D7 / GPIO17. Its output expresses speed and direction, not shaft angle. The prior positional source and app are preserved in `backups/2026-09-30-continuous-test`.

All following commands use opcode 2:

| Action | Payload |
|---|---|
| 14 timed jog | byte5 direction (1 positive pulse / 2 negative pulse), byte6 speed 1–100%, bytes7–8 duration 20–2000 ms, bytes9–10 neutral pulse 1400–1600 µs |
| 15 Stop | no payload; cancel timed motion and continuously send the configured neutral pulse |
| 16 apply stop trim | bytes5–6 neutral pulse 1400–1600 µs; idle only; apply immediately and persist separately from positional calibration |
| 4 release | cancel timed motion and disable PWM |

At 100% speed the two directions command 2000 and 1000 µs respectively; lower speeds interpolate from the configured neutral, initially 1500 µs. Signal frequency is 50 Hz. Neither speed percentage nor timed duration claims measured shaft travel. The user's physical motor direction and stop trim are determined by observation.

Telemetry type 4 precedes ordinary telemetry: byte1 bit0 test mode, bit1 PWM enabled, bit2 timed motion running; bytes2–3 last commanded pulse (0 before first command); byte4 extension version 4; bytes5–6 neutral pulse; byte7 signed direction (-1/0/+1); byte8 speed percentage; bytes9–10 remaining milliseconds. No angle estimate is exposed in the continuous motor test. The phone gates test commands by this version.

The ESP uses a one-shot `esp_timer` task callback to send neutral, independently of sensor reads, recording/storage work and the phone. A new jog is rejected while another runs; replay of the last accepted jog sequence is ignored even after completion. A disconnect stops driven motion, retains released PWM if already released, and clears pending commands. Connection generations prevent queued commands from a prior connection from running after reconnection. Explicit Stop still requires BLE delivery; the timed stop does not.

Boot leaves PWM disabled. Joystick movement and all legacy angle/endpoint/manual-fan/Start commands are disabled in this continuous-test build, preventing an old position command from becoming indefinite rotation. Saved positional calibration and records remain intact. Existing Start/Stop rules semantics above describe positional operation and are unavailable while this test firmware is installed. Error 9 denotes invalid jog/trim parameters, 10 unavailable positional operation, 11 motor already running, and 12 a stale/disconnected command.
