param(
    [string]$JavaBaseUrl = 'http://127.0.0.1:8080',
    [string]$PythonBaseUrl = 'http://127.0.0.1:8000',
    [string]$BearerToken = '',
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputPath) {
    $directory = Join-Path $Root 'artifacts'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $OutputPath = Join-Path $directory ('health-report-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
}

function Invoke-SafeGet([string]$Url, [hashtable]$Headers = @{}) {
    try {
        $response = Invoke-RestMethod -Uri $Url -Headers $Headers -TimeoutSec 10
        return [ordered]@{ status = 'pass'; response = $response }
    } catch {
        return [ordered]@{ status = 'fail'; error = $_.Exception.GetType().Name }
    }
}

$headers = @{}
if ($BearerToken) { $headers.Authorization = 'Bearer ' + $BearerToken }
$nativeErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $gitStatus = (& git -c core.excludesFile= -C $Root status --short 2>$null) -join "`n"
    $gitExitCode = $LASTEXITCODE
    $dockerStatus = (& docker compose -f (Join-Path $Root 'docker-compose.yml') ps --format json 2>$null) -join "`n"
    $dockerExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $nativeErrorPreference
}
$report = [ordered]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    shareable = $true
    secretValuesIncluded = $false
    java = Invoke-SafeGet ($JavaBaseUrl.TrimEnd('/') + '/api/health')
    python = Invoke-SafeGet ($PythonBaseUrl.TrimEnd('/') + '/health')
    ecosystem = if ($BearerToken) { Invoke-SafeGet ($JavaBaseUrl.TrimEnd('/') + '/api/ecosystem/health-report') $headers } else { @{ status = 'skipped'; reason = 'BearerToken not supplied' } }
    docker = @{ status = if ($dockerExitCode -eq 0) { 'pass' } else { 'warning' }; summary = $dockerStatus }
    repository = @{ dirty = [bool]$gitStatus; changedPaths = @($gitStatus -split "`n" | Where-Object { $_ } | ForEach-Object { $_.Substring([Math]::Min(3, $_.Length)) }) }
}

$json = $report | ConvertTo-Json -Depth 16
$json = $json -replace '(?i)("(?:password|secret|token|authorization|api[_-]?key)"\s*:\s*)"[^"]*"', '$1"[REDACTED]"'
Set-Content -LiteralPath $OutputPath -Value $json -Encoding utf8
Write-Output $OutputPath
