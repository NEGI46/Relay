<#
.SYNOPSIS
  Verify APK build reproducibility by building twice and comparing SHA-256.
.DESCRIPTION
  Builds the debug APK twice with SOURCE_DATE_EPOCH set, then compares the
  resulting APKs. Reports which ZIP entries differ if not identical.

  For release/pilotRelease variants, SOURCE_DATE_EPOCH or a git timestamp is
  REQUIRED - the script will not fall back to Instant.now().

  Does NOT claim REPRODUCIBLE_BUILD_PASS unless APKs actually match.

  Exit codes:
    0 = REPRODUCIBLE_BUILD_PASS or REPRODUCIBLE_CONTENT_PASS_METADATA_DIFF
    1 = REPRODUCIBLE_BUILD_NOT_ACHIEVED or BUILD_FAILED
    2 = BLOCKED_MISSING_METADATA
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
    # For release variants, missing timestamp is a blocking condition
    if ($Variant -match 'release|pilotRelease|Release') {
        Write-Host "BLOCKED_MISSING_METADATA: No git timestamp available for release variant" -ForegroundColor Yellow
        $metadata = [ordered]@{ variant = $Variant; result = 'BLOCKED_MISSING_METADATA'; detail = 'No git commit timestamp for reproducibility' }
        New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
        $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDir 'build-reproducibility.json') -Encoding UTF8
        exit 2
    }
    Write-Host "WARNING: Could not determine git commit timestamp (acceptable for debug)" -ForegroundColor Yellow
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$assembleTask = ":app:assemble$Variant"

# Determine expected APK output path
$variantLower = $Variant.ToLower()
$apkDir = Join-Path $Root "app\build\outputs\apk\$variantLower"

# --- Build 1 ---
Write-Host "Build 1..."
# Clean ONLY the variant's output to avoid picking up old APKs
if (Test-Path -LiteralPath $apkDir) { Remove-Item -Path $apkDir -Recurse -Force -ErrorAction SilentlyContinue }

& $gradlew $assembleTask --no-daemon --console=plain 2>&1 | Out-Null
$build1Exit = $LASTEXITCODE
if ($build1Exit -ne 0) {
    Write-Host "BUILD_FAILED: Gradle build 1 failed (exit code $build1Exit)" -ForegroundColor Red
    $metadata = [ordered]@{ variant = $Variant; result = 'BUILD_FAILED'; detail = "Build 1 failed with exit code $build1Exit" }
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDir 'build-reproducibility.json') -Encoding UTF8
    exit 1
}

$apk1 = Get-ChildItem -Path $apkDir -Filter '*.apk' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk1) {
    Write-Host "BUILD_FAILED: No APK produced in build 1" -ForegroundColor Red
    exit 1
}

# Verify APK was actually produced NOW (not stale)
$buildStart = (Get-Date).AddMinutes(-10)
if ($apk1.LastWriteTime -lt $buildStart) {
    Write-Host "BUILD_FAILED: APK exists but appears stale (modified before build started)" -ForegroundColor Red
    exit 1
}

$dest1 = Join-Path $OutputDir "build1-$($apk1.Name)"
Copy-Item -LiteralPath $apk1.FullName -Destination $dest1 -Force
$hash1 = (Get-FileHash -LiteralPath $dest1 -Algorithm SHA256).Hash
$size1 = (Get-Item -LiteralPath $dest1).Length

Write-Host "  APK: $($apk1.Name) Size: $size1 SHA256: $hash1"

# --- Clean for second build ---
if (Test-Path -LiteralPath $apkDir) { Remove-Item -Path $apkDir -Recurse -Force -ErrorAction SilentlyContinue }
& $gradlew ":app:clean" --no-daemon --console=plain 2>&1 | Out-Null

# --- Build 2 ---
Write-Host "Build 2..."
& $gradlew $assembleTask --no-daemon --console=plain 2>&1 | Out-Null
$build2Exit = $LASTEXITCODE
if ($build2Exit -ne 0) {
    Write-Host "BUILD_FAILED: Gradle build 2 failed (exit code $build2Exit)" -ForegroundColor Red
    $metadata = [ordered]@{ variant = $Variant; result = 'BUILD_FAILED'; detail = "Build 2 failed with exit code $build2Exit"; build1Sha256 = $hash1 }
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDir 'build-reproducibility.json') -Encoding UTF8
    exit 1
}

$apk2 = Get-ChildItem -Path $apkDir -Filter '*.apk' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk2) {
    Write-Host "BUILD_FAILED: No APK produced in build 2" -ForegroundColor Red
    exit 1
}
$dest2 = Join-Path $OutputDir "build2-$($apk2.Name)"
Copy-Item -LiteralPath $apk2.FullName -Destination $dest2 -Force
$hash2 = (Get-FileHash -LiteralPath $dest2 -Algorithm SHA256).Hash
$size2 = (Get-Item -LiteralPath $dest2).Length

Write-Host "  APK: $($apk2.Name) Size: $size2 SHA256: $hash2"

# --- Generate metadata ---
$metadata = [ordered]@{
    variant          = $Variant
    sourceDateEpoch  = $env:SOURCE_DATE_EPOCH
    build1Sha256     = $hash1
    build1Size       = $size1
    build2Sha256     = $hash2
    build2Size       = $size2
    identical        = ($hash1 -eq $hash2)
    timestamp        = (Get-Date).ToString('o')
    gitCommit        = (git -C $Root rev-parse HEAD 2>$null)
}

Write-Host ""
Write-Host "Build 1 SHA-256: $hash1 ($size1 bytes)"
Write-Host "Build 2 SHA-256: $hash2 ($size2 bytes)"

if ($hash1 -eq $hash2) {
    Write-Host "REPRODUCIBLE_BUILD_PASS: APKs are byte-identical" -ForegroundColor Green
    $metadata['result'] = 'REPRODUCIBLE_BUILD_PASS'
} else {
    Write-Host "APKs differ - comparing ZIP entries..." -ForegroundColor Yellow

    # Compare ZIP entries using stream-based SHA-256 (avoids .Crc32 availability issues)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip1 = [System.IO.Compression.ZipFile]::OpenRead($dest1)
    $zip2 = [System.IO.Compression.ZipFile]::OpenRead($dest2)

    $entries1 = @{}
    foreach ($entry in $zip1.Entries) {
        $stream = $entry.Open()
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $hashBytes = $sha.ComputeHash($stream)
        $stream.Close()
        $entries1[$entry.FullName] = @{ Size = $entry.Length; Hash = [BitConverter]::ToString($hashBytes) -replace '-' }
    }

    $entries2 = @{}
    foreach ($entry in $zip2.Entries) {
        $stream = $entry.Open()
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $hashBytes = $sha.ComputeHash($stream)
        $stream.Close()
        $entries2[$entry.FullName] = @{ Size = $entry.Length; Hash = [BitConverter]::ToString($hashBytes) -replace '-' }
    }
    $zip1.Dispose()
    $zip2.Dispose()

    $contentDiffs = @()
    $metadataDiffs = @()
    foreach ($name in ($entries1.Keys + $entries2.Keys | Sort-Object -Unique)) {
        $e1 = $entries1[$name]
        $e2 = $entries2[$name]
        if (-not $e1) { $contentDiffs += "ADDED in build2: $name" }
        elseif (-not $e2) { $contentDiffs += "REMOVED in build2: $name" }
        elseif ($e1.Hash -ne $e2.Hash) {
            # Distinguish metadata-only changes (e.g., timestamps in META-INF)
            if ($name -match 'META-INF|MANIFEST\.MF|\.SF|\.RSA|\.DSA') {
                $metadataDiffs += "METADATA_DIFF: $name"
            } else {
                $contentDiffs += "CONTENT_CHANGED: $name (size: $($e1.Size)->$($e2.Size))"
            }
        }
    }

    $metadata['contentDiffs'] = $contentDiffs
    $metadata['metadataDiffs'] = $metadataDiffs

    if ($contentDiffs.Count -eq 0 -and $metadataDiffs.Count -gt 0) {
        Write-Host "REPRODUCIBLE_CONTENT_PASS_METADATA_DIFF: Content identical, only signing/metadata differs" -ForegroundColor Cyan
        $metadata['result'] = 'REPRODUCIBLE_CONTENT_PASS_METADATA_DIFF'
        $metadataDiffs | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkCyan }
    } else {
        Write-Host "REPRODUCIBLE_BUILD_NOT_ACHIEVED: Content differs" -ForegroundColor Yellow
        $metadata['result'] = 'REPRODUCIBLE_BUILD_NOT_ACHIEVED'
        $contentDiffs | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkYellow }
        $metadataDiffs | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkCyan }
    }
}

$metadataPath = Join-Path $OutputDir 'build-reproducibility.json'
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding UTF8
Write-Host "Metadata written to $metadataPath"

# Exit code
switch ($metadata['result']) {
    'REPRODUCIBLE_BUILD_PASS' { exit 0 }
    'REPRODUCIBLE_CONTENT_PASS_METADATA_DIFF' { exit 0 }
    'BLOCKED_MISSING_METADATA' { exit 2 }
    default { exit 1 }
}
