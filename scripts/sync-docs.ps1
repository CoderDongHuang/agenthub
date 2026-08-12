$source = Join-Path $PSScriptRoot '..\docs'
$target = Join-Path $PSScriptRoot '..\web-ui\public\docs'
Get-ChildItem -LiteralPath $source -Filter '*.md' | ForEach-Object {
  Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $target $_.Name) -Force
}
Write-Output "Synchronized documentation from docs/ to web-ui/public/docs/"
