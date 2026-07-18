[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$repo=Resolve-Path (Join-Path $PSScriptRoot '..\..')
$temp=Join-Path ([IO.Path]::GetTempPath()) ('relay-distribution-fixture-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
  $package=Join-Path $temp 'package'; $meta=Join-Path $temp 'metadata'; New-Item -ItemType Directory $package,$meta | Out-Null
  $artifact=Join-Path $package 'payload.txt'; Set-Content -LiteralPath $artifact -Value 'relay-fixture-payload' -NoNewline
  $signScript=Join-Path $repo 'scripts\sign-artifacts.ps1'; $verifyScript=Join-Path $repo 'scripts\verify-tuf-metadata.ps1'
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $signScript -PackageRoot $package -MetadataRoot $meta -ManifestOnly | Out-Null
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyScript -MetadataPath (Join-Path $meta 'targets.json') -ArtifactPath $artifact -TargetName 'payload.txt' -ManifestOnly | Out-Null

  function Expect-Failure([scriptblock]$Action,[string]$Name){$failed=$false;try{&$Action}catch{$failed=$true};$exit=$LASTEXITCODE;if(-not $failed -and $exit -eq 0){throw "Expected failure did not occur: $Name"};Write-Output "PASS: $Name"}
  $manifest=Get-Content (Join-Path $meta 'targets.json') -Raw | ConvertFrom-Json
  $manifest.signed.expires=(Get-Date).ToUniversalTime().AddDays(-1).ToString('yyyy-MM-ddTHH:mm:ssZ'); $expired=Join-Path $temp 'expired.json'; $manifest|ConvertTo-Json -Depth 20|Set-Content $expired
  Expect-Failure {& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyScript -MetadataPath $expired -ArtifactPath $artifact -TargetName 'payload.txt' -ManifestOnly} 'expired metadata'
  $tampered=Join-Path $temp 'tampered.txt'; Set-Content $tampered -Value 'tampered' -NoNewline
  Expect-Failure {& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyScript -MetadataPath (Join-Path $meta 'targets.json') -ArtifactPath $tampered -TargetName 'payload.txt' -ManifestOnly} 'hash mismatch'
  $length=Get-Content (Join-Path $meta 'targets.json') -Raw|ConvertFrom-Json; $length.signed.targets.'payload.txt'.length=999999; $lengthPath=Join-Path $temp 'length.json'; $length|ConvertTo-Json -Depth 20|Set-Content $lengthPath
  Expect-Failure {& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyScript -MetadataPath $lengthPath -ArtifactPath $artifact -TargetName 'payload.txt' -ManifestOnly} 'length mismatch'

  $rsa=New-Object Security.Cryptography.RSACryptoServiceProvider 2048; $private=Join-Path $temp 'tuf-private.xml'; $public=Join-Path $temp 'tuf-public.xml'; $rsa.ToXmlString($true)|Set-Content $private; $rsa.ToXmlString($false)|Set-Content $public
  $signed=[ordered]@{_type='targets';spec_version='1.0.31';version=1;expires=(Get-Date).ToUniversalTime().AddDays(30).ToString('yyyy-MM-ddTHH:mm:ssZ');targets=[ordered]@{'payload.txt'=[ordered]@{length=(Get-Item $artifact).Length;hashes=[ordered]@{sha256=(Get-FileHash $artifact -Algorithm SHA256).Hash.ToLowerInvariant()}}}}
  function Canon([object]$v){if($null -eq $v){return 'null'};if($v -is [bool]){return $(if($v){'true'}else{'false'})};if($v -is [string]){return ([System.Web.Script.Serialization.JavaScriptSerializer]::new().Serialize($v))};if($v -is [System.Collections.IDictionary]){$p=@();foreach($k in ($v.Keys|Sort-Object)){$p+=(Canon ([string]$k))+':'+(Canon $v[$k])};return '{'+($p-join ',')+'}'};if($v -is [System.Collections.IEnumerable] -and -not($v -is [string])){$p=@();foreach($i in $v){$p+=Canon $i};return '['+($p-join ',')+']'};if($v -is [pscustomobject]){$m=[ordered]@{};foreach($x in ($v.PSObject.Properties|Sort-Object Name)){$m[$x.Name]=$x.Value};return Canon $m};return [string]$v}
  $sig=[Convert]::ToBase64String($rsa.SignData([Text.Encoding]::UTF8.GetBytes((Canon $signed)),(New-Object Security.Cryptography.SHA256CryptoServiceProvider)))
  $signedMeta=[ordered]@{signatures=@([ordered]@{keyid='fixture-key';sig=$sig});signed=$signed}; $signedPath=Join-Path $temp 'signed.json'; $signedMeta|ConvertTo-Json -Depth 20|Set-Content $signedPath
  $root=[ordered]@{keys=[ordered]@{'fixture-key'=[ordered]@{keytype='rsa';scheme='rsassa-pkcs1v1_5-sha256';keyval=[ordered]@{public=(Get-Content $public -Raw).Trim()}}}}; $rootPath=Join-Path $temp 'trusted-root.json'; $root|ConvertTo-Json -Depth 20|Set-Content $rootPath
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyScript -MetadataPath $signedPath -ArtifactPath $artifact -TargetName 'payload.txt' -TrustedRootPath $rootPath | Out-Null; Write-Output 'PASS: trusted TUF signature round-trip'
  $bad=Join-Path $temp 'bad-signed.json'; $signedMeta.signatures[0].sig='PLACEHOLDER'; $signedMeta|ConvertTo-Json -Depth 20|Set-Content $bad
  Expect-Failure {& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyScript -MetadataPath $bad -ArtifactPath $artifact -TargetName 'payload.txt' -TrustedRootPath $rootPath} 'placeholder signature rejected'
  Write-Output 'Distribution fixture checks passed.'
} finally { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }
