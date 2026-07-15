$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradlew = Join-Path $root 'gradlew.bat'
$inputDir = Join-Path $root 'pc-gateway\build\jpackage-input'
$outputDir = Join-Path $root 'artifacts\pc-gateway-exe'
$finalExe = Join-Path $root 'artifacts\relay-pc-gateway.exe'

$jpackage = Get-Command jpackage.exe -ErrorAction SilentlyContinue
if (-not $jpackage) {
    throw 'jpackage.exe is required. Install a full JDK 17+ and add its bin directory to PATH.'
}
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
    throw 'WiX Toolset 3.x (candle.exe and light.exe) is required for Windows EXE packaging.'
}

& $gradlew ':pc-gateway:installDist' '--no-daemon' '--console=plain'
New-Item -ItemType Directory -Force -Path $inputDir, $outputDir, (Split-Path $finalExe) | Out-Null
Get-ChildItem (Join-Path $root 'pc-gateway\build\install\pc-gateway\lib') -File | Copy-Item -Destination $inputDir -Force

& $jpackage.Source --type exe --name RelayPcGateway --app-version 0.1.0 `
    --input $inputDir --main-jar pc-gateway.jar `
    --main-class com.example.relay.pcgateway.MainKt --dest $outputDir `
    --win-console --win-menu --vendor Relay `
    --description 'Relay offline PC Gateway' `
    --java-options '-cp $APPDIR\*'

$built = Join-Path $outputDir 'RelayPcGateway-0.1.0.exe'
if (-not (Test-Path $built)) { throw "jpackage did not create $built" }
Copy-Item $built $finalExe -Force
Get-FileHash $finalExe -Algorithm SHA256 | Format-List | Out-File (Join-Path $root 'artifacts\relay-pc-gateway.exe.sha256')
Write-Output "Created $finalExe"
