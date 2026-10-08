[CmdletBinding()]
param(
    [string]$FirmwarePath = "",
    [string]$InterfaceName = "Wi-Fi",
    [ValidateRange(15, 300)][int]$DiscoveryTimeoutSeconds = 120,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$otaPrefix = "Breeze-OTA-"
$otaAddress = "192.168.4.1"
$slotBytes = 0x1B0000
$buildPath = Join-Path $PSScriptRoot "out-pc-ota"
$buildScript = Join-Path $PSScriptRoot "build-ota.ps1"

function Read-BreezeImage([string]$ImagePath) {
    $resolvedImage = (Resolve-Path -LiteralPath $ImagePath).Path
    $imageBytes = [System.IO.File]::ReadAllBytes($resolvedImage)
    if ($imageBytes.Length -lt 1024) { throw "Firmware is too small to be an ESP32 application image." }
    if ($imageBytes.Length -gt $slotBytes) { throw "Firmware is larger than the $slotBytes-byte OTA slot." }
    if ($imageBytes[0] -ne 0xE9) { throw "Firmware is missing the ESP32 image magic byte." }
    $chipId = [int]$imageBytes[12] -bor ([int]$imageBytes[13] -shl 8)
    if ($chipId -ne 0x000D) { throw "Firmware was not built for the ESP32-C6." }
    if ($imageBytes[32] -ne 0x32 -or $imageBytes[33] -ne 0x54 -or
        $imageBytes[34] -ne 0xCD -or $imageBytes[35] -ne 0xAB) {
        throw "Firmware is missing the ESP application descriptor."
    }
    $imageText = [Text.Encoding]::ASCII.GetString($imageBytes)
    $versionMatches = [regex]::Matches($imageText, 'BREEZE-FW:([0-9]+\.[0-9]+\.[0-9]+)')
    if ($versionMatches.Count -ne 1) { throw "Firmware must contain exactly one Breeze version marker." }
    [pscustomobject]@{
        Path = $resolvedImage
        Bytes = $imageBytes.Length
        Version = $versionMatches[0].Groups[1].Value
        Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedImage).Hash.ToLowerInvariant()
    }
}

function Find-BreezeOtaNetwork {
    $networkListing = & netsh.exe wlan show networks mode=bssid 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Windows could not scan Wi-Fi networks: $($networkListing -join ' ')" }
    $foundNames = @($networkListing | ForEach-Object {
        if ($_ -match '^\s*SSID\s+\d+\s*:\s*(Breeze-OTA-[0-9A-Fa-f]{16})\s*$') { $matches[1] }
    } | Sort-Object -Unique)
    if ($foundNames.Count -gt 1) { throw "More than one Breeze controller is in update mode. Leave only one enabled and retry." }
    if ($foundNames.Count -eq 1) { return $foundNames[0] }
    return $null
}

function Read-CurrentWifiProfile {
    $interfaceListing = & netsh.exe wlan show interfaces 2>&1
    $profileMatch = [regex]::Match(($interfaceListing -join "`n"), '(?m)^\s*Profile\s*:\s*(.+?)\s*$')
    if ($profileMatch.Success) { return $profileMatch.Groups[1].Value.Trim() }
    return ""
}

if ([string]::IsNullOrWhiteSpace($FirmwarePath)) {
    if ($SkipBuild) { throw "-SkipBuild requires -FirmwarePath." }
    & $buildScript -BuildPath $buildPath
    if ($LASTEXITCODE -ne 0) { throw "Firmware build failed with exit code $LASTEXITCODE." }
    $FirmwarePath = Join-Path $buildPath "BreezeController.ino.bin"
}

$firmwareImage = Read-BreezeImage $FirmwarePath
$curlExecutable = (Get-Command curl.exe -ErrorAction Stop).Source
$previousWifiProfile = Read-CurrentWifiProfile
$temporaryProfileAdded = $false
$otaSsid = $null
$profileXmlPath = Join-Path ([System.IO.Path]::GetTempPath()) ("breeze-ota-" + [guid]::NewGuid().ToString("N") + ".xml")
$responsePath = Join-Path ([System.IO.Path]::GetTempPath()) ("breeze-ota-response-" + [guid]::NewGuid().ToString("N") + ".json")

Write-Host "Breeze firmware $($firmwareImage.Version) is ready ($($firmwareImage.Bytes) bytes)."
Write-Host "While the controller is powered and running normally, hold its built-in BOOT button for 2 seconds, then release it."
Write-Host "Waiting for the controller's temporary Wi-Fi..."

try {
    $discoveryDeadline = [DateTime]::UtcNow.AddSeconds($DiscoveryTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $discoveryDeadline) {
        $otaSsid = Find-BreezeOtaNetwork
        if ($otaSsid) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $otaSsid) { throw "No Breeze OTA network appeared within $DiscoveryTimeoutSeconds seconds." }

    $sessionToken = $otaSsid.Substring($otaPrefix.Length)
    $escapedSsid = [System.Security.SecurityElement]::Escape($otaSsid)
    $escapedToken = [System.Security.SecurityElement]::Escape($sessionToken)
    $profileXml = @"
<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
  <name>$escapedSsid</name>
  <SSIDConfig><SSID><name>$escapedSsid</name></SSID></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>manual</connectionMode>
  <MSM><security>
    <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
    <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>$escapedToken</keyMaterial></sharedKey>
  </security></MSM>
</WLANProfile>
"@
    [System.IO.File]::WriteAllText($profileXmlPath, $profileXml, [Text.UTF8Encoding]::new($false))
    & netsh.exe wlan add profile "filename=$profileXmlPath" "interface=$InterfaceName" user=current | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Windows could not create the temporary Breeze Wi-Fi profile." }
    $temporaryProfileAdded = $true
    & netsh.exe wlan connect "name=$otaSsid" "ssid=$otaSsid" "interface=$InterfaceName" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Windows could not start connecting to the Breeze update network." }

    $localAddress = $null
    $connectionDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $connectionDeadline) {
        $configuration = Get-NetIPConfiguration -InterfaceAlias $InterfaceName -ErrorAction SilentlyContinue
        $localAddress = @($configuration.IPv4Address.IPAddress | Where-Object { $_ -like '192.168.4.*' } | Select-Object -First 1)
        if ($localAddress.Count -eq 1) { $localAddress = $localAddress[0]; break }
        $localAddress = $null
        Start-Sleep -Milliseconds 500
    }
    if (-not $localAddress) { throw "Windows joined the request but did not receive an update-network address." }

    Write-Host "Connected locally. Uploading over Wi-Fi..."
    $curlArguments = @(
        '--fail-with-body', '--noproxy', '*', '--connect-timeout', '10', '--max-time', '150',
        '--progress-bar', '--interface', $localAddress, '--request', 'POST',
        '--header', 'Content-Type: application/octet-stream',
        '--header', "X-Breeze-Token: $sessionToken",
        '--header', "X-Firmware-SHA256: $($firmwareImage.Sha256)",
        '--header', 'Expect:',
        '--data-binary', "@$($firmwareImage.Path)",
        '--output', $responsePath,
        "http://$otaAddress/firmware"
    )
    & $curlExecutable @curlArguments
    $curlExitCode = $LASTEXITCODE
    $serverResponse = if (Test-Path -LiteralPath $responsePath) { [System.IO.File]::ReadAllText($responsePath) } else { "" }
    if ($curlExitCode -ne 0) { throw "Wireless upload failed (curl exit $curlExitCode). Controller response: $serverResponse" }
    if ($serverResponse -notmatch '"ok"\s*:\s*true') { throw "The controller did not accept the firmware. Response: $serverResponse" }

    Write-Host "Firmware $($firmwareImage.Version) was accepted. The ESP32 is rebooting and will self-confirm after its startup health check."
}
finally {
    foreach ($temporaryPath in @($profileXmlPath, $responsePath)) {
        try {
            if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
        }
        catch { Write-Warning "Could not remove temporary file $temporaryPath`: $($_.Exception.Message)" }
    }
    if ($temporaryProfileAdded -and $otaSsid) {
        & netsh.exe wlan delete profile "name=$otaSsid" "interface=$InterfaceName" | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Windows could not remove the temporary Breeze Wi-Fi profile." }
    }
    if ($previousWifiProfile -and $previousWifiProfile -ne $otaSsid) {
        & netsh.exe wlan connect "name=$previousWifiProfile" "interface=$InterfaceName" | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Windows could not reconnect the previous Wi-Fi profile '$previousWifiProfile'." }
    }
}
