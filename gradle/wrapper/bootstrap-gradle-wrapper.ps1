# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$GradleArguments
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$propertiesPath = Join-Path $scriptDir 'gradle-wrapper.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
  throw "Missing $propertiesPath"
}

$properties = @{}
foreach ($line in Get-Content -LiteralPath $propertiesPath) {
  if ($line -match '^(?<key>[^#=]+)=(?<value>.*)$') {
    $properties[$Matches.key.Trim()] = $Matches.value.Trim()
  }
}
$wrapperUrl = $properties['wrapperJarUrl']
$wrapperSha256 = $properties['wrapperJarSha256']
if ($wrapperUrl -notmatch '^https://raw\.githubusercontent\.com/gradle/gradle/[0-9a-f]{40}/gradle/wrapper/gradle-wrapper\.jar$') {
  throw 'wrapperJarUrl must be an immutable Gradle raw URL'
}
if ($wrapperSha256 -notmatch '^[0-9a-f]{64}$') {
  throw 'wrapperJarSha256 must be a lowercase SHA-256 digest'
}

$gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$jarDir = Join-Path $gradleUserHome 'wrapper\jars'
New-Item -ItemType Directory -Force -Path $jarDir | Out-Null
$jarPath = Join-Path $jarDir "gradle-wrapper-$wrapperSha256.jar"
$jarPropertiesPath = Join-Path $jarDir "gradle-wrapper-$wrapperSha256.properties"
if (-not (Test-Path -LiteralPath $jarPropertiesPath) -or ((Get-FileHash -Algorithm SHA256 -LiteralPath $propertiesPath).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPropertiesPath).Hash)) {
  Copy-Item -Force -LiteralPath $propertiesPath -Destination $jarPropertiesPath
}

$needsDownload = $true
if (Test-Path -LiteralPath $jarPath) {
  $needsDownload = ((Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant() -ne $wrapperSha256)
}

if ($needsDownload) {
  $downloadPath = "$jarPath.download.$PID"
  try {
    Invoke-WebRequest -Uri $wrapperUrl -OutFile $downloadPath
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $downloadPath).Hash.ToLowerInvariant() -ne $wrapperSha256) {
      throw 'Downloaded Gradle wrapper failed SHA-256 verification'
    }
    Move-Item -Force -LiteralPath $downloadPath -Destination $jarPath
  } finally {
    if (Test-Path -LiteralPath $downloadPath) { Remove-Item -Force -LiteralPath $downloadPath }
  }
}

$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
& $javaExe '-Dorg.gradle.appname=gradlew' '-jar' $jarPath @GradleArguments
exit $LASTEXITCODE
