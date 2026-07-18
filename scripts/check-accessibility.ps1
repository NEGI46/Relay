[CmdletBinding()]
param([string]$SourceRoot='app/src/main/java')
$ErrorActionPreference='Stop'
$files = Get-ChildItem -LiteralPath $SourceRoot -Recurse -Filter *.kt -File
$violations = @()
foreach($file in $files){
  $lines = Get-Content -LiteralPath $file.FullName
  for($i=0; $i -lt $lines.Count; $i++){
    if($lines[$i] -match '\b(?:Button|OutlinedButton|TextButton)\s*\('){
      $end = [Math]::Min($lines.Count - 1, $i + 10)
      $window = ($lines[$i..$end] -join "`n")
      if($window -notmatch '\bText\s*\(' -and $window -notmatch 'contentDescription\s*='){
        $violations += "$($file.FullName):$($i + 1)"
      }
    }
  }
}
if($violations.Count -gt 0){ $violations | ForEach-Object { Write-Error "Potential unlabeled button: $_" }; exit 1 }
Write-Output "Accessibility source contract passed for $($files.Count) Kotlin files."
