param(
    [ValidateSet('up', 'check', 'down')]
    [string]$Action = 'up',
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$StateDir = Join-Path $Root '.agenthub'
$LogDir = Join-Path $StateDir 'logs'
$PidFile = Join-Path $StateDir 'pids.json'
$EnvFile = Join-Path $Root '.env'

function New-RandomSecret([int]$Bytes = 36) {
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Ensure-Environment {
    if (Test-Path $EnvFile) { return }
    $content = Get-Content -Raw (Join-Path $Root '.env.example')
    $content = $content -replace '(?m)^JWT_SECRET=.*$', ('JWT_SECRET=' + (New-RandomSecret))
    $content = $content -replace '(?m)^AGENTHUB_INTERNAL_TOKEN=.*$', ('AGENTHUB_INTERNAL_TOKEN=' + (New-RandomSecret))
    $content = $content -replace '(?m)^AGENTHUB_KMS_MASTER_KEY=.*$', ('AGENTHUB_KMS_MASTER_KEY=' + (New-RandomSecret))
    $content = $content -replace '(?m)^AGENTHUB_ECOSYSTEM_SIGNING_KEY=.*$', ('AGENTHUB_ECOSYSTEM_SIGNING_KEY=' + (New-RandomSecret))
    $content += "`r`nAGENTHUB_DEMO_MODE=true`r`n"
    Set-Content -LiteralPath $EnvFile -Value $content -Encoding utf8
    Write-Host 'Created .env with random local-only service secrets.'
}

function Test-Command([string]$Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Show-Check {
    $checks = [ordered]@{
        docker = Test-Command 'docker'
        java = Test-Command 'java'
        maven = Test-Command 'mvn'
        node = Test-Command 'node'
        npm = Test-Command 'npm'
        envFile = Test-Path $EnvFile
        pythonVenv = Test-Path (Join-Path $Root 'python-engine\.venv\Scripts\python.exe')
        nodeModules = Test-Path (Join-Path $Root 'web-ui\node_modules')
    }
    [pscustomobject]$checks
    if ($checks.Values -contains $false) { exit 1 }
}

if ($Action -eq 'check') {
    Show-Check
    exit 0
}

if ($Action -eq 'down') {
    if (Test-Path $PidFile) {
        $pids = Get-Content -Raw $PidFile | ConvertFrom-Json
        foreach ($id in @($pids.java, $pids.python, $pids.web)) {
            if ($id) { Stop-Process -Id $id -ErrorAction SilentlyContinue }
        }
        Remove-Item -LiteralPath $PidFile -Force
    }
    & docker compose -f (Join-Path $Root 'docker-compose.yml') down
    exit $LASTEXITCODE
}

Ensure-Environment
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
& docker compose -f (Join-Path $Root 'docker-compose.yml') up -d
if ($LASTEXITCODE -ne 0) { throw 'Docker infrastructure failed to start' }

$PythonDir = Join-Path $Root 'python-engine'
$PythonExe = Join-Path $PythonDir '.venv\Scripts\python.exe'
if (-not (Test-Path $PythonExe)) {
    if ($SkipInstall) { throw 'Python virtual environment is missing' }
    & python -m venv (Join-Path $PythonDir '.venv')
    & $PythonExe -m pip install -r (Join-Path $PythonDir 'requirements.txt')
}
if (-not (Test-Path (Join-Path $Root 'web-ui\node_modules'))) {
    if ($SkipInstall) { throw 'web-ui/node_modules is missing' }
    & npm.cmd ci --prefix (Join-Path $Root 'web-ui')
}

$python = Start-Process -FilePath $PythonExe -ArgumentList 'main.py' -WorkingDirectory $PythonDir -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $LogDir 'python.out.log') -RedirectStandardError (Join-Path $LogDir 'python.err.log')
$java = Start-Process -FilePath 'mvn.cmd' -ArgumentList 'spring-boot:run' -WorkingDirectory (Join-Path $Root 'java-console') -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $LogDir 'java.out.log') -RedirectStandardError (Join-Path $LogDir 'java.err.log')
$web = Start-Process -FilePath 'npm.cmd' -ArgumentList 'run', 'dev', '--', '--host', '127.0.0.1' -WorkingDirectory (Join-Path $Root 'web-ui') -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $LogDir 'web.out.log') -RedirectStandardError (Join-Path $LogDir 'web.err.log')

[ordered]@{ java = $java.Id; python = $python.Id; web = $web.Id; startedAt = (Get-Date).ToString('o') } |
    ConvertTo-Json | Set-Content -LiteralPath $PidFile -Encoding utf8
Write-Host 'AgentHub local stack started: web http://127.0.0.1:5173, Java :8080, Python :8000.'
