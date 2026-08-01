[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    throw $Message
}

function Read-Property([string]$Path, [string]$Name) {
    $pattern = '^' + [regex]::Escape($Name) + '=(.*)$'
    $line = Get-Content -LiteralPath $Path |
        Where-Object { $_ -match $pattern } |
        Select-Object -First 1
    if ($null -eq $line) {
        Fail "missing $Name in $Path"
    }
    return ($line -replace $pattern, '$1')
}

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$propertiesFile = Join-Path $scriptDirectory 'gradle-wrapper.properties'
if (-not (Test-Path -LiteralPath $propertiesFile)) {
    Fail "missing $propertiesFile"
}

$wrapperJarUrl = Read-Property $propertiesFile 'wrapperJarUrl'
$wrapperJarSha256 = Read-Property $propertiesFile 'wrapperJarSha256'
if ($wrapperJarUrl -notmatch '^https://raw\.githubusercontent\.com/gradle/gradle/[0-9a-f]{40}/gradle/wrapper/gradle-wrapper\.jar$') {
    Fail 'wrapperJarUrl must point to an immutable Gradle commit'
}
if ($wrapperJarSha256 -notmatch '^[0-9a-f]{64}$') {
    Fail 'wrapperJarSha256 must be a lowercase SHA-256 digest'
}

$gradleUserHome = $env:GRADLE_USER_HOME
if ([string]::IsNullOrWhiteSpace($gradleUserHome)) {
    $gradleUserHome = Join-Path $env:USERPROFILE '.gradle'
}
$jarDirectory = Join-Path $gradleUserHome 'wrapper\jars'
$jarPath = Join-Path $jarDirectory ('gradle-wrapper-' + $wrapperJarSha256 + '.jar')
New-Item -ItemType Directory -Force -Path $jarDirectory | Out-Null

if (Test-Path -LiteralPath $jarPath) {
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $wrapperJarSha256) {
        Remove-Item -Force -LiteralPath $jarPath
    }
}

if (-not (Test-Path -LiteralPath $jarPath)) {
    $temporaryPath = $jarPath + '.' + [guid]::NewGuid().ToString() + '.tmp'
    try {
        Invoke-WebRequest -Uri $wrapperJarUrl -OutFile $temporaryPath
        $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryPath).Hash.ToLowerInvariant()
        if ($actualSha256 -ne $wrapperJarSha256) {
            Fail 'downloaded Gradle Wrapper JAR failed SHA-256 verification'
        }
        Move-Item -Force -LiteralPath $temporaryPath -Destination $jarPath
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -Force -LiteralPath $temporaryPath
        }
    }
}

# Gradle 9.5 resolves its distribution properties beside the wrapper JAR.
$propertiesCachePath = Join-Path $jarDirectory ('gradle-wrapper-' + $wrapperJarSha256 + '.properties')
Copy-Item -Force -LiteralPath $propertiesFile -Destination $propertiesCachePath

$javaExe = $null
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExe)) {
        Fail 'JAVA_HOME does not point to a usable Java installation'
    }
} else {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand) {
        Fail 'JAVA_HOME is not set and no java.exe command could be found'
    }
    $javaExe = $javaCommand.Source
}

$javaOptions = @('-Xmx64m', '-Xms64m')
foreach ($optionName in @('JAVA_OPTS', 'GRADLE_OPTS')) {
    $optionValue = [Environment]::GetEnvironmentVariable($optionName)
    if (-not [string]::IsNullOrWhiteSpace($optionValue)) {
        $javaOptions += ($optionValue -split '\s+')
    }
}

& $javaExe @javaOptions '-Dorg.gradle.appname=gradlew' '-jar' $jarPath @GradleArguments
exit $LASTEXITCODE
