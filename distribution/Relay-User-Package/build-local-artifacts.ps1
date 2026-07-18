[CmdletBinding()]
param()
$script = Join-Path $PSScriptRoot '..\..\scripts\build-local-artifacts.ps1'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Resolve-Path $script).Path
