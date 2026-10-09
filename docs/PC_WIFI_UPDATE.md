# Computer Wi-Fi firmware updates

The Breeze controller accepts firmware directly from the Windows computer over a temporary Wi-Fi network. The Android app is not involved.

## Normal update

The latest recorded installed version is **1.2.5**. Source and build-script
defaults still identify 1.2.2, so explicitly select patch 5 when reproducing the
current manual-IR build. From PowerShell in the Breeze project folder, run:

```powershell
.\firmware\build-ota.ps1 -BuildPath .\firmware\out-immediate-ir -VersionPatch 5
.\firmware\wireless-update.ps1 -FirmwarePath .\firmware\out-immediate-ir\BreezeController.ino.bin
```

When the script asks, hold the XIAO ESP32C6's built-in **BOOT** button for two seconds while the existing firmware is running, then release it. Do not use the BOOT-plus-RESET download-mode sequence for a normal wireless update.

The controller opens a five-minute temporary WPA2 network named `Breeze-OTA-` followed by a one-time token. The script discovers that network, adds a temporary Windows profile, connects locally, uploads the specified application image, and reconnects the previous Wi-Fi profile in its `finally` cleanup. The computer may briefly lose normal Wi-Fi while joined to the controller.

On success the script prints:

```text
Firmware <version> was accepted. The ESP32 is rebooting and will self-confirm after its startup health check.
```

The new image waits ten seconds after boot, then marks itself healthy and cancels ESP-IDF rollback. Sensors and BLE should resume normally.

## Upload an already-built image

```powershell
.\firmware\wireless-update.ps1 -FirmwarePath .\firmware\out-pc-ota\BreezeController.ino.bin
```

The uploader rejects a file unless it is an ESP32-C6 application image, fits the `0x1B0000` OTA slot, and contains exactly one `BREEZE-FW:<major>.<minor>.<patch>` marker. The controller independently checks the declared length, token, OTA capacity, streamed SHA-256, and ESP image before selecting it for boot.

## Recovery

If discovery or upload fails before the controller accepts the image, reset the board and retry. The old application remains selected unless a complete image passes validation. If Wi-Fi recovery is impossible, USB remains available: put the XIAO into download mode by holding **BOOT**, pressing and releasing **RESET**, then releasing **BOOT**, and flash only the intended application or full known-good build. Do not erase all flash or format LittleFS as routine recovery; those actions would risk stored records and preferences.

## Verified baseline

On October 3, 2026, the physical controller was USB-bootstrapped to v1.2.0, accepted v1.2.1 through this Windows Wi-Fi path, rebooted, resumed sensor readings, and logged `startup health check passed; rollback canceled`. The v1.2.1 application image is 1,585,328 bytes with SHA-256 `617F5E5A2168A2B0005D5FCDD72070C3D70300F77B5397816BE0DD25B15A5361`.

The subsequent v1.2.2 image added retries after transient rollback-confirmation errors. That historical application image was 1,585,328 bytes with SHA-256 `0EF5F08EA1F8DC87415038460E19417C20391C68D5BD8FFA684BCC0DF5074CCA`; do not use this hash for a new build.

Later October 8 testing installed v1.2.5 through this same Windows OTA path. Fresh BLE verification reported version 1.2.5, rollback pending false, and the preserved learned-slot mask `0x03f`. The [IR troubleshooting handoff](../TROUBLESHOOTING.md) separates the successful 220 Ω fan test from the remaining post-update latency and oscillation checks.
