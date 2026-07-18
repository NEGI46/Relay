[CmdletBinding()]
param()
$rootScript = Join-Path $PSScriptRoot '..\..\scripts\verify-distribution.ps1'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Resolve-Path $rootScript).Path -PackageRoot $PSScriptRoot
exit $LASTEXITCODE
