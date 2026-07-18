[CmdletBinding()]
param(
    [string]$Publisher = $env:RELAY_MSIX_PUBLISHER,
    [string]$CertificateFile = $env:RELAY_MSIX_CERTIFICATE,
    [string]$CertificatePassword = $env:RELAY_MSIX_CERTIFICATE_PASSWORD,
    [string]$OutputDirectory = "$PSScriptRoot\.."
)

$repoScript = Join-Path $PSScriptRoot '..\..\..\..\scripts\build-windows-bridge-artifacts.ps1'
& powershell -NoProfile -ExecutionPolicy Bypass -File $repoScript -Publisher $Publisher -CertificateFile $CertificateFile -CertificatePassword $CertificatePassword -OutputDirectory $OutputDirectory
