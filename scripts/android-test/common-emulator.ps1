<#
.SYNOPSIS
  Shared functions for Android emulator management (classic AVD, not Gradle Managed Devices).
#>

$ErrorActionPreference = 'Stop'

function Find-AndroidSdk {
    if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $default) { return $default }
    return $null
}

function Find-SdkTool {
    param([string]$SdkPath, [string]$RelativePath)
    $full = Join-Path $SdkPath $RelativePath
    if (Test-Path -LiteralPath $full) { return $full }
    # Try alternate cmdline-tools versions
    $alt = $RelativePath -replace 'cmdline-tools\\latest', 'cmdline-tools\11.0'
    $altFull = Join-Path $SdkPath $alt
    if (Test-Path -LiteralPath $altFull) { return $altFull }
    # Try without cmdline-tools prefix (older SDK layouts)
    $toolsAlt = $RelativePath -replace 'cmdline-tools\\latest\\bin\\', 'tools\bin\'
    $toolsFull = Join-Path $SdkPath $toolsAlt
    if (Test-Path -LiteralPath $toolsFull) { return $toolsFull }
    return $null
}

function Get-SdkManager {
    param([string]$SdkPath)
    return Find-SdkTool $SdkPath 'cmdline-tools\latest\bin\sdkmanager.bat'
}

function Get-AvdManager {
    param([string]$SdkPath)
    return Find-SdkTool $SdkPath 'cmdline-tools\latest\bin\avdmanager.bat'
}

function Get-Emulator {
    param([string]$SdkPath)
    $emu = Join-Path $SdkPath 'emulator\emulator.exe'
    if (Test-Path -LiteralPath $emu) { return $emu }
    return $null
}

function Get-Adb {
    param([string]$SdkPath)
    $adb = Join-Path $SdkPath 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $adb) { return $adb }
    if (Get-Command 'adb' -ErrorAction SilentlyContinue) { return 'adb' }
    return $null
}

function Test-SystemImageInstalled {
    param([string]$SdkPath, [int]$ApiLevel, [string]$ImageType = 'google_apis')
    $imagePath = Join-Path $SdkPath "system-images\android-$ApiLevel\$ImageType\x86_64"
    if (Test-Path -LiteralPath $imagePath) { return $true }
    $imagePath2 = Join-Path $SdkPath "system-images\android-$ApiLevel\$ImageType\x86"
    if (Test-Path -LiteralPath $imagePath2) { return $true }
    # Check for default image
    $defaultPath = Join-Path $SdkPath "system-images\android-$ApiLevel\default\x86_64"
    if (Test-Path -LiteralPath $defaultPath) { return $true }
    $defaultPath2 = Join-Path $SdkPath "system-images\android-$ApiLevel\default\x86"
    if (Test-Path -LiteralPath $defaultPath2) { return $true }
    return $false
}

function Get-InstalledSystemImage {
    param([string]$SdkPath, [int]$ApiLevel)
    foreach ($type in @('google_apis', 'google_apis_playstore', 'default')) {
        foreach ($arch in @('x86_64', 'x86')) {
            $path = Join-Path $SdkPath "system-images\android-$ApiLevel\$type\$arch"
            if (Test-Path -LiteralPath $path) {
                return "system-images;android-$ApiLevel;$type;$arch"
            }
        }
    }
    return $null
}

function Test-AvdExists {
    param([string]$AvdManager, [string]$AvdName)
    $list = & $AvdManager list avd -c 2>&1 | Out-String
    return $list -match "(?m)^$AvdName$"
}

function Start-EmulatorHeadless {
    param([string]$Emulator, [string]$AvdName, [int]$TimeoutSeconds = 120)
    # Start emulator in background
    $proc = Start-Process -FilePath $Emulator -ArgumentList @(
        '-avd', $AvdName,
        '-no-window', '-no-audio', '-no-boot-anim',
        '-gpu', 'swiftshader_indirect',
        '-no-snapshot-save'
    ) -PassThru -NoNewWindow
    return $proc
}

function Wait-EmulatorBoot {
    param([string]$Adb, [string]$Serial, [int]$TimeoutSeconds = 120)
    $elapsed = 0
    while ($elapsed -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 5
        $elapsed += 5
        $bootProp = & $Adb -s $Serial shell 'getprop sys.boot_completed' 2>&1 | Out-String
        if ($bootProp.Trim() -eq '1') { return $true }
    }
    return $false
}

function Get-EmulatorSerial {
    param([string]$Adb, [int]$ProcessId)
    # Find the emulator serial by listing devices and matching
    Start-Sleep -Seconds 3
    $devices = & $Adb devices 2>&1 | Out-String
    $lines = $devices -split "`n" | Where-Object { $_ -match '^emulator-\d+\s+device' }
    if ($lines.Count -gt 0) {
        return ($lines[-1] -split '\s+')[0]
    }
    # Also check for "offline" emulators that are still booting
    $booting = $devices -split "`n" | Where-Object { $_ -match '^emulator-\d+' }
    if ($booting.Count -gt 0) {
        return ($booting[-1] -split '\s+')[0]
    }
    return $null
}

function Stop-EmulatorSafe {
    param([string]$Adb, [string]$Serial, $Process)
    if ($Serial) {
        try { & $Adb -s $Serial emu kill 2>&1 | Out-Null } catch { }
    }
    if ($Process -and -not $Process.HasExited) {
        Start-Sleep -Seconds 3
        if (-not $Process.HasExited) {
            try { $Process.Kill() } catch { }
        }
    }
}
