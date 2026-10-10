# Breeze validation history

## Firmware 1.2.14 and unresolved Setup behavior — October 9, 2026

- Firmware 1.2.14 compiled and was accepted by the existing Wi-Fi updater.
  The application image is 1,601,488 bytes with 167,984 bytes of OTA headroom;
  SHA-256 `97D86B4C734891ACD0C6DC9F4DE37695155936B4CCF416679A9707ACEB2A1AF2`.
  Fresh BLE reported 1.2.14, rollback pending false, learned mask 63, and
  synchronized estimate 4. Synchronization emitted no IR (`sendCounter=0`).
- All eight firmware host suites passed locally: `ir_test.py`,
  `fan_first_send_test.py`, `ir_nvs_migration_test.py`, `logic_control_test.py`,
  `third_record_test.py`, `telemetry_cadence_test.py`,
  `ir_symphony_replay_test.py`, and `fan_automatic_interval_test.py`.
  Automatic normal/boost/Power/TARGET sends now respect 1,000 ms since the latest
  actual transmission completion. Tests exercise 999/1000 ms boundaries,
  nonzero burst duration, wake repeats, rollover, slower configured timing,
  and cancellation when entering the room gap. Direct manual SEND and the
  300 ms wake repeat are unchanged.
- Deployment logs remain local under `firmware/out-auto-one-second-20261009`:
  `wireless-install.log`, `pre-update.log`, and `health-and-confirmed-four.log`.
  These build outputs are not published. Three tests above depend on unpublished
  legacy/NVS fixtures; see the handoff's reproduction limitations.
- After the user enabled Logic, the phone reported room 72.6°F, body average
  about 75°F, estimate/target 4, no errors, and zero IR sends since boot. Warm
  was 73°F and Cool 72°F; the checked Cool off-below threshold was 97.5°F.
  Source and live status agree that the room gap holds before body shutoff is
  considered. This explains this incident but does not resolve the intended
  product behavior. The user requested a full Setup review, with no implementation.
  See [the current investigation handoff](SETUP_REDESIGN_REVIEW.md).

## One-screen Home, command-first corrections, and ten-second BLE telemetry — October 9, 2026

- Home uses the top half for six History-styled live readouts without checkboxes and the bottom half for six fan commands, Logic, recording, Reconnect, and editable Current fan setting. No Home scrolling, elevation animation, or sending Toast. Confirmed Logic active is blue; paused is purple. The existing Fan page UI is retained.
- A Home fan command precedes any edited-setting correction. The correction waits for both the GATT write callback and matching successful ACK, projects the command's result, and never sends AUTO. Power-off corrections first establish the declared retained on-speed, then Off; these SYNC packets do not emit IR. Known ordinary commands add no SYNC. Ambiguous effects quarantine the old draft until a new human edit or Done; newer confirmations and edit revisions supersede older callbacks. Accepted physical sends are never replayed.
- Final Android artifact: `app/out-telemetry-20261009/outputs/apk/debug/app-debug.apk`, SHA-256 **ADB113A96993D24231E681F75C075ED6886B65137AF828233EDB43E119046DCE**. App/test builds, all **85 JVM tests**, and lint passed (0 errors, 28 warnings). Emulator-only Home instrumentation passed **37 touches**, including six OS-injected Power/Speed touches, cold start, tab returns, resume, focused input, mid-gesture readiness loss, draft ordering and revision protection. Strict layout/readout checks passed at font scales 1.0 and 1.3. Real-service fake-BLE queue tests passed ACK/write ordering, failed/rejected sends, retained speed, quarantined drafts, newer Done priority, cadence freshness and recovery. Controls regression passed; the earlier battery-policy check passed 120-second grace, cleanup, immediate manual disconnect and microphone veto. Logs are in that build's `verification/` directory.
- Installed with `adb install -r` on Pixel 8 Pro; installed APK hash matches the final build. SQLite integrity and complete row comparisons preserve all **7 sessions, 40,974 samples, 3 feelings, 34 weather rows and the cursor**. Named profiles, graph choices and microphone veto match the earlier snapshot. Active settings match the copy taken immediately before the final install. That active profile already had Warm body threshold **96°F**; the earlier task backup had 98°F. The current value was preserved, and the discrepancy is recorded rather than attributed or silently restored. See `data-preservation.json` and the two phone snapshots. The phone remained locked; physical screen taps and actual fan reception were not established by the emulator or installed hash.
- The user explicitly reported current fan setting **2**. One non-actuating SYNC established that anchor, received successful ACK in **47 ms**, and confirmed a synchronized estimate of 2. The phone's cache was updated to that confirmed value while stopped, preserving every unrelated preference. No manual Power or Speed test was sent. See `anchor-confirmation.json`.
- Firmware **1.2.11** is installed over Wi-Fi: `firmware/out-telemetry-20261009/BreezeController.ino.bin`, **1,601,120 bytes**, 168,352 bytes of slot headroom, SHA-256 **1280DC61F71821B6165A65E4E1AC376174F07DBA67C08AD81FC87E5914FE15E4**. Before deployment, its ESP32-C6 header/version/size and partition binary were checked against the verified 1.2.10 recovery. OTA source/script, partition layout and compact learned-code storage are unchanged. Upload acceptance, reboot, health confirmation, retained learned mask 63, subsequent OTA Ready and cancellation to Idle all passed. Live quiet observation found exactly three of each full telemetry packet type over 31 seconds, with sensor gaps **10.015 / 9.969 seconds**; CLOCK ACK took **62 ms**. Internal body sampling remains 1 Hz, durable recording 10 seconds, command ACKs immediate, and meaningful state changes prompt. Host session, D2, IR, Logic, first-send, NVS-migration and telemetry-cadence suites passed. This establishes software/radio behavior, not optical emission, physical fan response or oscillation coverage.

## Named Logic profile Save/Load — October 9, 2026

- Added same-row **Save profile** / **Load profile** controls at both ends of Setup's Logic section. Explicit Save keeps a named phone snapshot; autosave only updates active settings. First-use migration preserves the existing active profile. Loading cancels the previous form's delayed save before rebuilding and applies the selected profile through the existing controller protocol. No firmware or OTA files changed for this update.
- `out-profile-load-20261009`: app/test APK builds passed, all 80 JVM tests passed, and lint reported 0 errors (26 warnings). Emulator-only `namedLogicProfiles` instrumentation passed legacy seeding, distinct names sharing an identity, full-field round trips, same-name replacement, active-only autosave, trimmed names, malformed-library preservation, both Save/Load rows, picker cancellation, one apply per selection, delayed-save protection, and offline persistence. Tests used isolated preferences/database paths and a fake controller. Emulator screenshot inspected: `verification/setup-emulator.png`.
- Installed the app update on Pixel 8 Pro with data-preserving `adb install -r`. Installed APK SHA-256 matches `B18BDAE5E4CD46D7C4CB5306A215641E2BB4D12181E1F997658AEC8C5AD992C2`. Before/after snapshots passed database integrity and preserved all 7 sessions, 40,974 samples, 3 feelings, and 34 weather rows. The active profile remained unchanged and its seeded named copy contains identical values. The phone was locked, so UI interactions were verified in the emulator rather than on the physical display. Snapshots and reports are under `app/out-profile-load-20261009/verification/`.
- Live phone storage check: 29 GB available. Current serialized profile is 730 bytes; the one-profile named collection is 775 bytes before XML overhead. Named copies stay on the phone; loading replaces the controller's existing single active profile.

## Firmware 1.2.10 recovery complete; wireless updates verified — October 9, 2026

- Final image: `firmware/out-nvs-recovery-final-20261009/BreezeController.ino.bin`,
  **1.2.10**, 1,600,112 bytes, 169,360 bytes of OTA-slot headroom; SHA-256
  `FE7B50C1490030C719043D53296F05FE1681D91E5E4692C7C375F50D00E7ED54`.
  Installed **over Wi-Fi** (`wireless-install.log`), then passed a second complete
  1.2.10 → 1.2.10 wireless update with **USB plugged in and its serial reader
  closed**, taking 53.2 seconds (`wireless-unread-usb-roundtrip.log`). Evidence
  files in this paragraph and the next three are under that final build directory.
- Fresh post-reboot desktop BLE verification confirmed firmware 1.2.10, rollback pending
  false, learned mask 63 (`0x03f`), fan-history capability and schema 2; controller
  telemetry/command errors were zero. See `after-unread-usb-verify.txt`.
  D0/D1/SHT40 readings were valid; D2 remained unavailable. Fan-state synchronization
  was still required (Logic error 21); no IR command was sent to invent an anchor
  or test physical fan response. Recording remained stopped.
- The post-repair 64 KiB snapshot is
  `backups/2026-10-09-ota-nvs-recovery/boot-partition-nvs-after-repair.bin`, SHA-256
  `A852780473DC1E10BB82ED40FE7D2A6DD512E81BAE4B11710729E305EDA67FEF`.
  `nvs-preservation.json` verifies every meaningful metadata field and active raw
  pulse of all six codes through version-1 → version-2 migration. All 45 committed
  keys remain, with none missing or added: six learned blobs were compacted, 35
  other values are unchanged, and four radio keys changed (AP SSID, password,
  PMK information and calibration). Values and credential hashes are not printed.
  Available NVS capacity is **281 of 504 usable entries**, with **zero orphaned
  blob entries**. The compact record checksums pass.
- After reading the backup and resetting, a final fresh OTA entry reached READY
  with matching ACK result 0 (`final-ota-reentry.txt`). Normal cancellation then
  confirmed IDLE and stopped the temporary access point (`final-ota-idle.txt`).
  This completes the recovery's wireless upload/reboot/health-confirmation,
  retained-code and repeated-OTA-entry checks, including the unread-USB condition.
  No recording or physical IR action was initiated during these checks.
- The phone was locked and dozing at the final check; its service was absent,
  consistent with the existing 120-second background grace. An app-launch request
  was delivered behind the lock screen. This does not establish a fresh phone
  connection or a physical fan-button response; the controller verification
  above was performed through the desktop BLE helper.

### Initial diagnosis, 1.2.9 recovery and preflight evidence

- Built `firmware/out-nvs-recovery-20261009/BreezeController.ino.bin`, version
  **1.2.9**, 1,599,968 bytes, leaving 169,504 bytes in the existing OTA slot.
  SHA-256: `29668CAA2766F739F83685A4CC10A69779335054A1B3E963A39B4B91097060C4`.
  The protected `OtaUpdate.cpp`/`.h` build snapshots and `partitions.bin` are
  byte-identical to the 1.2.7 build. The existing OTA metadata selected app0 as
  valid. Preflight also matched the backed-up active 1.2.7 application to its
  known binary hash. Evidence: `firmware/out-nvs-recovery-20261009/preflight.json`.
- Installed only that application at `0x10000` over USB on COM3; the flash hash
  was verified. The first boot logged **six migrated codes, 280 available NVS
  entries, reserve 252, learned mask `0x03f`**, and firmware 1.2.9. The captured
  boot has no `0x1105` calibration error. D0/D1/SHT40 readings resumed; D2 remained
  missing. Evidence: `usb-install.log` and `usb-first-boot.txt` in the same build
  directory. This verifies migration and retained learned-slot availability,
  not physical IR response.
- Fresh BLE confirmed version 1.2.9, schema 2, fan-history capability bit 5 and
  rollback pending false. A fresh correlated OTA command reported
  **PREPARING → READY with error 0**, and sequence 1 received ACK result 0.
  Windows found one controller update network. Evidence:
  `firmware/out-fan-history-20261009/verification/ota-correlated-1791575881778848200.json`.
  The first upload attempt, `firmware/out-nvs-recovery-20261009/wireless-roundtrip.log`,
  later timed out after 150 seconds at 28.7% with USB connected and its serial
  reader closed.
- A full 1.2.9 wireless transfer **passed with USB serial actively drained**.
  `firmware/out-nvs-recovery-20261009/wireless-drained-roundtrip-retry.log`
  records acceptance; `wireless-drained-serial.txt` in that directory records
  100% upload, image/SHA-256 validation, reboot into 1.2.9 and successful startup
  health confirmation/rollback cancellation. Reboot logged `migrated=0`, 281
  available NVS entries and all six learned slots. Subsequent BLE verification
  passed with learned mask 63 (`0x03f`). This is physical wireless-update and
  retained-state proof under the stated serial-reader condition.
- Source inspection found that the installed Arduino `HWCDC.write` can wait
  about two seconds when connected USB output is full. Once-per-second sensor
  `printf` calls consequently delayed the main loop. Attaching a read-only
  serial drainer reduced measured AP startup from about eight seconds to 156 ms;
  the subsequent complete upload passed. The 1.2.10 source formats sensor logs
  into a bounded buffer and writes only when the full line fits, dropping that
  diagnostic otherwise. Sensor acquisition and one-second cadence remain
  unchanged; `OtaUpdate.cpp`/`.h` remain untouched.
- The logging guard passed full/partial/exact-fit buffer cases and 20 successive
  one-second measurements with no output space: all three probes and fault state
  still update without extra records. The six host suites passed: session,
  third-record, IR, 432-case first-send, Logic, and actual-backup NVS migration.
  The final 1.2.10 build and completed unread-USB wireless update are recorded
  above; these host checks remain distinct from that physical evidence.

- The physical controller was identified on **COM3**. USB serial reported
  `phy_init: store calibration failed (0x1105)` (`ESP_ERR_NVS_NOT_ENOUGH_SPACE`).
  Evidence: `firmware/out-fan-history-20261009/verification/usb-initial-diagnostics.txt`,
  line 16. This supersedes the earlier unknown-cause and USB-unavailable status.
  The installation and successful OTA-startup results above followed this diagnosis.
- Read-only analysis of
  `backups/2026-10-09-ota-nvs-recovery/boot-partition-nvs-before-repair.bin`
  found all **504 usable NVS entries occupied**, zero erased entries and one
  empty 126-entry spare page. The snapshot SHA-256 is
  `E2854788792F399D96DFF1D0FE58AB2C58FCB8F1150F64F1F5664CF44CB47E91`.
  The reporter is `firmware/out-fan-history-20261009/verification/nvs-summary.py`;
  it reports occupancy and integrity without exposing stored values.
- Six legacy learned-code blobs are valid, each 1,576 bytes: slots 0–1 contain
  215 active pulses each; slots 2–5 contain 191 each. They consume 314 entries,
  plus one verified-flag entry. A valid 1,904-byte radio calibration remains,
  alongside 64 entries belonging to four unindexed calibration chunks from an
  incomplete replacement. No `logic-profile` or `fan-estimate` key exists in this
  snapshot. Phone settings are separate; no claim is made that the failed
  controller profile save had ever persisted.
- The installed `FanRemote` repair reads both the frozen legacy format and compact
  version 2. Compact records retain all meaningful metadata and active raw pulse
  timings, with an exact length and FNV checksum: `36 + 2 * pulse_count` bytes,
  or 466/418 bytes for these six records. Before BLE initialization, migration
  validates, replaces and reads back one same-key blob at a time; it never
  removes the old key to make room. A subsequent boot accepts mixed versions and
  resumes migration. Normal learned writes require the new blob's conservative
  temporary entry cost plus **252 available entries**; the internal 126-entry
  NVS spare is excluded from that available count. The exception is only for a
  validated replacement whose entry cost strictly shrinks.
- `tests/ir_nvs_migration_test.py` passed against the actual six backed-up blobs,
  comparing every active raw pulse and meaningful metadata field. Checks cover
  a 64-entry migration start, interruption after two replacements, reboot
  completion, idempotence, invalid versions/checksums/counts/lengths, failed
  writes/read rejection, unrelated-key preservation and reserve rejection.
  Existing IR tests and all 432 first-send/reconnect cases also passed. The
  capacity model expects about 280 available entries after this snapshot's
  migration and allows an eight-entry profile/state budget, leaving 272. This
  is host bookkeeping, not physical flash/garbage-collection or OTA proof.
- `partitions.csv`, OTA upload protocol, image verification and rollback
  confirmation are unchanged. The 4 MiB flash remains divided as below; free
  recording space or spare application space cannot satisfy a full NVS write.

| Flash region | Offset | Size | Purpose |
| --- | --- | --- | --- |
| Bootloader, table and reserved area | `0x000000` | 36 KiB | Boot metadata |
| NVS | `0x009000` | 20 KiB | Learned codes, settings and radio data |
| OTA selection data | `0x00E000` | 8 KiB | Active-image selection |
| Application slot 0 | `0x010000` | 1,728 KiB | Firmware image |
| Application slot 1 | `0x1C0000` | 1,728 KiB | Alternate firmware image |
| LittleFS | `0x370000` | 512 KiB | Controller recording files |
| Core dump | `0x3F0000` | 64 KiB | Crash data |

- Recording history remains separate: the last confirmed controller archive
  base/total and phone cursor were all **40,974**, and recording was stopped.
  The phone's checked database retained 7 sessions, 40,974 samples, 3 feelings
  and 34 weather rows. The recovery does not resize or format LittleFS or clear
  phone data. Available recovery files are not a complete 4 MiB flash backup.
- **Downgrade compatibility:** firmware 1.2.8 and earlier does not read compact
  version-2 learned records. Preserve the verified pre-repair backup for a
  deliberate USB recovery; do not blindly boot old firmware after migration.
  The first repair wrote the currently valid application slot over USB,
  avoiding an initial pending-OTA rollback to a legacy reader. Future compatible
  builds retain version-2 reads; the existing OTA confirmation lifecycle stays
  unchanged. Both the initial recovery and final 1.2.10 wireless update with the
  serial reader closed are verified above.

## Earlier fan-setting graph deployment and blocked 1.2.8 attempt — October 9, 2026

- Follow-up after the user restarted the ESP: fresh telemetry confirmed uptime 119 seconds, firmware 1.2.7, recording stopped and learned mask 0x03f. OTA entry again failed with AP_START error 9; Windows found zero Breeze update networks. No firmware was uploaded. Evidence: `firmware/out-fan-history-20261009/verification/post-restart-ota-attempt.json`.

- Installed and hash-verified `app/out-fan-overlay-20261009/outputs/apk/debug/app-debug.apk`, SHA-256 `80B8909A74658F9517BF775BED84468ED0359EB7999042953FF9E13871291F2B`. Fan setting is a default-enabled stepped trace on the existing temperature/humidity plotting rectangle. The initial separate fan panel was removed following the user's correction; graph height is the original 346 dp. Temperature/humidity pixels do not move when the fan trace is toggled. Off is 0, known speeds are 1–9, Unknown is a gap, and the numeric reading remains available when unchecked.
- The preceding fan-history app/data build passed 80 JVM tests and lint (0 errors, 26 warnings). The corrected overlay app/test assembly and lint passed; isolated emulator rendering checks passed steps/gaps, shared plot geometry, zoom clipping, historical feeling selection, defaults and numeric readouts. Visually inspected the saved combined plot at `app/out-fan-overlay-20261009/verification/fan-setting-graph-check.png`.
- Isolated emulator database checks passed schema 5→6 migration, exact type-6/10/11 parent matching, Off/9/Unknown, duplicates/gaps/restarts, CRC checks, atomic rollback, voice-anchor mapping and twelve-column CSV. On the physical phone, backup comparison and SQLite integrity verified every original row remained: 7 sessions, 40,974 samples, 3 feelings, 34 weather rows, and the cursor. Old fan-setting values remain null. Proof and backups: `app/out-fan-history-20261009/verification`.
- Built controller firmware 1.2.8 at `firmware/out-fan-history-20261009/BreezeController.ino.bin`: 1,597,136 bytes, 172,336 bytes OTA headroom, SHA-256 `8E75508FF193586321AF286E9719DF66DAC8739CC3005E09790EA49CDA5DF4CF`. It appends a type-11 fan-setting companion every ten seconds, alongside the existing temperature records, and advertises capability bit 5. Actual-source host tests for grouped records, session behavior, IR, Logic and 432 first-send/reconnect cases passed. This image is **not installed**: OTA entry failed with AP_START error 9 twice, including after cancel/retry. No firmware upload occurred. The controller remains on 1.2.7 with learned mask 0x03f; recording remains stopped.
- Attempted the real-phone Home Reconnect instrumentation after installing the corrected APK. It reported SKIPPED because the phone was locked; this run is not physical reconnect proof. No recording or IR action was sent by that test. Earlier live service inspection independently confirmed fresh Bluetooth telemetry.
- First-touch instrumentation injected actual MotionEvents after Fan→Home→Fan and after Activity pause/resume; Power, Speed+ and Speed− each dispatched the first tap once. BLE recovery instrumentation passed with a fake transport. An initial fixture crash came from its strict preference override rejecting the new diagnostic namespace; isolated trace preferences corrected that test fixture. These tests never commanded physical IR.
- Persistent bounded diagnostics captured user taps reaching Android, successful controller acknowledgements, and send-counter increments. The user subsequently attributed the complete lack of physical response to a moved sensor/positioning and explicitly set that investigation aside. No optical-response or first-press fix is inferred from software counters, and no duplicate-send workaround was added. A later live phone check confirmed Connected, 270 ms packet age, no pending command, firmware 1.2.7 and no command error. A separate intermittent reconnect profile-persistence error 24 remained distinct from manual-send acceptance; its cause was not established at that stage. The later USB/snapshot recovery findings above confirm exhausted NVS and intact learned codes.


## Installed app and firmware 1.2.7 — October 9, 2026

- The user authorized the update. Stopped and fully downloaded the recording: controller base/total and phone cursor were all 40,974. The recording remains stopped. Before/after backups under `firmware/out-logic/deployment-20261009` preserve every existing row: 7 sessions, 40,974 samples, 34 weather rows, 2 feelings, and the cursor. SQLite schema 5 integrity check passed.
- Installed `app/out-fan-resume-diagnostic/outputs/apk/debug/app-debug.apk`, SHA-256 `AF779191EC30709629C4D1536F4AB3A9DDC8CB504AC9E11AEA11EB0181C03CF3`, verified against the phone's installed base APK. All 77 JVM tests passed; lint reported 0 errors and 24 warnings. This includes independent Warm/Cool thresholds, autosave, explicit Save reapplication, and manual IR request/acknowledgement logging plus fan send-counter dumps. Existing D2/reconnect/current-setting changes remain included.
- Uploaded `firmware/out-logic-independent/BreezeController.ino.bin`, version 1.2.7, 1,597,008 bytes, SHA-256 `8CB5E766E28C440A998A692580FB44DB71D219EED758538558D806FC514638D5`. The image has 172,464 bytes of OTA headroom and the expected partition table. The coordinating chat's logic, IR, session/sensor, and D2 record host checks passed; this chat also ran the final logic and IR suites successfully. Schema-1 saved rules migrate to schema 2 without inventing a separate cool threshold.
- Live BLE after reboot confirmed version 1.2.7, rollback pending false, all six learned slots (`0x03f`), Logic/D2 capabilities, and schema 2. D0 was 34.70 C, D1 35.84 C, room 22.15 C. D2 remained unavailable. D2=GPIO2 and ADC attenuation were verified from the exact board variant/build; the physical cause of the invalid reading is not established.
- No guessed fan anchor or physical IR command was sent during deployment. The controller reported unknown current fan state and requested synchronization (logic error 21), rather than assuming the old saved speed. The phone was dozing after deployment, so post-update onscreen verification/profile upload awaits reopening it. Read-only phone preferences retain Warm 73 F, Cool 72 F, and all three selected probes.
- The user reproduced an ineffective first Power/Speed press after any return to the app, including quick app switches, on firmware 1.2.5. Two Speed+ presses advanced the controller estimate from 1 to 3 while the physical fan reached 2. Source audit confirms the controller increments after its transmit routine; Android does not increment the estimate locally. The optical/fan-response cause remains unresolved, and no automatic duplicate-send workaround was added. A failed press under the new diagnostic build has not yet been captured.

## Warm/Cool room fields — October 9, 2026

- Installed the coordinating chat's `app/out-logic-room-fields/outputs/apk/debug/app-debug.apk`, SHA-256 `53056E31C629670DE0BC76C33AE70DF6EA4294AA1EC8E5CB101B2E0B5C34137E`. Each rule now contains its editable room-temperature cutoff: Warm at or above, Cool below. The two fields mirror the shared boundary. Existing D2, current-setting and connection-readiness changes remain included.
- All 76 JVM tests passed in this build; added lint passed with 0 errors and 24 warnings. A source review confirmed linked input/save behavior; the current Logic UI instrumentation does not specifically exercise the newly added cutoff fields. Installation preserved the ongoing controller recording. Firmware installation remains pending.

## Current fan setting and first-tap readiness — October 9, 2026

- Current fan setting now follows fresh synchronized controller values, including 0 for Off. An unknown/stale controller state displays Unknown instead of the old saved anchor. Focus alone no longer freezes updates; actual edits and explicit corrections remain protected through command acknowledgement and the next fan-status packet.
- Profile upload no longer synchronizes an unknown controller from a possibly stale saved speed. Only a pending explicit user correction sends the synchronization command; automatic control otherwise waits for a known fan state.
- Manual Power/speed buttons wait until connection setup and command transport can accept a tap. Stale telemetry/test rejection updates feedback, and reconnect clears previous success. Rejected manual commands remain unqueued and are never replayed automatically. These changes resolve observed source-level readiness/feedback problems; physical first-press reception remains unverified.
- Installed APK: `app/out-fan-state-20261009/outputs/apk/debug/app-debug.apk`; SHA-256 `69696954B736843D3494CCA3F1A7CD509069CB35575DED2264CB0A0125A9DE3C`. App/test assembly, all 76 JVM tests, and lint passed (0 errors, 24 warnings). Pixel fake-transport checks passed startup queue/readiness, explicit-versus-stale anchors, ACK ordering, no replay, reconnect and legacy capability reset. On-device sensor/IR UI checks passed all five readings including D2, missing-D2 isolation and command readiness after updating the fake connection to remain isolated from the real reconnect lifecycle.
- Firmware 1.2.6 was built by the coordinating fan-logic chat: `firmware/out-logic/BreezeController.ino.bin`, 1,596,368 bytes, SHA-256 `BBDD5C49BBF24EB76E66A91A71FB2AC6EE061E5AAE9B847E1D323BD48E259B9C`, 173,104 bytes below the OTA slot limit. Host checks for actual sensor functions, D2 paired-record writing, the logic engine and IR control passed. Installation remains pending permission to end the active recording; no controller restart or record stop was issued here.

## D2 body probe and highest-body graph — October 9, 2026

- Home now includes D2. History computes Highest body temp from D0/D1/D2 only, excludes SHT40 room temperature, and keeps the D2 individual trace unchecked by default while retaining its numeric readout. Old recordings keep missing D2 values rather than fabricating a temperature.
- Phone schema 5 adds nullable D2 and verified companion-record identity. A type-10 record enriches its matching type-6 sample and advances the durable cursor in the same transaction. Voice entries anchored to companion records still resolve to the correct night. CSV appends `third_body_temperature_c` after the unchanged first ten columns.
- `app/out-d2-final-20261009` app/test assembly, 70 JVM tests, and lint passed (0 errors, 26 warnings). Pixel 8 Pro instrumentation passed isolated database migration/download/rollback/CSV/voice-anchor checks and highest-body graph/rendering/defaults checks. The final capability gate additionally preserves old motor telemetry acknowledgements without interpreting speed/angle as D2.
- The installed APK SHA-256 is `65F9C5C54A2490B6D9F6BC2056F7D4ECDED179EF0F4C87F06E00CC8DCE757C27`. Before/after database comparison preserved all 7 nights, 40,829 existing samples, 2 feeling entries, and 34 weather rows; 13 new samples arrived while testing. Schema migrated from 4 to 5 and SQLite integrity check passed.
- Hardware D2 evidence remains pending the matching firmware installation. The controller was observed connected on v1.2.5 with an active recording; its D2 value correctly remained missing. The recording was not stopped for this update. Backups and data-preservation evidence are retained under `app/out-d2-20261009/verification`.

## Visible temperature range correction — October 7, 2026

- The earlier time zoom retained whole-night temperature bounds, imposed a four-display-degree minimum, and used integer tick labels. This could flatten small changes even after zooming into a short interval.
- The temperature axis now fits only enabled temperature traces in the visible time window, plus a small proportional margin. Offscreen extrema are excluded; valid segments crossing the viewport edges contribute only their visible interpolated values. Missing or disconnected segments do not invent boundary temperatures. Humidity retains its independent 0–100% scale.
- Narrow ranges use fractional degree labels. Pixel instrumentation verified that 0.2°C local variation occupies at least 45 dp of vertical trace height despite 10°C/40°C extrema elsewhere in the night. Tests also cover disabled-trace outliers, Fahrenheit/Celsius labels, hourly boundary interpolation, refresh/reset, and the existing gesture/annotation behavior.
- App/test assembly, JVM tests, lint (0 errors / 23 warnings), and the expanded on-device history suite passed. The current working-tree voice additions were preserved without editing their implementation. A fresh `app/out-temperature-scale` build avoided a locked older resource output.
- Installed APK SHA-256: `F6EFD0F1CBD431A6FA7A3734A6AA95AB3E26C0B29138B2B468020081110813B9`. Final controller diagnostics remained connected with storage ready, both error fields zero, and recording active.

## Bluetooth connection recovery — October 7, 2026

- Physical Pixel 8 Pro logs identified repeated `DeadObjectException` in `BluetoothGatt.writeCharacteristic`. Breeze retained a stale GATT handle across a Bluetooth off/on cycle and treated every rejected submission as generic busy.
- Android now uses API 33 write status codes, retries only rejected BUSY submissions for at most six seconds, closes expired handles before failing callbacks, reconnects after adapter state changes/disconnects/timeouts, and checks telemetry with a monotonic clock. Accepted commands with an uncertain outcome are never replayed automatically.
- App assembly, all 14 existing JVM tests, lint (0 errors / 13 existing warnings), and Android-test assembly passed. Pixel instrumentation passed the new fake-transport recovery scenarios and existing sensor/IR UI checks.
- A physical Bluetooth off/on cycle closed the old handle, showed `Bluetooth off`, reconnected to firmware v1.2.1, and resumed the download from the phone's durable cursor without intervention.
- The connected controller reported zero free bytes, storage not ready, and error 6. Its retained range was `23731..38082`. The repaired app is downloading the contiguous backlog to phone storage before acknowledging reclamation; controller recovery is recorded separately when verified.
- Full controller storage disables Start recording, keeps Stop available for an active recording, and displays download progress. Android reports reclaimed storage only after controller metadata confirms the acknowledged range is retired.
- Physical completion subsequently confirmed `controllerRecords=38084..38084`, `freeBytes=507904`, `storageReady=true`, `telemetryError=0`, and `commandError=0`. All retained records had reached the phone, and the user started another recording successfully. No controller format, erase-all, or firmware change was performed.

## Recorded-night zoom and feeling entries — October 7, 2026

- Recorded-night graphs have a time viewport supporting pinch zoom to a one-minute minimum, bounded horizontal panning, tap-to-inspect, and double-tap/Reset zoom. The viewport survives periodic same-night refreshes and new samples. Vertical swipes still scroll History.
- Users can add/edit a date/time, Hot or Cold, and intensity 1–10 for each selected night. Notes are stored separately from sensor readings and appear as orange Hot triangles / blue Cold circles with dated rating details on tap.
- Database schema v3 adds only the feelings table/index. A consistent pre-migration phone backup (`backups/2026-10-07-before-ble-recovery/breeze-phone-before-feelings-v3.db`) passed SQLite `quick_check`, with schema v2, 5 sessions, 38,104 samples and 26 weather records. Sensor CSV remains unchanged.
- All 14 JVM tests passed. Pixel instrumentation passed the new feelings migration/persistence/validation suite, synthetic touch/rendering checks for zoom/pan/reset/scroll/markers, BLE/storage recovery regression checks, and existing sensor/IR UI checks. Two initial gesture-fixture failures were resolved using the device's minimum scaling span and ScrollView takeover behavior; the final gesture suite passed.
- The final app was installed over the existing Pixel 8 Pro app. Physical inspection confirmed the History controls and date/time/Hot/Cold/intensity form. No demonstration feeling entry was added to the user's real history.
- Final APK: `app/out-ble-recovery/outputs/apk/debug/app-debug.apk`; SHA-256 `EE74DC86FBF9292BE03CE45DB0CF163FF3B50222C161759C2F7268EA99A4D945`. Lint: 0 errors, 21 warnings. Final live diagnostics showed firmware v1.2.1 connected, storage ready, 507,904 free bytes, both error fields zero, and the user's recording active.

## Computer Wi-Fi OTA — October 3, 2026

- USB flashed only the v1.2.0 application at `0x10000`; existing NVS, OTA data layout, and LittleFS were not erased.
- The Windows uploader discovered the controller SoftAP, joined it, sent all 1,585,328 bytes of v1.2.1, and received the server's accepted response.
- Serial evidence showed upload progress through 100%, SHA-256/image validation success, reboot into `BREEZE-FW:1.2.1`, resumed sensor readings, and `startup health check passed; rollback canceled`.
- v1.2.1 SHA-256: `617F5E5A2168A2B0005D5FCDD72070C3D70300F77B5397816BE0DD25B15A5361`.
- A follow-up v1.2.2 build adds one-second retry handling if rollback confirmation transiently fails. Compile passed at 1,585,328 bytes with SHA-256 `0EF5F08EA1F8DC87415038460E19417C20391C68D5BD8FFA684BCC0DF5074CCA`; it is prepared but was not flashed during this validation.
- The Android OTA attempt failed twice on the physical Pixel before HTTP transfer because Android returned `EPERM` while binding the upload socket to the selected local network. No firmware bytes were written in either attempt. That implementation is removed from the live app and preserved under `backups/2026-10-03-android-ota-attempt`.

## Archived Android OTA validation — October 2, 2026

## Software and build evidence

- The existing BLE capability remains 7. The installed v1.1.0 USB bootstrap reports version bytes `1.1.0` and OTA feature bit 0. Current v1.1.1 source and test image report `1.1.1`, add pending-verification bit 0 in capability byte 9 and opcode 7 action 3 Confirm, and retain Enter/Cancel plus type-7 status telemetry without changing the sensor/Dreo message layouts.
- The firmware uses a temporary WPA2 SoftAP and a raw, fixed-length `POST /firmware` to the inactive OTA slot. It checks the session token, declared length, slot capacity and streamed SHA-256 before selecting the new image, then delays reboot so the HTTP response can finish.
- The ESP32-C6 compile completed with Arduino-ESP32 3.3.12 at **1,584,528 program bytes** in a **1,769,472-byte (`0x1B0000`) application slot**, leaving **184,944 bytes**. This is build evidence, not OTA upload or boot evidence. The v1.1.0 USB-bootstrap image and v1.1.1 OTA-test image are distinct artifacts; do not reuse one version's checksum for the other.
- The compiled partition output and sketch `partitions.csv` agree on two `0x1B0000` OTA application slots and `0x80000` LittleFS. The `PartitionScheme=huge_app` FQBN setting is only the Arduino compile ceiling; the sketch CSV is the authoritative flash layout.
- The Android source implements document-provider selection, `.bin`/size/image/version validation, a single in-flight update, API-33 `WifiNetworkSpecifier` approval, a network-bound raw upload, progress/error reporting, BLE reconnect/version validation, and explicit running-image health confirmation before rollback is canceled. Focused JVM tests cover the image parser and OTA protocol parsing. Source and tests do not establish a successful real phone/controller update.
- OTA uses Arduino-ESP32 facilities and Android platform APIs. It introduces no new external firmware library or Android dependency.

## Physical baseline completed

The physical controller has received the complete v1.1.0 USB bootstrap with the OTA partition layout. This is wired bootstrap evidence only; it is not evidence that the Wi-Fi OTA path, Android workflow, v1.1.1 image, post-update sensors/Dreo behavior or recovery path works.

## Required OTA/app/device validation — pending

None of the following v1.1.0 → v1.1.1 OTA checks is claimed complete until it is actually performed and recorded on the physical phone and controller:

1. Before OTA, stop recording, download and confirm all controller-held records, then verify the USB-bootstrapped controller reports BLE capability 7 and firmware v1.1.0. Confirm retained phone history plus working controller recording/download after the LittleFS migration.
2. In **Setup → Firmware Update**, use the real Android document picker to select `firmware/out-dreo/BreezeController.ino.bin`. Confirm it displays v1.1.1 plus the expected filename/size, and confirm clear rejection messages for wrong/truncated/oversize/magic/descriptor/chip-invalid files.
3. Start one update and confirm Android shows one normal system prompt for the controller's temporary Wi-Fi on that join attempt. Verify the phone does not use a home router or obtain Internet through the SoftAP, and that unrelated phone traffic is not globally rebound.
4. Observe the real BLE Ready status and slot capacity, raw HTTP upload, byte/progress updates, Verifying and Rebooting. Confirm token, fixed Content-Length and SHA-256 failures are rejected without activating a partial image.
5. Confirm the phone automatically returns to BLE after reboot, sees v1.1.1 with capability byte 9 pending, verifies the selected firmware marker, sends opcode 7/action 3, and reports completion only after that command is acknowledged successfully. Confirm subsequent capability telemetry clears the pending flag. Exercise a safe confirmation-failure path and verify it is reported instead of silently accepting the image.
6. Retest live D0/D1/SHT40/humidity, recording/download, manual Dreo learning/transmission/target behavior, and verify there is no unexpected IR command during boot/update.
7. Power-cycle the controller and repeat v1.1.1, sensor, recording and Dreo checks. Separately test a safely interrupted upload to confirm the previously running partition remains bootable; do not remove power while deliberately testing flash-write interruption unless a USB recovery path is ready.
8. Exercise RESET/download mode and a complete USB recovery upload, then repeat the post-recovery version/sensor/Dreo checks.

The earlier entries below remain historical evidence for their named builds. They do not prove that the v1.1.0 → v1.1.1 Android Wi-Fi approval, physical OTA upload, reboot/version/health confirmation, sensor/Dreo regression, power-cycle, interruption or USB-recovery flow has passed.

# History selection and combined graph — October 2, 2026

- Inspected the live History spinner: the phone had one saved night, and its one-item dropdown overlaid the selected row, making opening it look unchanged. Replaced it with an explicit Choose recorded night button and a titled single-choice dialog with night count, date, reading count and end time.
- Temperature and humidity now share one time axis. Temperature uses the left scale; humidity uses the independent right 0–100% scale. Six colored checkboxes independently toggle D0, D1, SHT40 temperature, outdoor temperature, indoor humidity and outdoor humidity. Selection is saved in the existing app preferences. Outdoor traces stay dashed, missing data stays missing, and temperature scaling follows visible temperature traces.
- Updated source: MainActivity.java and GraphView.java. Prior copies: `backups/2026-10-02-history-032209`. No database, firmware or recording logic changed.
- Android build and 14 existing JVM tests passed; lint 0 errors / 13 warnings. Installed updated APK on the Pixel 8 Pro with existing data retained. APK SHA-256 `42F485EB3ADCE15D464F6EEEE366D764E55863FD6FC8CC6D839A18AE2D60EABC`.
- Actual phone verification: opened chooser, selected October 1 12:57 AM night with 1024 readings and end 9:28 AM, confirmed the graph loads. Unchecked D0, navigated Home → History, and confirmed only D0 stayed unchecked; visually verified its line disappeared while SHT40 and humidity remained. Independently toggled humidity and verified its trace disappears/reappears. Restored all six checkboxes and left the saved night open.
- Screenshots inspected: `docs/screenshots/history-night-list.png`, `history-d0-hidden.png`, `history-humidity-hidden.png`, `history-combined-final.png`. This older night predates D1 and has no downloaded outdoor values, so those checked traces correctly have no data to draw.

# Ten-second sampling, graph colors and Home recording — October 2, 2026

- Both D0/D1 thermistors and SHT40 temperature/humidity now sample every 10 seconds. Outdoor model records remain hourly. No contact-rejection or automatic fan rules were added.
- Home has Start recording directly below humidity, switching to Stop recording from controller state. The existing Setup control remains. Colors and matching legends: D0 green, D1 coral, SHT40 gold, outdoor temperature dashed blue; SHT40 humidity purple, outdoor humidity dashed cyan.
- Source snapshot: `backups/2026-10-02-ten-second-031528`.
- Android APK/test build and all 14 JVM tests passed. Lint: 0 errors, 13 warnings. Updated Pixel 8 Pro using install -r, preserving history/settings. Fake-controller UI instrumentation passed, including Home button placement, Start/Stop routing, and the existing raw-reading/manual-IR checks. No physical recording or IR transmission was started by those tests.
- Actual firmware-function test passed 10-second deadlines for D0, D1 and SHT40, no early reads, recording Start/Stop, sensor reads after Stop, timer rollover and marker-shifted deadlines.
- Firmware compiled at 901,838 bytes flash / 21,164 bytes static RAM. Reidentified the same ESP32-C6FH4 on COM3 (base MAC 10:BD:A3:9F:9F:84), uploaded and verified hashes, automatic reboot. Existing log/NVS partitions were not formatted.
- Live serial samples arrived at host offsets 6.070 s, 16.073 s, 26.074 s: intervals 10.003 s and 10.001 s. Latest sample D0 23.06 C, D1 22.87 C, SHT40 21.97 C / 63.4% RH. D1 is now valid. Serial evidence: `docs/ten-second-serial.txt`.
- Visually inspected actual phone Home and existing-session temperature/humidity graphs: `docs/screenshots/ten-second-home.png`, `ten-second-temperature.png`, `ten-second-humidity.png`. Home shows live D0/D1/SHT40/RH and the requested button. Historical selected session predates D1 and has no downloaded outdoor records, so those traces correctly remain absent; their matching colored legends are visible.
- APK SHA-256 `212BE9D98F408130CC7BD1AF5D196561D6CB6A29F5D341CA35C81F2BDB22015B`; firmware SHA-256 `2FA1E815CF4A6975A21F5766E511991720500F60EB344BDED931BDFE9978B2BA`.

# Firmware upload and live boot — October 2, 2026

- User connected the controller. Identified COM3 as ESP32-C6FH4 revision 0.2, base MAC `10:BD:A3:9F:9F:84`.
- Saved all 4 MiB to `backups/2026-10-02-before-dual-probe-flash/controller.bin`; SHA-256 `E850D2422BD76CDCC9CB04A43E9A9C6EFAA07FE1C78270B5B7CB730299AC422B`. Compared the existing partition table with the new compiled table: exact match.
- Uploaded the previously built `firmware/out-dreo` image; esptool verified each written hash. No erase-all or filesystem format was used; NVS and recording partitions were outside the uploaded ranges. The board reset automatically.
- USB Serial confirms `Breeze IR-v7 ready`, IRremoteESP8266 2.9.0, no learned commands, unknown estimated speed and Auto OFF. Initial samples: D0 22.78 °C, SHT40 22.67 °C, RH 63.0%; D1 `nan` (no valid reading). This establishes running firmware and live sensor output, not independent calibration.
- No IR transmission or recording was started. Physical D1 wiring/contact test, remote learning/replay and visible phone UI remain pending; the phone was still locked.
- Serial evidence: `backups/2026-10-02-before-dual-probe-flash/after-upload-serial.txt`.

# Raw two-probe test and manual IR — October 2, 2026

- User correction governs this revision: collect data first. No sudden-drop/contact/recovery rules or automatic temperature algorithm exist. Auto enable returns rules-pending. Both raw D0/D1 values remain visible, graphed and exported. SHT40 wiring/calibration is preserved.
- Android app and test APK build passed; 14 JVM tests passed; lint has 0 errors and 13 warnings. Build output remains `app/out-sensors`.
- Actual `FanRemote.cpp` host tests passed: decoded/raw capture and sending, NVS reload, rate limiting, manual targets, unknown endpoint behavior, external-remote invalidation, timeout/cancel, simulated save failure, disconnect cancellation, clear, and absence of temperature-driven transmissions. These use IR/NVS spies, not hardware.
- Actual sensor/session firmware-function tests passed: two probe conversion and availability, recording guards and idempotence, original 30-second body/60-second SHT cadence, live readings after Stop, rollover and staggered sample deadlines.
- ESP32-C6 build passed with isolated Arduino-ESP32 3.3.12 and IRremoteESP8266 2.9.0: 901,838 bytes flash and 21,164 bytes static RAM. Binary SHA-256: `AAA088EC63F2889202A27BF2760D09F089B45B19B20F39BB50199148867DB642`.
- App and test APK installed on verified Pixel 8 Pro (`192.168.1.83:5555`) with `install -r`; no app data was cleared. APK SHA-256: `6BA79E5A0354675047E8B6A1564AEF1004746A41AB36A56DB0F5F9AA3211F85A`.
- Isolated on-phone storage instrumentation passed: version-1 database migration retaining existing sample/session/cursor; new D1 and user marker storage/export; mixed old/new records; durable cursor, duplicate/gap handling, restart recovery, CRC rejection, UTC/session boundaries, CSV and weather storage; live ZIP lookup plus 14 hourly weather records.
- The first storage run exposed an overly specific test expectation for the SQLite numeric string (`24.0` versus `24`). The assertion now parses the exported numeric field and checks the marker independently; the rerun passed.
- Visible UI instrumentation did not pass: the phone was locked/dozing, so the launch visibility assertion could not succeed. The runner now detects the locked phone and reports SKIPPED. Visual layout, fake-controller UI interactions, actual BLE version-7 telemetry and physical probe behavior remain unverified.
- No XIAO USB port was present (only COM1/COM5/COM6); this firmware was not uploaded. No physical IR send was initiated. Real reception, persistence across a physical reboot, carrier/frame behavior, fan response, single-step +/- behavior and transistor wiring still need the ordered hardware test in SETUP.md.
- Source before this work is under `backups/2026-10-02-dreo-ir`. Provisional temperature-policy files were moved to the Recycle Bin. Motor/servo code remains absent from active source.

The entries below are historical; current results above supersede them.

# Sensor-only update — September 30, 2026

- Replaced the opening controls/graph screen with Body temp, Fan temp and Humidity in a dark granite-green theme. Connection and recording are in Setup; History remains available.
- Removed motor/servo/joystick execution and all actuator commands from active firmware and app. Body stays on D0; the existing SHT40 uses D4/D5. Obsolete source and tests went to the Recycle Bin; pre-change source is preserved in backups/2026-09-30-sensors.
- ESP32-C6 build passed: 835,092 bytes program storage, 20,348 bytes static RAM. Confirmed ESP32-C6FH4, base MAC 10:BD:A3:9F:9F:84 on COM3, uploaded and verified flash hashes. No log partition format or erase-all was performed.
- Android build and all 7 protocol tests passed. Lint: 0 errors, 10 warnings. Installed the app on Pixel 8 Pro without clearing app data.
- On-phone fake-controller checks passed: three readings visible at launch, missing/stale handling, °F/°C switching, recording routing, old-firmware gating and absence of motor controls.
- Isolated phone database checks passed: durable cursor, duplicate suppression, gaps, restart persistence, CRC, timestamps, session boundaries, sensor CSV, stored weather and live ZIP/weather lookup.
- Actual firmware function host tests passed: thermistor conversion and faults, start/stop idempotence, clock/storage guards, 30-second body and 60-second fan cadence, sensing after Stop, timer rollover and probe recovery.
- Earlier validation below is historical and describes superseded motor builds.
# Validation — September 28, 2026

## Passed

- Android debug APK built with Android Gradle Plugin 9.0.1 / Gradle 9.2.1 / JDK 21, Java source target 17; compile/target SDK 36, minimum SDK 33.
- Android lint: **0 errors**, 16 warnings (English-only UI text, draw allocations, SDK/tool version recommendations, backup configuration).
- **9 JVM protocol tests**: physical dial mapping, reverse mounting, invalid configuration, absent sensor representation, signed temperature, large uptime, CRC corruption rejection and acknowledgement cursor encoding.
- Native host C++ tests against the firmware's actual `Control.h`: B3950 conversion, disconnected/shorted probe limits, heating thresholds, cooling hysteresis, optional room/RH contribution, missing optional sensor fallback and dial order validation.
- Arduino-ESP32 **3.3.7**, board **XIAO_ESP32C6**: firmware compiles, **847,098 bytes flash (64%)**, **20,556 bytes static RAM (6%)**.
- Installed package `com.aaron.breeze` on USB-connected **Pixel 6**, Android 16 (API 36), serial `19151FDF6005K7`. Pixel 8 Pro was not modified.
- Pixel SQLite instrumentation in a temporary isolated database: durable cursor, restart recovery, duplicate suppression, rejected gaps, CRC failure retention, exact UTC timestamps, session end markers, weather persistence, CSV values.
- Pixel live networking test: ZIP **53214** lookup succeeded; Open-Meteo returned **14 hourly weather records** for a 12-hour test window. Temporary test data was not added to the app's real history.
- The app launched successfully and its main dashboard was captured and visually inspected (`screenshots/pixel6-live.png`).

## Not yet verified on the physical controller

No XIAO ESP32C6 USB device was present. Only COM1 and Bluetooth serial ports COM5/COM6 were listed. Nothing was flashed, and no servo was driven.

Still required with the assembled hardware: thermistor divider measurement and calibration; SHT40 readings; actual joystick center/direction/button; BLE connection, offline overnight recording, interruption/resume and acknowledged flash cleanup; deliberate power-loss recovery during append and generation rotation; servo voltage/current/pulse range and four measured dial positions. These hardware checks are separate from compilation and phone database tests.

The phone locked during further interactive UI checks; additional graph/calibration screen inspection depends on unlocking it. No lock or security setting was changed.

## Controls and sampling revision — September 28, 2026

- Updated and reinstalled the debug app on the USB Pixel 6 (`19151FDF6005K7`). Existing app data was retained.
- Connected dashboard uses a test controller to verify the connection box hides, Off/High/Medium/Low remain visible, and Start/Stop dispatch the correct commands without a confirmation dialog. Screenshot: `screenshots/pixel6-connected-test.png` (simulated controller state, not a real BLE connection).
- Visually inspected the compact body/fan temperature row and Rules screen; verified the Save button opens the name prompt. Screenshots: `screenshots/pixel6-controls.png`, `screenshots/pixel6-rules.png`.
- Pixel instrumentation passed named rule save/load with isolated preferences, all temperature fields restored, calibration preserved, and top Save/Load visibility. No test profiles or records were written to the user's settings/history.
- Android build and all 9 protocol tests passed; lint reports 0 errors and 20 warnings (existing categories: hardcoded English text, drawing allocations, SDK/version and backup guidance).
- Host tests compiled the actual firmware Start, Stop, sensor reading and scheduling functions with hardware spies. Passed immediate rule application on Start; unchanged speed, angle and PWM on Stop for Off/Low/Medium/High; storage/clock/calibration guards; 30-second body and 60-second fan sampling; timer rollover; staggered deadlines; no recording or automatic changes after Stop.
- Existing native control-math tests still pass.
- Matching XIAO ESP32C6 firmware compiled successfully. No physical controller was connected or flashed. App actions 7/8 require this firmware; physical BLE, sensor cadence and servo behavior remain unverified.
- Source, firmware, tests and docs before this revision are preserved in `backups/2026-09-28-controls`.

## SG90 motor test and first upload — September 30, 2026

- Live USB identification: COM3, ESP32-C6FH4 revision v0.2, base MAC `10:BD:A3:9F:9F:84`. User confirmed SG90 and signal on D7; installed board variant verifies D7 = GPIO17.
- Backed up all 4 MiB before upload: `backups/2026-09-30-motor-test/esp32c6-before-upload.bin`, SHA-256 `EF8259EC4C35F8093108A1FBF50AFCAE82074570B192EA82E7BF78A743333572`. Source/docs/tests were also backed up there.
- Firmware compiled for `esp32:esp32:XIAO_ESP32C6`: 847,962 bytes flash, 20,572 bytes static RAM. Uploaded to COM3 with the Arduino CLI and verified flash checksums; no erase-all or filesystem formatting. Build artifacts: `firmware/out-motor-test`.
- Updated Android app installed on the Pixel 8 Pro (`192.168.1.83:5555`) at the user's correction. Pixel 6 had received the initial update before that correction.
- Actual BLE advertising discovered `Breeze Fan` at `10:BD:A3:9F:9F:86`; Pixel 8 connected and received the motor-test extension, showing `Connected` and `Motor released · set the starting position`. No physical movement was initiated by the agent.
- New Motor test screen provides explicit initial positioning, +/- buttons, a 2–100 µs step slider, commanded position, and Release motor. Changing the slider sends no movement. Tests work without sensors, recording, filesystem formatting or servo calibration.
- Host test `tests/motor_test.py` compiles the actual firmware movement functions with PWM spies. Passed D7 mapping, explicit initialization, +/- microsteps, range guards, recording guard, release/reinitialize, and hardware-error reporting. Prior session/Start/Stop/cadence host tests also pass.
- Android protocol tests: 13 passed. Pixel 8 instrumentation passed fake-controller UI checks including slider-no-motion, direction/amount routing, initialization, range, recording and release guards. These automated checks do not establish mechanical direction or travel.
- Fixed a real connection UI issue: scanning while already connected could overwrite status with `No controller found`. A ready connection is now kept; a single discovered controller connects automatically; ready status takes precedence over scan status.
- Physical servo direction, deadband and fan-knob travel are for the user to observe. Firmware intentionally leaves PWM off at boot; the first position command can be larger than a microstep because SG90 provides no physical position readback to the ESP.

## Expanded motor test range — September 30, 2026

- User explicitly approved motor-test pulse endpoints 500–2400 µs and doubled largest step from 100 to 200 µs. Smallest/default step remains 2 µs. No position slider was added.
- Final clarified boundary behavior: retain the selected slider amount. A request beyond an endpoint moves only to that endpoint; the next opposite-direction tap uses the original full selected step. Android disables a direction only when already at its endpoint.
- Firmware motor telemetry is version 2. Android requires it and accepts 500–2400 µs. Its approximate angle display uses 500=0°, 1500=90°, 2400=180°. The normal saved fan-calibration mapping is unchanged.
- Actual firmware function host tests passed expanded bounds, endpoint saturation, full-size reverse step, initialization/recording/PWM-error guards. Android unit tests passed 16/16; build and lint passed (0 errors, 20 warnings). Fake-controller instrumentation includes assertions that endpoint motion does not alter slider progress or the next reverse-step amount.
- Full physical shaft range is not inferred from the displayed angle; the user observes the actual SG90 travel. Test-range approval is not a measurement of mechanical endpoints.
- Final expanded-range firmware (847,996 bytes flash) uploaded to COM3 and checksum verified. Final APK installed on Pixel 8; fake-controller instrumentation passed endpoint/unchanged-slider tests. Reopened Motor test and connected to actual version-2 telemetry; no motor movement was initiated by the agent.

## Start motor testing at either end — September 30, 2026

- Replaced forced-center initialization with direct **Start at 0°** (500 µs, action 12) and **Start at 180°** (2400 µs, action 13) controls. No center command or confirmation dialog is inserted by these buttons. Firmware remains motionless at boot.
- Motor telemetry extension is now v3, required by the updated phone app. Existing range, selected step size, boundary saturation, recording guards and saved fan calibration are preserved.
- Host tests execute the actual firmware functions and passed initialization from released PWM at both ends and full-size first steps away from each endpoint, with exactly one PWM write for initialization and no intermediate center target. Recording blocks either start action.
- Firmware build passed at 848,040 bytes flash / 20,572 bytes static RAM, then uploaded to COM3 with flash checksum verified. Android app/test APK build and 16 unit tests passed; lint 0 errors/20 warnings.
- Pixel 8 update installed. The expanded fake-controller instrumentation runner stalled before its endpoint checks and was stopped; do not count that device test as passed. The actual app was relaunched and connected for visual verification. No physical motor movement was initiated by the agent.

## Continuous-rotation motor test — September 30, 2026

- User confirmed replacing the positional SG90 with a three-wire continuous-rotation servo, keeping signal on D7. Added Direction A/B, speed 1–100%, timed turns 20–2000 ms, Stop, Release, and separately saved neutral trim 1400–1600 µs. No endpoint initialization or angle estimate applies to this motor.
- The ESP one-shot timer sends neutral independently of main-loop sensor/storage work. Disconnect stops driven motion without energizing released PWM, clears queued commands, and invalidates older connection generations. Duplicate accepted jog sequences do not repeat motion. This test build rejects all legacy positional/fan/Start commands and preserves their saved configuration.
- `tests/continuous_test.py` compiles actual firmware motion functions with timer/PWM spies. Passed timed and explicit stops, both full-speed outputs, retry suppression, no forced position, invalid packet/recording guards, stop trim, disconnected/stale-command rejection, PWM/timer failures, and rejection of positional controls. Retained positional motor/session regression suites also pass with continuous mode explicitly disabled in those harnesses.
- Final firmware compiled to 848,348 bytes flash and 20,604 bytes static RAM. Application binary SHA-256: `F3EE452521F29A3634067978C636186AE596CB91CF73381910D083E3850926E3`. Confirmed the same ESP32-C6FH4 / base MAC `10:BD:A3:9F:9F:84` on COM3, uploaded, and verified flash hashes. Boot leaves PWM off; no filesystem format or erase-all was used.
- Android app/test APK and lint passed; 15 protocol tests passed. Updated Pixel 8 Pro (`192.168.1.83:5555`) without clearing its data. Isolated on-device UI instrumentation passed continuous controls, sliders sending no commands, running-state busy handling, Stop/Release priority and error cleanup, old-firmware gating, and preservation of calibration/rules. These checks use a fake controller and do not establish physical motor motion.
- Real BLE connection subsequently showed continuous firmware telemetry. The user reported no physical movement. The screen then showed 21% speed, 367 ms duration, and 1600 µs neutral. Stopped telemetry alone cannot establish that a jog was sent or that the shaft moved. A later live slider adjustment exposed an Android range bug: setting minimum before maximum clamps the intended 1400 minimum to Android's default maximum, allowing invalid trim values. Correction and verification are tracked below.
- Corrected slider initialization to set maximum before minimum. Added exact min/max assertions for speed, duration and trim; the updated isolated Pixel 8 instrumentation passed. Corrected APK SHA-256: `67DAE8A98799A7FEE3068F3EF3E301311B0EBE9EB45A3FD9FC18D113D9B2BF6E`. Reinstalled, relaunched and connected to the real controller. Physical no-motion report remains unresolved until the user observes a valid full-speed jog; the slider defect alone is not established as its cause.

## Continuous run option — September 30, 2026

- Added user-requested Continuous mode alongside Timed mode. Action 17 starts persistent motion without a duration timer, and permits explicit changes to direction/speed during a continuous run. Stop, Release and disconnect end it. Changing mode/sliders alone does not issue motion.
- Motor telemetry v5 reports continuous running separately from timed motion; older v4 retains timed operation in the app. Motor boot state, positional-command rejection, neutral trim, saved settings and records remain unchanged.
- Expanded actual-function host tests passed continuous motion beyond a minute of simulated time, stale timeout callbacks, direction/speed replacement, duplicate suppression before/after Stop, Stop/Release/disconnect, invalid duration/payload rejection, stale connection rejection, and mutual exclusion with timed jogs. Read-only firmware review found no additional issue.
- Started the ESP build before completing the Android work. Final firmware compiled at 848,526 bytes flash / 20,604 bytes static RAM and was uploaded to the confirmed ESP32-C6 on COM3 with flash hashes verified. Binary SHA-256: `8791532A6D8F009827BBA258E1428CB49571FCF7FC71F2170D054548CC2F09B2`.
- Updated the ADB UI helper to use a fresh temporary XML path per read. A failed UI dump can no longer silently return a stale screen tree.
- Android app/test builds and 19 protocol unit tests passed; lint reported 0 errors / 21 warnings. Final APK SHA-256: `2FE62C4B7DE76611CD1D7C92BB6463518B5565EA584221FA720CA2A6563474A3`. Installed on Pixel 8 with data retained; isolated device instrumentation passed the Timed/Continuous mode routing, no-motion mode/slider changes, v4 fallback, running-state direction/speed updates, and Stop/Release queue checks.
- Relaunched the actual app, opened Motor test and connected to the ESP. Final live screenshot `screenshots/pixel8-continuous-option.png` shows Continuous selected and controller-reported Direction B at 70%, command pulse 1150 µs. The assistant did not tap a physical motor direction button. This verifies the live app/controller state, not measured shaft movement or torque.


Final live verification: after upload, Setup reported Connected and Live readings / recording stopped (sensor firmware capability accepted). Home displayed all three live values. Screenshot: docs/screenshots/sensor-home.png. APK SHA-256: 28CBEE0E134A35D13EB9339240415070506750767125965A56A82A7C9BFDB0B6. Firmware SHA-256: 2B07CC527CED36C6CC5CCECE663D70D88DD28ECF48FFA8EB40E51FD8FBCB4FB2. These verify delivered readings and UI, not independent sensor calibration.
