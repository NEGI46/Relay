<#
.SYNOPSIS
  Install pinned dependency-security and backup tools into an isolated Windows directory.

.DESCRIPTION
  Windows counterpart of scripts/install-security-tools.sh. Downloads exact release binaries from
  the official GitHub releases, verifies each archive against the SHA-256 checksum file published
  in the same release, extracts only the needed executable into an isolated directory, and writes
  tool metadata JSON. The system PATH is never modified; callers use the directory directly or via
  the RELAY_SECURITY_TOOL_DIR environment variable (which the Relay scan scripts already honour).

  Re-running is idempotent: a tool already present at the pinned version is skipped. A failed or
  partial install of a tool is removed so a stale half-installed binary is never left behind.

.PARAMETER ToolDir
  Isolated installation directory. Default: <repo>\tools\security-windows

.PARAMETER Tools
  Comma-separated subset to install. Default: syft,osv-scanner,grype
  Note: 'age' is intentionally NOT in the default set. The age release publishes only Sigstore
  '.proof' attestations and no plain SHA-256 checksums file, so this script cannot verify it with
  a published checksum and refuses to install it unverified (fail-closed). Request it explicitly
  with -Tools age only after supplying a trusted sha256 in the catalog below.
  Note: litestream is deliberately absent from this Windows catalog because the official litestream
  release ships no Windows binary (darwin/linux only); the gateway backup script that needs it is
  therefore exercised on Linux CI, not via this Windows installer.

.PARAMETER MetadataPath
  Where to write the tool metadata JSON. Default: <ToolDir>\tools-metadata.json

.EXAMPLE
  .\scripts\install-security-tools.ps1
  .\scripts\install-security-tools.ps1 -Tools syft,grype
  $env:RELAY_SECURITY_TOOL_DIR = (Resolve-Path .\tools\security-windows).Path
#>
[CmdletBinding()]
param(
    [string]$ToolDir = '',
    [string]$Tools = 'syft,osv-scanner,grype',
    [string]$MetadataPath = ''
)

$ErrorActionPreference = 'Stop'
# Native download progress and extractor stderr must not become terminating errors.
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ToolDir) { $ToolDir = Join-Path $Root 'tools\security-windows' }
if (-not $MetadataPath) { $MetadataPath = Join-Path $ToolDir 'tools-metadata.json' }
New-Item -ItemType Directory -Force -Path $ToolDir | Out-Null

# Version pins intentionally match scripts/install-security-tools.sh so Windows and CI scanners agree.
$catalog = @{
    'syft' = @{
        version    = '1.20.0'
        repo       = 'anchore/syft'
        asset      = 'syft_1.20.0_windows_amd64.zip'
        checksums  = 'syft_1.20.0_checksums.txt'
        binary     = 'syft.exe'
        versionArg = 'version'
    }
    'osv-scanner' = @{
        version    = '2.0.3'
        repo       = 'google/osv-scanner'
        asset      = 'osv-scanner_windows_amd64.exe'
        checksums  = 'osv-scanner_SHA256SUMS'
        binary     = 'osv-scanner.exe'
        versionArg = '--version'
    }
    'grype' = @{
        version    = '0.116.0'
        repo       = 'anchore/grype'
        asset      = 'grype_0.116.0_windows_amd64.zip'
        checksums  = 'grype_0.116.0_checksums.txt'
        binary     = 'grype.exe'
        versionArg = 'version'
    }
    'age' = @{
        version    = '1.2.1'
        repo       = 'FiloSottile/age'
        asset      = 'age-v1.2.1-windows-amd64.zip'
        checksums  = ''
        binary     = 'age.exe'
        versionArg = '--version'
        # age publishes only Sigstore '.proof' attestations, not a plain SHA-256 checksums file.
        # Leave this empty/'REPLACE_...' so the install is refused (fail-closed) until a trusted
        # out-of-band checksum is recorded here. Do NOT fabricate a value.
        sha256     = 'REPLACE_WITH_PUBLISHED_AGE_SHA256'
    }
    # litestream intentionally omitted: the official release publishes no Windows binary
    # (darwin/linux only), so it cannot be installed here. See the .PARAMETER Tools note above.
}

$selected = @($Tools -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$work = Join-Path ([IO.Path]::GetTempPath()) ('relay-sec-tools-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Save-FileFromUrl([string]$Url, [string]$OutFile) {
    Invoke-WebRequest -Uri $Url -UseBasicParsing -OutFile $OutFile
}

# Download a text file and return its content as a string. Reading via a temp file avoids the
# PowerShell 5.1 behaviour where Invoke-WebRequest .Content can be a byte[] for some responses.
function Get-TextFromUrl([string]$Url) {
    $tmp = Join-Path $script:work ('dl-' + [guid]::NewGuid().ToString('N') + '.txt')
    Save-FileFromUrl $Url $tmp
    $text = (Get-Content -LiteralPath $tmp -Raw)
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
    return $text
}

# Return the lowercase SHA-256 for $assetName from a checksums file, or $null if absent.
function Get-ChecksumFromText([string]$Text, [string]$AssetName) {
    foreach ($line in ($Text -split "`r?`n")) {
        $parts = $line -split '\s+'
        if ($parts.Count -ge 2 -and $parts[1] -eq $AssetName) {
            return $parts[0].ToLowerInvariant()
        }
    }
    return $null
}

function Get-FileSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$metadata = New-Object System.Collections.ArrayList
$failures = 0

try {
    foreach ($name in $selected) {
        $spec = $catalog[$name]
        if (-not $spec) {
            Write-Warning "Unknown tool requested: $name (skipping)"
            [void]$metadata.Add([ordered]@{ tool = $name; status = 'SKIPPED'; reason = 'unknown-tool' })
            continue
        }
        $target = Join-Path $ToolDir $spec.binary
        $entry = [ordered]@{
            tool      = $name
            version   = $spec.version
            repo      = $spec.repo
            binary    = $spec.binary
            path      = $target
            sha256    = $null
            status    = 'FAIL'
            reason    = $null
        }

        try {
            # Idempotent: skip when the pinned version is already installed and reports correctly.
            if (Test-Path -LiteralPath $target -PathType Leaf) {
                $existing = (& $target $spec.versionArg 2>&1 | Out-String)
                if ($LASTEXITCODE -eq 0 -and $existing -match [regex]::Escape($spec.version)) {
                    $entry.status = 'PASS'
                    $entry.reason = 'already-installed'
                    $entry.sha256 = (Get-FileSha256 $target)
                    Write-Host "[PASS] $name $($spec.version) already installed"
                    [void]$metadata.Add($entry)
                    continue
                }
                # A different/broken version is present: replace it.
                Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
            }

            $baseUrl = "https://github.com/$($spec.repo)/releases/download/v$($spec.version)"
            $assetPath = Join-Path $work $spec.asset
            Save-FileFromUrl "$baseUrl/$($spec.asset)" $assetPath
            $actual = Get-FileSha256 $assetPath

            # Verify the archive against the release checksums. Refuse to install on any mismatch
            # or when no trustworthy checksum is available.
            $expected = $null
            if ($spec.checksums) {
                $sumText = Get-TextFromUrl "$baseUrl/$($spec.checksums)"
                $expected = Get-ChecksumFromText $sumText $spec.asset
            } elseif ($spec.sha256 -and $spec.sha256 -notmatch '^REPLACE_') {
                $expected = $spec.sha256.ToLowerInvariant()
            }
            if (-not $expected) {
                throw "no published checksum available for $($spec.asset); refusing unverified install"
            }
            if ($actual -ne $expected) {
                throw "checksum mismatch for $($spec.asset): expected $expected got $actual"
            }
            $entry.sha256 = $actual

            # Extract the single needed binary.
            $extractDir = Join-Path $work ("extract-" + $name)
            New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
            if ($spec.asset -match '\.zip$') {
                Expand-Archive -LiteralPath $assetPath -DestinationPath $extractDir -Force
                $found = Get-ChildItem -LiteralPath $extractDir -Recurse -Filter $spec.binary | Select-Object -First 1
                if (-not $found) { throw "$($spec.binary) not found inside $($spec.asset)" }
                Copy-Item -LiteralPath $found.FullName -Destination $target -Force
            } else {
                # Single-binary release (e.g. osv-scanner .exe).
                Copy-Item -LiteralPath $assetPath -Destination $target -Force
            }

            # Post-install version confirmation attests the pinned release actually runs.
            $verify = (& $target $spec.versionArg 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0 -or $verify -notmatch [regex]::Escape($spec.version)) {
                throw "post-install version check failed for $name (expected $($spec.version))"
            }
            $entry.status = 'PASS'
            $entry.reason = 'installed-and-verified'
            Write-Host "[PASS] $name $($spec.version) installed and verified -> $target"
        } catch {
            $failures++
            $entry.status = 'FAIL'
            $entry.reason = $_.Exception.Message
            # Partial install cleanup: never leave a half-installed binary behind.
            if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue }
            Write-Host "[FAIL] $name : $($_.Exception.Message)" -ForegroundColor Red
        }
        [void]$metadata.Add($entry)
    }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

$report = [ordered]@{
    generatedUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    toolDir      = $ToolDir
    tools        = @($metadata)
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $MetadataPath -Encoding UTF8
Write-Host ''
Write-Host "Tool metadata written to $MetadataPath"
Write-Host "To use these tools: `$env:RELAY_SECURITY_TOOL_DIR = '$ToolDir'"

if ($failures -gt 0) {
    Write-Host "install-security-tools: $failures tool(s) failed to install" -ForegroundColor Red
    exit 1
}
Write-Host 'install-security-tools: all requested tools installed and verified'
exit 0
