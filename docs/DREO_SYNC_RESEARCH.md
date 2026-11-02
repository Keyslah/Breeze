# Dreo state synchronization research

Compatibility researched October 10, 2026; live connection and locked-phone
checks verified October 11, 2026. Source evidence and runtime evidence are
separated below.

## Exact model evidence

The community integration explicitly supports **DR-HAF008S, series 565S**, with
speeds **1–9**, modes, and oscillation. Its
[support change #679](https://github.com/JeffSteinbok/hass-dreo/pull/679) includes
captured discovery and state fixtures from a real device. At inspected commit
`63e584f8d126a5159c801fb84e2dbe576ffebd4d`:

- [Discovery fixture](https://github.com/JeffSteinbok/hass-dreo/blob/63e584f8d126a5159c801fb84e2dbe576ffebd4d/tests/pydreo/api_responses/get_devices_HAF008S.json)
  identifies `model: DR-HAF008S` and `seriesName: 565S`.
- [State fixture](https://github.com/JeffSteinbok/hass-dreo/blob/63e584f8d126a5159c801fb84e2dbe576ffebd4d/tests/pydreo/api_responses/get_device_state_HAF008S_1.json)
  contains `data.mixed` entries for `windlevel`, `poweron`, `mode`, `oscmode`,
  and `connected`, each with a `state` value and Unix-seconds `timestamp`.
- [Model definition](https://github.com/JeffSteinbok/hass-dreo/blob/63e584f8d126a5159c801fb84e2dbe576ffebd4d/custom_components/dreo/pydreo/models.py#L379)
  declares speed range 1–9 and Normal, Natural, Sleep, Auto, and Turbo modes.

[Issue #931](https://github.com/JeffSteinbok/hass-dreo/issues/931) supplies another
565S owner's October 2026 discovery/state capture. This establishes a usable
cloud interface for DR-HAF008S, rather than assuming support from the `DR-HAF`
prefix. The requested name `DR-HAF008` lacks the cloud model's final `S`;
account discovery must identify the actual device and expose the required
fields before Breeze treats it as compatible.

## Official and community interfaces

| Interface | Authentication and reads | Update behavior |
| --- | --- | --- |
| Official `hass-dreoverse` 2.2.1, commit `86327d5547b7dd759db51df842acb14d1b154717`, with `pydreo-cloud` 1.0.0 | Email and MD5 password, `grant_type=openapi`, bearer token; `open-api-{us,eu}.dreo-tech.com`, `/api/device/list`, `/api/device/state` | Polls every 15 seconds. Cloud supplies device configuration; no exact 565S entry was present in the inspected repository. |
| Community integration, commit above | Email and MD5 password, `grant_type=email-password`, bearer token; `app-api-{region}.dreo-tech.com`, `/api/v2/user-device/device/list`, `/api/user-device/device/state?deviceSn=...` | Initial REST snapshot plus WebSocket reports. REST reads can also retrieve snapshots without Home Assistant. |

Sources: official [manifest](https://github.com/dreo-team/hass-dreoverse/blob/86327d5547b7dd759db51df842acb14d1b154717/custom_components/dreo/manifest.json),
[authentication](https://github.com/dreo-team/hass-dreoverse/blob/86327d5547b7dd759db51df842acb14d1b154717/custom_components/dreo/config_flow.py),
[coordinator](https://github.com/dreo-team/hass-dreoverse/blob/86327d5547b7dd759db51df842acb14d1b154717/custom_components/dreo/coordinator.py),
[SDK release](https://pypi.org/project/pydreo-cloud/1.0.0/);
community [endpoints](https://github.com/JeffSteinbok/hass-dreo/blob/63e584f8d126a5159c801fb84e2dbe576ffebd4d/custom_components/dreo/pydreo/constant.py#L114),
[authentication body](https://github.com/JeffSteinbok/hass-dreo/blob/63e584f8d126a5159c801fb84e2dbe576ffebd4d/custom_components/dreo/pydreo/helpers.py#L46),
and [state handling](https://github.com/JeffSteinbok/hass-dreo/blob/63e584f8d126a5159c801fb84e2dbe576ffebd4d/custom_components/dreo/pydreo/__init__.py#L308).

Both authentication protocols include public upstream application identifiers.
These are distinct from the owner's credentials and issued tokens. No browser
OAuth/PKCE flow was found in the inspected implementations. Account secrets
must be stored using Android Keystore-backed encryption, excluded from backup
and logs, with bounded reauthentication and network retries.

## Freshness and scope

These are Dreo cloud-reported values, not a direct physical speed measurement.
`windlevel` is a speed level, not RPM. A REST success, accepted command, or
WebSocket `control-reply` does not establish a new fan state. Missing values
must stay unknown. Read only the selected device's actual state fields.

Compare per-field report timestamps, preserve newer accepted observations,
and distinguish report time from local fetch time. The community SDK documents
[last-change timestamps on another fan family](https://github.com/JeffSteinbok/hass-dreo/blob/63e584f8d126a5159c801fb84e2dbe576ffebd4d/custom_components/dreo/pydreo/pydreoceilingfan.py#L575):
an unchanged value can retain an old timestamp. That behavior must not be
mistaken for a guaranteed heartbeat on the 565S. Reject responses whose
requests began before a newer local IR action, and reject older or conflicting
versions of previously accepted cloud fields. Retain those field versions by
selected device across service restarts, without restoring settled confidence.
Do not permanently reject an unchanged cloud value merely because its
last-change time predates an IR
attempt: a missed IR command may leave the real value unchanged.

Breeze's requested polling cadence is once every **three minutes**, including
after IR commands; no extra post-command requests are permitted. Two matching
scheduled snapshots can provide conservative settling before correction, but
are not proof of a fresh physical reading. Cloud values can still lag. Keep
the status as **Dreo reported**, distinguish field report times from fetch
time, and show an estimate/pending state when reports cannot be used. The
existing controller synchronization command also marks Normal mode, so
automatic correction requires Dreo to report Normal mode; other modes can be
displayed without asserting a false controller mode.

Absolute speed is supported by [upstream fan control code](https://github.com/dreo-team/hass-dreoverse/blob/86327d5547b7dd759db51df842acb14d1b154717/custom_components/dreo/fan.py#L287),
removing the need to accumulate relative speed steps. That capability still needs execution
confirmation. **Breeze's authorized integration is state reading only; no
direct Dreo cloud control is authorized.** IR remains the control path. No
Home Assistant installation, backend, or firmware rewrite is required.

## Locked-phone operation

Android's [`connectedDevice` foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
supports ongoing interaction with external devices over Bluetooth, IR, or
networks. It requires its manifest permission, an eligible runtime permission
or other listed prerequisite, and a visible notification. A user-started fan
session can continue after the screen locks. The
[`dataSync` six-hour background limit](https://developer.android.com/develop/background-work/services/fgs/timeout)
would be unsuitable for an overnight session.

A foreground service alone does not remove
[Doze's network and CPU restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby).
Battery-optimization exemption permits network access and partial wake locks;
it does not itself keep a timer running. Timely locked synchronization needs
appropriate wake handling while the fan session is active, balanced against
battery cost. It must work independently of Breeze's microphone/listening
setting. A build or foreground test does not prove this requirement: verify
on the physical phone while locked, including forced Doze, and verify that
stopping the session releases its resources.

## Breeze implementation and validation

The Android implementation uses the community REST interface above without a
Home Assistant dependency. It reads once at session start, then waits 180
seconds between completed checks. Network failures back off to at most 15
minutes; authentication gets at most one refresh attempt per failed request.
IR activity invalidates pending results without scheduling another request.
Only explicit, timestamped Normal-mode values can correct the existing
controller estimate. Corrections preserve paused Logic and send no IR.

Implementation files:

- `app/src/main/java/com/aaron/breeze/DreoClient.java`: verified login, discovery and read-only state requests.
- `app/src/main/java/com/aaron/breeze/DreoAccountStore.java`: Android Keystore encryption and selected account/device.
- `app/src/main/java/com/aaron/breeze/DreoReport.java`: strict field and timestamp parsing.
- `app/src/main/java/com/aaron/breeze/DreoSyncPolicy.java`: persisted version guards, action generations and conservative settling.
- `app/src/main/java/com/aaron/breeze/DreoSynchronizer.java`: three-minute scheduling, failure handling and report provenance.
- `app/src/main/java/com/aaron/breeze/DreoConnectionUi.java`: account selection and locked-phone battery setup.
- `app/src/main/java/com/aaron/breeze/BreezeService.java`: controller correction and active foreground session.
- `app/src/main/java/com/aaron/breeze/UiConnectionState.java`: scoped locked-phone exception to the existing background grace.
- `app/src/main/java/com/aaron/breeze/MainActivity.java`: status and connection entry point in the existing UI.
- `app/src/main/AndroidManifest.xml`: battery-exemption request permission.
- `app/build.gradle.kts`: JVM JSON test dependency only.
- `app/src/test/java/com/aaron/breeze/{DreoReportTest,DreoDeviceDiscoveryTest,DreoSyncPolicyTest,UiConnectionStateTest}.java`: parser, identity, stale-state, restart and lifecycle checks.
- `app/src/androidTest/java/com/aaron/breeze/{DreoCorrectionChecks,StoreTestRunner}.java`: isolated real correction/transport/ACK/persistence checks.
- `docs/SETUP.md` and this document: setup, sources and limitations.

On October 10, 2026, `assembleDebug`, `testDebugUnitTest`, `lintDebug` and
`assembleDebugAndroidTest` completed successfully. All 127 JVM tests passed;
lint reported zero errors and 38 warnings, including the battery-exemption
warning. The new parser/policy/lifecycle coverage contributes 28 tests.
Output: `app/out-dreo-cloud-20261010/outputs/apk/debug/app-debug.apk`.
SHA-256: `57328A5087D15BEE3F3ABAD97DBFCEDA8262F03B2ECD4AEC843960ACDF6333F6`.
Installed on the Pixel 8 Pro with `adb install -r`, and the installed APK hash
matches. This establishes build/install evidence; real account and locked
runtime evidence is recorded separately below when available. No firmware
files were changed by this synchronization task.

### Live connection and locked-phone checks — October 11, 2026

The owner signed in inside Breeze. The Pixel 8 Pro successfully fetched actual
Dreo state: connected true, power off, saved speed 1, Normal mode (1), and
oscillation off (0). The encrypted account was usable while locked, and Android
battery exemption was granted. The controller already estimated Off, so no
physical mismatch was deliberately introduced.

Three successful reads were observed, with no failed requests or extra
post-command polls. Device epoch timestamps preserve exact timing:

| Read | Request epoch ms | Response epoch ms | Runtime state |
| --- | --- | --- | --- |
| 1 | 1791694823505 | 1791694823626 | Initial account connection |
| 2 | 1791695003629 | 1791695003995 | Phone securely locked |
| 3 | 1791695183998 | 1791695184388 | Phone securely locked; forced deep Doze `IDLE` |

Requests were 180.124 and 180.369 seconds apart. During the third read,
keyguard reported `showing=true` and `inputRestricted=true`; the original
two-minute grace had expired, Bluetooth remained connected, sensor telemetry
was fresh, and the Dreo wake lock remained held. Controller firmware stayed
1.2.21, its estimate stayed Off, and its transmission counter stayed 85. This
exercise did not issue fan controls. Temporary Doze/battery simulation was
cleared afterward, leaving the real phone's synchronization running.

The actual delta-correction branch was tested separately on a temporary,
read-only API-35 emulator. `DreoCorrectionChecks` fed the observed sanitized
state into the real `BreezeService.applyDreoReport` method with a fake transport
and isolated preferences. A deliberately wrong estimate of 5 produced exactly
one `SYNC_SPEED 0 / Normal` packet, waited for write completion plus ACK, and
persisted Off. Matching state produced no packet; non-Normal, busy and pending
guards passed; paused Logic stayed paused. No Dreo account, network, Bluetooth,
IR or real app settings were accessed by the fixture. The emulator was closed.

Limits: the real fan was already Off, so a naturally occurring physical drift
correction and an overnight endurance run were not observed. The endpoint is
still cloud-cached, and Normal-mode-only correction remains intentional.

### Phone correction history — October 11, 2026

`DreoCorrections.java` stores a phone-local lifetime total and the latest 1,000
timestamped estimate corrections in the existing private phone preferences.
The Fan page shows the total and latest correction; **Correction history**
shows each date/time (including seconds) and before → after level, even when
the controller and service are disconnected. Each event also keeps the IR
send counter observed before correction and Dreo read/speed/power/mode
timestamps for later diagnosis. No credentials are included.

The count/history and corrected anchor are saved together only after the
controller acknowledges a changed, known estimate. Matching checks, unknown
estimate initialization, failures and superseded callbacks are excluded.
This is evidence of estimate correction, not proof of a missed IR command.
Past corrections were not recorded and cannot be backfilled. No firmware or
ESP storage changes were made.

The debug APK and instrumentation APK built successfully; 127 JVM tests passed
and lint reported zero errors / 39 warnings. The expanded isolated
`DreoCorrectionChecks` passed on the temporary API-35 emulator: two timestamped
corrections, before-ACK telemetry changes, duplicate acknowledgements, offline
history, rejection paths and 1,000-entry retention with a continuing total.
No physical phone or fan was controlled; the emulator was closed afterward.

APK: `app/out-dreo-corrections-20261011/outputs/apk/debug/app-debug.apk`.
SHA-256: `8ED352FC34FBCC803D371CD0883AE15013443E93232504B61B9C6E133CE85ABC`.
The update is built but has not been installed on the owner's Pixel 8 Pro,
which is away from the development computer.
