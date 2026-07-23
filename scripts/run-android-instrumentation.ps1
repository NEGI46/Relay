<#
.SYNOPSIS
  Runs the Relay Android instrumentation suite on a reproducible headless emulator.

.DESCRIPTION
  Drives the Gradle Managed Device defined in app/build.gradle.kts
  (mediumPhoneApi36, see docs/EMULATOR_VALIDATION_DEFAULTS.md). Gradle provisions
  the emulator, installs the debug app and test APKs, runs the connected tests,
  and tears the emulator down afterwards, so a workstation and CI behave the same.

  The emulator runs headless (-no-window) with the swiftshader_indirect GPU so the
  workstation stays responsive. Nothing secret is created; the managed device state
  lives under the SDK's managed-device cache and is reused across runs.

  Exits 0 when the instrumentation suite passes, 1 on any failure (including a
  missing Android SDK or an emulator that never finishes booting).

.PARAMETER Device
  Managed device task prefix. Default "mediumPhoneApi36".

.PARAMETER Gpu
  Emulator GPU mode passed to the managed device. Default "swiftshader_indirect".

.PARAMETER Tests
  Optional fully-qualified test class or method filter (androidx test '-Pandroid.testInstrumentationRunnerArguments.class').

.PARAMETER SkipDaemon
  Pass --no-daemon to Gradle (matches CI).
#>
[CmdletBinding()]
param(
    [string]$Device = 'mediumPhoneApi36',
    [string]$Gpu = 'swiftshader_indirect',
    [string]$Tests = '',
    [switch]$SkipDaemon
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    $localProps = Join-Path $Root 'local.properties'
    if (Test-Path -LiteralPath $localProps) {
        $line = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
        if ($line) {
            $value = ($line.Line -split '=', 2)[1].Trim() -replace '\\\\', '\'
            if ($value -and (Test-Path -LiteralPath $value)) { return $value }
        }
    }
    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $default) { return $default }
    return $null
}

$sdk = Resolve-AndroidSdk
if (-not $sdk) {
    Write-Host 'FAIL: Android SDK not found. Set ANDROID_HOME or add sdk.dir to local.properties.'
    exit 1
}
Write-Host "Android SDK: $sdk"
Write-Host "Managed device: $Device (GPU $Gpu, headless)"

$task = ":app:${Device}DebugAndroidTest"
$gradleArgs = @(
    $task,
    '--console=plain',
    "-Pandroid.testoptions.manageddevices.emulator.gpu=$Gpu"
)
if ($SkipDaemon) { $gradleArgs += '--no-daemon' }
if ($Tests) {
    $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.class=$Tests"
}

Write-Host "Running: gradlew $($gradleArgs -join ' ')"
& (Join-Path $Root 'gradlew.bat') @gradleArgs
$code = $LASTEXITCODE

$report = Join-Path $Root "app\build\reports\androidTests\managedDevice\debug\$Device"
if (Test-Path -LiteralPath $report) {
    Write-Host "Instrumentation report: $report"
}

if ($code -eq 0) {
    Write-Host 'PASS: instrumentation suite completed successfully.'
    exit 0
}
Write-Host "FAIL: instrumentation suite exited $code."
exit 1
