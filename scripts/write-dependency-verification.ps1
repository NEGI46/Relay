<#
.SYNOPSIS
  Generate Gradle dependency verification metadata for human review (supply-chain change).

.DESCRIPTION
  This is the controlled entry point for Task "Gradle dependency verification". It runs Gradle's
  --write-verification-metadata across a comprehensive task set that spans every module (Android
  app, shared JVM, pc-gateway, broker, compose desktop) so the resulting gradle/verification-metadata.xml
  covers the full dependency graph, not just one module.

  IMPORTANT - this is intentionally a two-step, human-reviewed process:

    1. This script GENERATES gradle/verification-metadata.xml. It does NOT commit it.
    2. A build/release owner must REVIEW the generated file (component list, sha256 values, that the
       resolution used the approved repositories/network) and only then commit it as a separate,
       auditable supply-chain change.

  Why not commit automatically: an incomplete or unreviewed metadata file pins untrusted state and,
  because Gradle verification is fail-closed, any dependency NOT captured makes later builds fail.
  Generating from a partial task set (for example JVM modules only) omits Android-only dependencies
  and would break the Android build once verification is active. See
  docs/readiness/BLOCKED_BY_EXTERNAL_DECISIONS.md ("Gradle dependency locks / verification metadata").

  Run this in the approved dependency-network environment with the Android SDK available
  (ANDROID_HOME set or local.properties present) so the full graph resolves.

.PARAMETER Tasks
  Task set used to resolve the full dependency graph. Override only if you know why.

.PARAMETER SkipAndroid
  Generate from the JVM/desktop task set only. The result will be INCOMPLETE for the Android build
  and must NOT be committed as the repository-wide verification file. Useful only for a quick local
  smoke of the generation flow.

.EXAMPLE
  .\scripts\write-dependency-verification.ps1
  .\scripts\write-dependency-verification.ps1 -SkipAndroid   # local smoke only, not for commit
#>
[CmdletBinding()]
param(
    [string[]]$Tasks = @(
        'help',
        ':app:assembleDebug',
        ':app:testDebugUnitTest',
        ':shared:jvmTest',
        ':pc-gateway:build',
        ':broker:build',
        ':composeApp:desktopTest'
    ),
    [switch]$SkipAndroid
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root
$gradlew = Join-Path $Root 'gradlew.bat'
$metadata = Join-Path $Root 'gradle\verification-metadata.xml'

if ($SkipAndroid) {
    $Tasks = @('help', ':shared:jvmTest', ':pc-gateway:build', ':broker:build', ':composeApp:desktopTest')
    Write-Warning 'SkipAndroid: generating from the JVM/desktop task set only. The result is INCOMPLETE for the Android build and must NOT be committed as the repository-wide verification file.'
}

Write-Host 'Generating dependency verification metadata (sha256) across:'
$Tasks | ForEach-Object { Write-Host "  - $_" }
Write-Host ''

$gradleArgs = @('--write-verification-metadata', 'sha256') + $Tasks + @('--no-daemon', '--console=plain')
& $gradlew @gradleArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host 'write-dependency-verification: Gradle metadata generation FAILED. Resolve the build first; do not commit a partial file.' -ForegroundColor Red
    exit 1
}

if (-not (Test-Path -LiteralPath $metadata)) {
    Write-Host 'write-dependency-verification: expected gradle/verification-metadata.xml was not produced.' -ForegroundColor Red
    exit 1
}

$lines = (Get-Content -LiteralPath $metadata).Count
$components = (Select-String -LiteralPath $metadata -Pattern '<component ' -SimpleMatch).Count
Write-Host ''
Write-Host "Generated $metadata ($lines lines, $components components)." -ForegroundColor Green
Write-Host ''
Write-Host 'REVIEW BEFORE COMMIT (manual, by a build/release owner):'
Write-Host '  1. Confirm the resolution used only approved repositories/network (no unexpected mirrors).'
Write-Host '  2. Confirm the component set covers EVERY module, including Android-only dependencies.'
Write-Host '  3. Spot-check sha256 values against the upstream artifacts for a few critical components.'
Write-Host '  4. Run a clean full build WITH verification active to prove nothing is missing:'
Write-Host '       .\gradlew.bat --no-daemon :app:assembleDebug :pc-gateway:build :broker:build :shared:jvmTest :composeApp:desktopTest'
Write-Host '  5. Commit gradle/verification-metadata.xml as a separate, auditable supply-chain change.'
Write-Host ''
Write-Host 'This script does NOT commit. An incomplete or unreviewed file will break later builds (fail-closed).'
exit 0
