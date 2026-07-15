$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradlew = Join-Path $root 'gradlew.bat'
$inputDir = Join-Path $root 'pc-gateway\build\jpackage-input'
$outputDir = Join-Path $root 'artifacts\pc-gateway-exe'
$finalExe = Join-Path $root 'artifacts\relay-pc-gateway.exe'
$wixLocal = Join-Path $root 'tools\wix314'
$jdkBin = 'C:\Program Files\Java\jdk-17\bin'

# Prefer project-local WiX binaries and full JDK 17 for jpackage.
if (Test-Path $wixLocal) {
    $env:Path = "$wixLocal;$env:Path"
}
if (Test-Path $jdkBin) {
    $env:Path = "$jdkBin;$env:Path"
}

$jpackage = Get-Command jpackage.exe -ErrorAction SilentlyContinue
if (-not $jpackage) {
    throw 'jpackage.exe is required. Install a full JDK 17+ and add its bin directory to PATH.'
}
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
    throw @"
WiX Toolset 3.x (candle.exe and light.exe) is required for Windows EXE packaging.
Expected local binaries at: $wixLocal
Download: https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip
"@
}

Write-Output "jpackage: $($jpackage.Source)"
Write-Output "candle: $((Get-Command candle.exe).Source)"
Write-Output "light: $((Get-Command light.exe).Source)"

& $gradlew ':pc-gateway:installDist' '--no-daemon' '--console=plain'
if ($LASTEXITCODE -ne 0) { throw "installDist failed with exit $LASTEXITCODE" }

New-Item -ItemType Directory -Force -Path $inputDir, $outputDir, (Split-Path $finalExe) | Out-Null
Get-ChildItem (Join-Path $root 'pc-gateway\build\install\pc-gateway\lib') -File | Copy-Item -Destination $inputDir -Force

# Stop a previous gateway that may lock the destination EXE.
Get-Process -Name 'relay-pc-gateway','RelayPcGateway' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

$appVersion = '0.1.0'
& $jpackage.Source --type exe --name RelayPcGateway --app-version $appVersion `
    --input $inputDir --main-jar pc-gateway.jar `
    --main-class com.example.relay.pcgateway.MainKt --dest $outputDir `
    --win-console --win-menu --vendor Relay `
    --description 'Relay offline PC Gateway' `
    --java-options '-cp $APPDIR\*'

$built = Join-Path $outputDir "RelayPcGateway-$appVersion.exe"
if (-not (Test-Path $built)) { throw "jpackage did not create $built" }

try {
    Copy-Item $built $finalExe -Force
} catch {
    $fallback = Join-Path $root 'artifacts\relay-pc-gateway-updated.exe'
    Copy-Item $built $fallback -Force
    Write-Warning "Could not overwrite $finalExe (file locked). Wrote $fallback instead."
    $finalExe = $fallback
}

$hash = Get-FileHash $finalExe -Algorithm SHA256
$hash.Hash | Set-Content (Join-Path $root 'artifacts\relay-pc-gateway.exe.sha256')
Write-Output "Created $finalExe"
Write-Output "Size bytes: $((Get-Item $finalExe).Length)"
Write-Output "SHA-256: $($hash.Hash)"
Write-Output "This is a Windows installer (jpackage/WiX). Install then run RelayPcGateway from the Start Menu or Program Files."
