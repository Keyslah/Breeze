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

## Motor-test extension v2

Control actions: 9 initializes the SG90 at 1500 µs; 10 increases the pulse by byte5 µs; 11 decreases it. Steps accept 2–200 µs, require initialized PWM/test mode, and limit the resulting target to the allowed travel endpoints. All motor-test motion is rejected while recording. Action 4 releases PWM. Firmware error 8 means initialization is required; 9 means invalid step or pulse range.

Telemetry type 4 (20 bytes) precedes ordinary telemetry: byte1 bit0 motor-test mode, bit1 PWM enabled; bytes2–3 last commanded pulse in µs (500–2400, or 0 before any command); byte4 extension version 2. Android clears this state on disconnect and disables controls unless ordinary controller telemetry is fresh. A missing type-4 packet leaves testing unavailable on old firmware.

The firmware starts with motor-test mode enabled but PWM off, so an unwired joystick cannot trigger motion. Start/normal fan commands exit test mode; a test initialization or release enters it. Signal pin: D7 / GPIO17.

A tap requests the selected pulse delta. If the target exceeds 500–2400 µs, firmware moves only to the relevant endpoint. Android never changes the selected step amount; the next tap away from that limit uses the original full amount. Invalid step amounts are rejected with error 9. Normal fan-angle calibration remains mapped to 1000–2000 µs. Test position is displayed approximately with 500 µs = 0°, 1500 µs = 90°, 2400 µs = 180°.
