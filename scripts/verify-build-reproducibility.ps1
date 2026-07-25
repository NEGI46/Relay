<#
.SYNOPSIS
  Verify APK build reproducibility by building twice and comparing SHA-256.
.DESCRIPTION
  Builds the debug APK twice with SOURCE_DATE_EPOCH set, then compares the
  resulting APKs. Reports which ZIP entries differ if not identical.
  Does NOT claim REPRODUCIBLE_BUILD_PASS unless APKs actually match.
#>
[CmdletBinding()]
param(
    [string]$Variant = 'Debug',
    [string]$OutputDir = 'artifacts'
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradlew = Join-Path $Root 'gradlew.bat'

# Set SOURCE_DATE_EPOCH to git commit timestamp for reproducibility
$commitEpoch = git -C $Root log -1 --format=%ct 2>$null
if ($commitEpoch) {
    $env:SOURCE_DATE_EPOCH = $commitEpoch
    Write-Host "SOURCE_DATE_EPOCH set to $commitEpoch"
} else {
    Write-Host "WARNING: Could not determine git commit timestamp" -ForegroundColor Yellow
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

# Build 1
Write-Host "Build 1..."
& $gradlew ":app:assemble$Variant" --no-daemon --console=plain 2>&1 | Out-Null
$apkDir = Join-Path $Root "app\build\outputs\apk\$($Variant.ToLower())"
$apk1 = Get-ChildItem -Path $apkDir -Filter '*.apk' -Recurse | Select-Object -First 1
if (-not $apk1) { Write-Host "FAIL: No APK produced in build 1" -ForegroundColor Red; exit 1 }
$dest1 = Join-Path $OutputDir "build1-$($apk1.Name)"
Copy-Item -LiteralPath $apk1.FullName -Destination $dest1 -Force
$hash1 = (Get-FileHash -LiteralPath $dest1 -Algorithm SHA256).Hash

# Clean build outputs for second build
& $gradlew ":app:clean" --no-daemon --console=plain 2>&1 | Out-Null

# Build 2
Write-Host "Build 2..."
& $gradlew ":app:assemble$Variant" --no-daemon --console=plain 2>&1 | Out-Null
$apk2 = Get-ChildItem -Path $apkDir -Filter '*.apk' -Recurse | Select-Object -First 1
if (-not $apk2) { Write-Host "FAIL: No APK produced in build 2" -ForegroundColor Red; exit 1 }
$dest2 = Join-Path $OutputDir "build2-$($apk2.Name)"
Copy-Item -LiteralPath $apk2.FullName -Destination $dest2 -Force
$hash2 = (Get-FileHash -LiteralPath $dest2 -Algorithm SHA256).Hash

# Generate metadata
$metadata = [ordered]@{
    variant          = $Variant
    sourceDateEpoch  = $env:SOURCE_DATE_EPOCH
    build1Sha256     = $hash1
    build2Sha256     = $hash2
    identical        = ($hash1 -eq $hash2)
    timestamp        = (Get-Date).ToString('o')
    gitCommit        = (git -C $Root rev-parse HEAD 2>$null)
}

Write-Host ""
Write-Host "Build 1 SHA-256: $hash1"
Write-Host "Build 2 SHA-256: $hash2"

if ($hash1 -eq $hash2) {
    Write-Host "REPRODUCIBLE_BUILD_PASS: APKs are identical" -ForegroundColor Green
    $metadata['result'] = 'REPRODUCIBLE_BUILD_PASS'
} else {
    Write-Host "REPRODUCIBLE_BUILD_NOT_ACHIEVED: APKs differ" -ForegroundColor Yellow
    $metadata['result'] = 'REPRODUCIBLE_BUILD_NOT_ACHIEVED'

    # Compare ZIP entries to identify differences
    Write-Host "Comparing ZIP entries..."
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip1 = [System.IO.Compression.ZipFile]::OpenRead($dest1)
    $zip2 = [System.IO.Compression.ZipFile]::OpenRead($dest2)
    $entries1 = @{}
    $zip1.Entries | ForEach-Object { $entries1[$_.FullName] = @{ Size = $_.Length; CRC = $_.Crc32 } }
    $entries2 = @{}
    $zip2.Entries | ForEach-Object { $entries2[$_.FullName] = @{ Size = $_.Length; CRC = $_.Crc32 } }
    $zip1.Dispose()
    $zip2.Dispose()

    $diffs = @()
    foreach ($name in ($entries1.Keys + $entries2.Keys | Sort-Object -Unique)) {
        $e1 = $entries1[$name]
        $e2 = $entries2[$name]
        if (-not $e1) { $diffs += "ADDED in build2: $name" }
        elseif (-not $e2) { $diffs += "REMOVED in build2: $name" }
        elseif ($e1.CRC -ne $e2.CRC -or $e1.Size -ne $e2.Size) { $diffs += "CHANGED: $name (size: $($e1.Size)->$($e2.Size), crc: $($e1.CRC)->$($e2.CRC))" }
    }
    $metadata['differingEntries'] = $diffs
    $diffs | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkYellow }
}

$metadataPath = Join-Path $OutputDir 'build-reproducibility.json'
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding UTF8
Write-Host "Metadata written to $metadataPath"
