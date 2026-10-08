[CmdletBinding()]
param(
    [string]$BuildPath = (Join-Path $PSScriptRoot "out-pc-ota"),
    [ValidateRange(0, 255)][int]$VersionMajor = 1,
    [ValidateRange(0, 255)][int]$VersionMinor = 2,
    [ValidateRange(0, 255)][int]$VersionPatch = 2
)

$ErrorActionPreference = "Stop"
$arduinoCli = "C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe"
$configPath = Join-Path $PSScriptRoot "arduino-dreo.yaml"
$libraryPath = Join-Path $PSScriptRoot "libraries"
$sketchPath = Join-Path $PSScriptRoot "BreezeController"
$sourcePartitions = Join-Path $sketchPath "partitions.csv"
$slotBytes = 0x1B0000
$versionFlags = "-DBREEZE_VERSION_MAJOR=$VersionMajor -DBREEZE_VERSION_MINOR=$VersionMinor -DBREEZE_VERSION_PATCH=$VersionPatch"

if (-not (Test-Path -LiteralPath $arduinoCli)) {
    throw "Arduino CLI was not found at $arduinoCli"
}

& $arduinoCli --config-file $configPath compile `
    --fqbn "esp32:esp32:XIAO_ESP32C6:PartitionScheme=huge_app" `
    --libraries $libraryPath `
    --build-property "compiler.cpp.extra_flags=$versionFlags" `
    --build-path $BuildPath `
    $sketchPath
if ($LASTEXITCODE -ne 0) {
    throw "Breeze firmware compilation failed with exit code $LASTEXITCODE"
}

$firmwareImage = Join-Path $BuildPath "BreezeController.ino.bin"
$builtPartitions = Join-Path $BuildPath "partitions.csv"
if (-not (Test-Path -LiteralPath $firmwareImage)) {
    throw "The compiled firmware image was not produced."
}
if (-not (Test-Path -LiteralPath $builtPartitions)) {
    throw "The compiled partition table was not produced."
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePartitions).Hash -ne
    (Get-FileHash -Algorithm SHA256 -LiteralPath $builtPartitions).Hash) {
    throw "Arduino did not use BreezeController/partitions.csv. Do not flash this build."
}

$image = Get-Item -LiteralPath $firmwareImage
if ($image.Length -gt $slotBytes) {
    throw "Firmware is $($image.Length) bytes, larger than the $slotBytes-byte OTA slot."
}

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $firmwareImage).Hash
$remaining = $slotBytes - $image.Length
Write-Host "OTA firmware ready: $firmwareImage"
Write-Host "Version: $VersionMajor.$VersionMinor.$VersionPatch"
Write-Host "Size: $($image.Length) bytes; slot headroom: $remaining bytes"
Write-Host "SHA-256: $hash"
