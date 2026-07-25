<#
.SYNOPSIS
  One-command Windows development validation harness for the Relay repository.

.DESCRIPTION
  Runs the full set of checks that can be performed on a personal Windows machine and writes a
  machine-readable JSON report. Every check is classified independently as PASS, FAIL, BLOCKED,
  or NOT_RUN:

    PASS    - the check ran and succeeded
    FAIL    - the check ran and failed
    BLOCKED - the check could not run because an external prerequisite is missing
              (Android SDK, Python, Docker, security scanner, ...)
    NOT_RUN - the check was skipped by selection (-Only) or a feature switch

  Exit code:
    non-zero - any mandatory check FAILed, or -Strict was used and any check is BLOCKED
    0        - otherwise (optional checks that are BLOCKED for missing external tools do not
               fail a report-only run)

  Android SDK resolution order: ANDROID_HOME, ANDROID_SDK_ROOT, sdk.dir in local.properties,
  then the default Android Studio location (%LOCALAPPDATA%\Android\Sdk). When found, it is
  exported to child Gradle processes so Android checks can run without a committed local.properties.

.PARAMETER Strict
  Treat BLOCKED checks as failures for the exit code (use before a release).

.PARAMETER Only
  Run only the named checks as a comma-separated list. All others are recorded as NOT_RUN.

.PARAMETER OutputPath
  Where to write the JSON report. Default: artifacts\windows-validation-report.json

.PARAMETER SkipBuild
  Skip the long Gradle build checks (shared/app/gateway/broker builds). Tests still run.

.EXAMPLE
  .\scripts\validate-windows-development.ps1
  .\scripts\validate-windows-development.ps1 -Strict
  .\scripts\validate-windows-development.ps1 -Only pc-gateway-test,broker-test
#>
[CmdletBinding()]
param(
    [switch]$Strict,
    [string]$Only = '',
    [string]$OutputPath = 'artifacts\windows-validation-report.json',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root
$gradlew = Join-Path $Root 'gradlew.bat'
$selected = @($Only -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })

# --- Prerequisite discovery -------------------------------------------------

function Find-AndroidSdk {
    if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    $lp = Join-Path $Root 'local.properties'
    if (Test-Path -LiteralPath $lp) {
        $line = Get-Content -LiteralPath $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $p = ($line -replace '^\s*sdk\.dir\s*=', '').Trim()
            $p = $p -replace '\\\\', '\'
            if (Test-Path -LiteralPath $p) { return $p }
        }
    }
    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $default) { return $default }
    return $null
}

$androidSdk = Find-AndroidSdk
if ($androidSdk) {
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_SDK_ROOT = $androidSdk
}
function Has-Command([string]$name) { return [bool](Get-Command $name -ErrorAction SilentlyContinue) }
$hasPython = Has-Command 'python'
# Docker Desktop may be installed but not running; treat a non-responsive daemon as unavailable
# without aborting the harness (stderr from native docker must not become a terminating error).
$hasDocker = $false
if (Has-Command 'docker') {
    try {
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $dockerInfo = (& docker info 2>&1 | Out-String)
        $ErrorActionPreference = $prevEap
        $hasDocker = ($LASTEXITCODE -eq 0) -and ($dockerInfo -notmatch 'error|cannot|failed to connect')
    } catch {
        $hasDocker = $false
    }
}
$hasJazzerDeps = $hasPython
$securityToolDir = $env:RELAY_SECURITY_TOOL_DIR
$hasSecurityScanners = $false
if ($securityToolDir) {
    $hasSecurityScanners = (Test-Path -LiteralPath (Join-Path $securityToolDir 'syft.exe')) -or `
        (Test-Path -LiteralPath (Join-Path $securityToolDir 'osv-scanner.exe')) -or `
        (Test-Path -LiteralPath (Join-Path $securityToolDir 'grype.exe'))
}
if (-not $hasSecurityScanners) {
    $hasSecurityScanners = (Has-Command 'syft') -or (Has-Command 'osv-scanner') -or (Has-Command 'grype')
}

Write-Host "Prerequisites: androidSdk=$($androidSdk -ne $null) python=$hasPython docker=$hasDocker securityScanners=$hasSecurityScanners strict=$Strict"

# --- Step engine -------------------------------------------------------------

$script:results = New-Object System.Collections.ArrayList
$script:logRoot = Join-Path $Root 'artifacts\windows-validation-logs'
New-Item -ItemType Directory -Force -Path $script:logRoot | Out-Null

function Add-Result {
    param([string]$Name, [string]$Status, [string]$Category, [bool]$Mandatory, $ExitCode, [string]$Detail, [long]$Millis,
          [int]$ExecutedTests = -1, [int]$Failures = -1, [int]$Errors = -1, [int]$Skipped = -1, [string[]]$ArtifactPaths = @())
    $entry = [ordered]@{
        name           = $Name
        status         = $Status
        category       = $Category
        mandatory      = $Mandatory
        exitCode       = $ExitCode
        detail         = $Detail
        durationMillis = $Millis
    }
    if ($ExecutedTests -ge 0) { $entry['executedTests'] = $ExecutedTests }
    if ($Failures -ge 0) { $entry['failures'] = $Failures }
    if ($Errors -ge 0) { $entry['errors'] = $Errors }
    if ($Skipped -ge 0) { $entry['skipped'] = $Skipped }
    if ($ArtifactPaths.Count -gt 0) { $entry['artifactPaths'] = $ArtifactPaths }
    [void]$script:results.Add($entry)
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } 'BLOCKED' { 'Yellow' } default { 'Gray' } }
    Write-Host ("[{0}] {1} {2}" -f $Status, $Name, $(if ($Detail) { "- $Detail" } else { '' })) -ForegroundColor $color
}

function Invoke-Step {
    param(
        [string]$Name,
        [string]$Category,
        [bool]$Mandatory,
        [scriptblock]$Check,
        [string]$BlockedReason = ''
    )
    if ($selected.Count -gt 0 -and $Name -notin $selected) {
        Add-Result -Name $Name -Status 'NOT_RUN' -Category $Category -Mandatory $Mandatory -ExitCode $null -Detail 'not selected' -Millis 0
        return
    }
    if ($BlockedReason) {
        Add-Result -Name $Name -Status 'BLOCKED' -Category $Category -Mandatory $Mandatory -ExitCode $null -Detail $BlockedReason -Millis 0
        return
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = & $Check
        $sw.Stop()
        if ($null -eq $r) { $r = @{ status = 'PASS' } }
        elseif ($r -is [int]) { $r = @{ status = $(if ($r -eq 0) { 'PASS' } else { 'FAIL' }); exitCode = $r } }
        # Forward all check result fields to Add-Result (test metrics, artifact paths)
        $addParams = @{
            Name = $Name; Status = [string]$r.status; Category = $Category; Mandatory = $Mandatory
            ExitCode = $r.exitCode; Detail = [string]$r.detail; Millis = $sw.ElapsedMilliseconds
        }
        if ($r.ContainsKey('executedTests')) { $addParams['ExecutedTests'] = [int]$r.executedTests }
        if ($r.ContainsKey('failures'))      { $addParams['Failures'] = [int]$r.failures }
        if ($r.ContainsKey('errors'))        { $addParams['Errors'] = [int]$r.errors }
        if ($r.ContainsKey('skipped'))       { $addParams['Skipped'] = [int]$r.skipped }
        if ($r.ContainsKey('artifactPaths')) { $addParams['ArtifactPaths'] = [string[]]$r.artifactPaths }
        Add-Result @addParams
    } catch {
        $sw.Stop()
        Add-Result -Name $Name -Status 'FAIL' -Category $Category -Mandatory $Mandatory `
            -ExitCode $null -Detail ("exception: " + $_.Exception.Message) -Millis $sw.ElapsedMilliseconds
    }
}

# Run an external command, capture output to a per-step log, and classify by exit code.
# Start-Process with stdin redirected from NUL is used deliberately: native tools (python, docker,
# gradle) write progress to stderr, and Python 3.13 will otherwise enter its interactive REPL on a
# redirected-but-open stdin and crash on the missing console handle. Redirecting all three streams
# and waiting for exit keeps the harness deterministic and never interactive.
function Invoke-Logged {
    param([string]$Name, [string]$Exe, [string[]]$CommandArgs)
    $safe = $Name -replace '[^A-Za-z0-9_-]', '_'
    $outLog = Join-Path $script:logRoot "$safe.out.log"
    $errLog = Join-Path $script:logRoot "$safe.err.log"
    $nul = Join-Path $script:logRoot "$safe.nul"
    Set-Content -LiteralPath $nul -Value '' -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath $Exe -ArgumentList $CommandArgs -WorkingDirectory $Root `
        -RedirectStandardInput $nul -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -NoNewWindow -PassThru -Wait
    $code = $p.ExitCode
    $combined = @()
    if (Test-Path -LiteralPath $outLog) { $combined += Get-Content -LiteralPath $outLog }
    if (Test-Path -LiteralPath $errLog) { $combined += Get-Content -LiteralPath $errLog }
    $combined | Set-Content -LiteralPath (Join-Path $script:logRoot "$safe.log") -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $nul -ErrorAction SilentlyContinue
    if ($null -eq $code) { $code = 0 }
    return @{ status = $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }); exitCode = $code; detail = "exitCode=$code log=$safe.log" }
}

# Parse JUnit XML files from a directory, returning test metrics.
# Only considers XML files modified AFTER $StartTime to avoid stale results.
function Parse-JUnitXml {
    param([string]$XmlDir, [datetime]$StartTime)
    $result = @{ executedTests = 0; failures = 0; errors = 0; skipped = 0; suites = @() }
    if (-not (Test-Path -LiteralPath $XmlDir)) { return $result }
    $xmlFiles = Get-ChildItem -Path $XmlDir -Filter '*.xml' -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $StartTime }
    foreach ($f in $xmlFiles) {
        try {
            [xml]$xml = Get-Content -LiteralPath $f.FullName -Encoding UTF8
            $suites = @()
            if ($xml.testsuites) { $suites = @($xml.testsuites.testsuite) }
            elseif ($xml.testsuite) { $suites = @($xml.testsuite) }
            foreach ($s in $suites) {
                if (-not $s) { continue }
                $t = [int]($s.tests -as [int])
                $fl = [int]($s.failures -as [int])
                $e = [int]($s.errors -as [int])
                $sk = [int]($s.skipped -as [int])
                $result.executedTests += $t
                $result.failures += $fl
                $result.errors += $e
                $result.skipped += $sk
                $result.suites += @{ name = $s.name; tests = $t; failures = $fl; errors = $e; skipped = $sk }
            }
        } catch {
            Write-Host "  WARNING: Failed to parse $($f.Name): $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    return $result
}

# Run Gradle with JUnit XML result parsing. Validates exit code and test count.
function Invoke-Gradle {
    param([string]$Name, [string[]]$Tasks, [string]$XmlResultDir = '')
    $stepStart = Get-Date
    $r = Invoke-Logged -Name $Name -Exe $gradlew -CommandArgs ($Tasks + @('--no-daemon', '--console=plain'))
    # If Gradle failed, never report PASS regardless of old XML
    if ($r.exitCode -ne 0) { return $r }
    # Parse JUnit XML if result dir specified
    if ($XmlResultDir -and (Test-Path -LiteralPath $XmlResultDir)) {
        $parsed = Parse-JUnitXml -XmlDir $XmlResultDir -StartTime $stepStart
        $r['executedTests'] = $parsed.executedTests
        $r['failures'] = $parsed.failures
        $r['errors'] = $parsed.errors
        $r['skipped'] = $parsed.skipped
        # 0 executed tests with Gradle success = FAIL (false-green prevention)
        if ($parsed.executedTests -eq 0) {
            $r.status = 'FAIL'
            $r.detail = 'Gradle succeeded but 0 tests executed (no JUnit XML evidence)'
        } elseif ($parsed.failures -gt 0 -or $parsed.errors -gt 0) {
            $r.status = 'FAIL'
            $r.detail = "Tests failed: $($parsed.failures) failures, $($parsed.errors) errors"
        }
    }
    return $r
}
function Invoke-PsTest { param([string]$Name, [string]$RelPath) return (Invoke-Logged -Name $Name -Exe 'powershell' -CommandArgs @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $Root $RelPath))) }
function Invoke-Python { param([string]$Name, [string[]]$PythonArgs) return (Invoke-Logged -Name $Name -Exe 'python' -CommandArgs $PythonArgs) }

# Tri-state PowerShell test: the callee uses exit code 2 to mean BLOCKED (missing external tooling),
# which must never be reported as PASS. 0 = PASS, 2 = BLOCKED, anything else = FAIL.
function Invoke-PsTestTri { param([string]$Name, [string]$RelPath)
    $r = Invoke-Logged -Name $Name -Exe 'powershell' -CommandArgs @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $Root $RelPath))
    if ($r.exitCode -eq 2) { $r.status = 'BLOCKED'; $r.detail = 'blocked (exitCode=2) ' + $r.detail }
    return $r
}

# --- Check catalog -----------------------------------------------------------

$androidBlocked = if ($androidSdk) { '' } else { 'Android SDK not found (set ANDROID_HOME or install Android Studio)' }
$hasNode = Has-Command 'node'
$hasNpm = Has-Command 'npm'
$playwrightBlocked = if ($hasNode -and $hasNpm -and (Test-Path -LiteralPath (Join-Path $Root 'staff-console-e2e\package-lock.json'))) { '' } else { 'Node.js, npm, or staff-console-e2e/package-lock.json not found' }

Invoke-Step -Name 'shared-jvm-test' -Category 'gradle' -Mandatory $true -Check { Invoke-Gradle 'shared-jvm-test' @(':shared:jvmTest') -XmlResultDir (Join-Path $Root 'shared\build\test-results') }
Invoke-Step -Name 'relay-protocol-test' -Category 'gradle' -Mandatory $true -Check { Invoke-Gradle 'relay-protocol-test' @(':relay-protocol:test') -XmlResultDir (Join-Path $Root 'relay-protocol\build\test-results') }
Invoke-Step -Name 'android-unit-test' -Category 'gradle' -Mandatory $false -BlockedReason $androidBlocked -Check { Invoke-Gradle 'android-unit-test' @(':app:testDebugUnitTest') -XmlResultDir (Join-Path $Root 'app\build\test-results') }
Invoke-Step -Name 'android-lint' -Category 'gradle' -Mandatory $false -BlockedReason $androidBlocked -Check { Invoke-Gradle 'android-lint' @(':app:lintDebug') }
Invoke-Step -Name 'android-instrumentation-compile' -Category 'gradle' -Mandatory $false -BlockedReason $androidBlocked -Check { Invoke-Gradle 'android-instrumentation-compile' @(':app:compileDebugAndroidTestKotlin') }
Invoke-Step -Name 'android-api36-instrumentation' -Category 'gradle' -Mandatory $false -BlockedReason $androidBlocked -Check {
    # Clean result directory to avoid stale XML
    $xmlDir = Join-Path $Root 'app\build\outputs\androidTest-results\managedDevice\mediumPhoneApi36'
    if (Test-Path -LiteralPath $xmlDir) { Remove-Item -Path $xmlDir -Recurse -Force -ErrorAction SilentlyContinue }
    $r = Invoke-Gradle 'android-api36-instrumentation' @(':app:mediumPhoneApi36DebugAndroidTest') -XmlResultDir (Join-Path $Root 'app\build\outputs\androidTest-results')
    return $r
}
Invoke-Step -Name 'android-api23-avd-smoke' -Category 'android-avd' -Mandatory $false -BlockedReason $androidBlocked -Check {
    # API 23 cannot use Gradle Managed Devices (GMD supports API 27+ only).
    # Delegate to the classic AVD smoke script instead.
    $scriptPath = Join-Path $Root 'scripts\android-test\run-api23-smoke.ps1'
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        return @{ status = 'BLOCKED'; detail = 'scripts\android-test\run-api23-smoke.ps1 not found' }
    }
    $r = Invoke-Logged -Name 'android-api23-avd-smoke' -Exe 'powershell' -CommandArgs @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath)
    if ($r.exitCode -eq 2) { $r.status = 'BLOCKED'; $r.detail = 'blocked (API 23 image or license missing) ' + $r.detail }
    return $r
}
Invoke-Step -Name 'pc-gateway-test' -Category 'gradle' -Mandatory $true -Check { Invoke-Gradle 'pc-gateway-test' @(':pc-gateway:test') -XmlResultDir (Join-Path $Root 'pc-gateway\build\test-results') }
Invoke-Step -Name 'broker-test' -Category 'gradle' -Mandatory $true -Check { Invoke-Gradle 'broker-test' @(':broker:test') -XmlResultDir (Join-Path $Root 'broker\build\test-results') }
Invoke-Step -Name 'compose-desktop-test' -Category 'gradle' -Mandatory $true -Check { Invoke-Gradle 'compose-desktop-test' @(':composeApp:desktopTest') -XmlResultDir (Join-Path $Root 'composeApp\build\test-results') }
Invoke-Step -Name 'staff-console-playwright' -Category 'e2e' -Mandatory $false -BlockedReason $playwrightBlocked -Check {
    $e2eDir = Join-Path $Root 'staff-console-e2e'
    $safe = 'staff-console-playwright'
    $outLog = Join-Path $script:logRoot "$safe.out.log"
    $errLog = Join-Path $script:logRoot "$safe.err.log"
    $nul = Join-Path $script:logRoot "$safe.nul"
    Set-Content -LiteralPath $nul -Value '' -ErrorAction SilentlyContinue
    # Install dependencies
    $p1 = Start-Process -FilePath 'npm' -ArgumentList @('ci') -WorkingDirectory $e2eDir `
        -RedirectStandardInput $nul -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -NoNewWindow -PassThru -Wait
    if ($p1.ExitCode -ne 0) {
        Remove-Item -LiteralPath $nul -ErrorAction SilentlyContinue
        return @{ status = 'FAIL'; exitCode = $p1.ExitCode; detail = "npm ci failed exitCode=$($p1.ExitCode)" }
    }
    # Install Chromium (cached if already present)
    $p2 = Start-Process -FilePath 'npx' -ArgumentList @('playwright', 'install', 'chromium') -WorkingDirectory $e2eDir `
        -RedirectStandardInput $nul -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -NoNewWindow -PassThru -Wait
    if ($p2.ExitCode -ne 0) {
        Remove-Item -LiteralPath $nul -ErrorAction SilentlyContinue
        return @{ status = 'BLOCKED'; exitCode = $p2.ExitCode; detail = "playwright install chromium failed exitCode=$($p2.ExitCode)" }
    }
    # Run tests
    $testLog = Join-Path $script:logRoot "$safe.test.log"
    $testErr = Join-Path $script:logRoot "$safe.test.err.log"
    $p3 = Start-Process -FilePath 'npx' -ArgumentList @('playwright', 'test') -WorkingDirectory $e2eDir `
        -RedirectStandardInput $nul -RedirectStandardOutput $testLog -RedirectStandardError $testErr `
        -NoNewWindow -PassThru -Wait
    Remove-Item -LiteralPath $nul -ErrorAction SilentlyContinue
    $combined = @()
    if (Test-Path -LiteralPath $testLog) { $combined += Get-Content -LiteralPath $testLog }
    if (Test-Path -LiteralPath $testErr) { $combined += Get-Content -LiteralPath $testErr }
    $combined | Set-Content -LiteralPath (Join-Path $script:logRoot "$safe.log") -ErrorAction SilentlyContinue
    # Copy artifacts for inspection
    $reportDir = Join-Path $e2eDir 'playwright-report'
    if (Test-Path -LiteralPath $reportDir) {
        $artifactDest = Join-Path $script:logRoot 'playwright-report'
        Copy-Item -Path $reportDir -Destination $artifactDest -Recurse -Force -ErrorAction SilentlyContinue
    }
    $code = $p3.ExitCode
    if ($null -eq $code) { $code = 0 }
    return @{ status = $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }); exitCode = $code; detail = "exitCode=$code log=$safe.log" }
}
Invoke-Step -Name 'accessibility-check' -Category 'script' -Mandatory $true -Check { Invoke-PsTest 'accessibility-check' 'scripts\check-accessibility.ps1' }
Invoke-Step -Name 'implementation-contract-check' -Category 'script' -Mandatory $true -Check { Invoke-PsTest 'implementation-contract-check' 'scripts\verify-implementation-contracts.ps1' }
Invoke-Step -Name 'host-checks' -Category 'script' -Mandatory $true -Check { Invoke-PsTest 'host-checks' 'test-lab\run-host-checks.ps1' }
Invoke-Step -Name 'decoder-regression' -Category 'python' -Mandatory $false -BlockedReason $(if ($hasPython) { '' } else { 'python not found' }) -Check { Invoke-Python 'decoder-regression' @('-m', 'unittest', 'discover', '-s', 'test-lab/fuzz', '-p', 'decoder*_test.py') }
Invoke-Step -Name 'virtual-ble-test' -Category 'python' -Mandatory $false -BlockedReason $(if ($hasPython) { '' } else { 'python not found' }) -Check { Invoke-Python 'virtual-ble-test' @('tools/ble-sim/run_tests.py') }
Invoke-Step -Name 'jvm-jazzer-regression' -Category 'gradle' -Mandatory $true -Check { Invoke-Gradle 'jvm-jazzer-regression' @(':fuzz-jvm:test') -XmlResultDir (Join-Path $Root 'fuzz-jvm\build\test-results') }
Invoke-Step -Name 'windows-launcher-test' -Category 'script' -Mandatory $true -Check { Invoke-PsTest 'windows-launcher-test' 'scripts\tests\run-pc-gateway-launcher.tests.ps1' }
Invoke-Step -Name 'windows-autostart-test' -Category 'script' -Mandatory $true -Check { Invoke-PsTest 'windows-autostart-test' 'scripts\tests\register-poc-gateway-autostart.tests.ps1' }
Invoke-Step -Name 'windows-setup-test' -Category 'script' -Mandatory $true -Check { Invoke-PsTest 'windows-setup-test' 'scripts\tests\pc-gateway-setup.tests.ps1' }
Invoke-Step -Name 'cp932-ascii-regression' -Category 'script' -Mandatory $true -Check { Invoke-PsTest 'cp932-ascii-regression' 'scripts\tests\launcher-encoding.tests.ps1' }
Invoke-Step -Name 'gateway-backup-restore' -Category 'script' -Mandatory $false -BlockedReason $(if ($hasPython) { '' } else { 'python not found (needed to seed/verify the SQLite database)' }) -Check { Invoke-PsTestTri 'gateway-backup-restore' 'scripts\tests\gateway-backup-restore.tests.ps1' }

if ($SkipBuild) {
    foreach ($n in @('pc-gateway-build', 'android-debug-build', 'android-localdev-build')) {
        Add-Result -Name $n -Status 'NOT_RUN' -Category 'gradle' -Mandatory $false -ExitCode $null -Detail 'skipped by -SkipBuild' -Millis 0
    }
} else {
    Invoke-Step -Name 'pc-gateway-build' -Category 'gradle' -Mandatory $true -Check { Invoke-Gradle 'pc-gateway-build' @(':pc-gateway:build', ':pc-gateway:installDist') }
    Invoke-Step -Name 'android-debug-build' -Category 'gradle' -Mandatory $false -BlockedReason $androidBlocked -Check { Invoke-Gradle 'android-debug-build' @(':app:assembleDebug') }
    Invoke-Step -Name 'android-localdev-build' -Category 'gradle' -Mandatory $false -BlockedReason $androidBlocked -Check { Invoke-Gradle 'android-localdev-build' @(':app:assembleLocalDev') }
}

Invoke-Step -Name 'test-trust-artifact-check' -Category 'gradle' -Mandatory $false -BlockedReason $androidBlocked -Check { Invoke-Gradle 'test-trust-artifact-check' @(':app:verifyNoTestTrustArtifactsInReleaseApks') }
Invoke-Step -Name 'security-scan' -Category 'security' -Mandatory $false -BlockedReason $(if ($hasSecurityScanners) { '' } else { 'security scanners not installed (run scripts\install-security-tools.ps1)' }) -Check {
    Invoke-Logged -Name 'security-scan' -Exe 'powershell' -CommandArgs @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $Root 'scripts\run-osv.ps1'), '-SourcePath', '.', '-OutputPath', 'artifacts/osv.json', '-Mode', 'report-only')
}
Invoke-Step -Name 'pc-gateway-smoke' -Category 'smoke' -Mandatory $false -Check {
    $r = Invoke-Logged -Name 'pc-gateway-smoke' -Exe 'powershell' -CommandArgs @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $Root 'scripts\smoke-test-pc-gateway.ps1'), '-HealthTimeoutSeconds', '180')
    return $r
}

# --- Report and exit code ----------------------------------------------------

$failCount = @($script:results | Where-Object { $_.status -eq 'FAIL' }).Count
$blockedCount = @($script:results | Where-Object { $_.status -eq 'BLOCKED' }).Count
$passCount = @($script:results | Where-Object { $_.status -eq 'PASS' }).Count
$mandatoryFail = @($script:results | Where-Object { $_.status -eq 'FAIL' -and $_.mandatory }).Count

$summary = [ordered]@{
    generatedUtc  = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    mode          = $(if ($Strict) { 'strict' } else { 'report-only' })
    strict        = [bool]$Strict
    androidSdk    = $androidSdk
    python        = $hasPython
    docker        = $hasDocker
    pass          = $passCount
    fail          = $failCount
    blocked       = $blockedCount
    mandatoryFail = $mandatoryFail
    results       = @($script:results)
}

$outFull = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $outFull) | Out-Null
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outFull -Encoding UTF8
Write-Host ''
Write-Host "Report written to $outFull"
Write-Host ("Summary: PASS={0} FAIL={1} BLOCKED={2} mandatoryFail={3}" -f $passCount, $failCount, $blockedCount, $mandatoryFail)

if ($mandatoryFail -gt 0) {
    Write-Host 'RESULT: FAIL (mandatory check failed)' -ForegroundColor Red
    exit 1
}
if ($Strict -and $blockedCount -gt 0) {
    Write-Host 'RESULT: FAIL (strict mode and at least one check is BLOCKED)' -ForegroundColor Red
    exit 1
}
if ($failCount -gt 0) {
    Write-Host 'RESULT: FAIL (a non-mandatory check failed)' -ForegroundColor Red
    exit 1
}
Write-Host 'RESULT: PASS' -ForegroundColor Green
exit 0
