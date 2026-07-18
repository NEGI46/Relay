[CmdletBinding()]
param([string]$PackageRoot)

$ErrorActionPreference = 'Stop'
$PackageRoot = if ($PackageRoot) { $PackageRoot } else { Join-Path (Join-Path $PSScriptRoot '..') 'distribution\Relay-User-Package' }
$PackageRoot = (Resolve-Path $PackageRoot).Path
$required = @(
    'Android\Relay-debug.apk',
    'Android\Relay-release-signed.apk',
    'Android\Relay-release-unsigned.apk',
    'Windows\Relay.PcBleBridge-local-signed.msix',
    'Windows\Debug\Relay.PcBleBridge-debug-local-signed.msix',
    'Windows\RelayPcGateway\bin\pc-gateway.bat',
    'iPhone\RelayAppleKit\Package.swift'
)
foreach ($relative in $required) {
    $path = Join-Path $PackageRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing distribution artifact: $relative" }
}
$sumFile = Join-Path $PackageRoot 'SHA256SUMS.txt'
foreach ($line in Get-Content $sumFile) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '\s+', 2
    $path = Join-Path $PackageRoot ($parts[1] -replace '/', '\')
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $parts[0].ToLowerInvariant()) { throw "Checksum mismatch: $($parts[1])" }
}
Write-Output "Distribution verification passed: $PackageRoot"
