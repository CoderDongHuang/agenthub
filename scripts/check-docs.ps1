$source = Join-Path $PSScriptRoot '..\docs'
$target = Join-Path $PSScriptRoot '..\web-ui\public\docs'
$failed = $false
Get-ChildItem -LiteralPath $source -Filter '*.md' | ForEach-Object {
  $copy = Join-Path $target $_.Name
  if (!(Test-Path -LiteralPath $copy) -or ((Get-FileHash $_.FullName).Hash -ne (Get-FileHash $copy).Hash)) {
    Write-Error "Documentation drift: $($_.Name)"; $failed = $true
  }
}
if ($failed) { exit 1 }
