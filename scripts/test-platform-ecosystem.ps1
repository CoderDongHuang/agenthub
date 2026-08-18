param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$RuntimeUrl = 'http://localhost:8000',
    [string]$Username = 'admin',
    [string]$Password = 'admin123'
)

$ErrorActionPreference = 'Stop'
$script:CsrfToken = ''
$script:CookieJar = [System.IO.Path]::GetTempFileName()
trap {
    Remove-Item -LiteralPath $script:CookieJar -Force -ErrorAction SilentlyContinue
    throw $_
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
}

function Invoke-AgentHubApi([string]$Method, [string]$Path, $Body = $null) {
    $arguments = @('-sS', '--fail-with-body', '-b', $script:CookieJar, '-c', $script:CookieJar,
        '-X', $Method.ToUpperInvariant(), '-H', 'Accept:application/json')
    if ($script:CsrfToken) { $arguments += @('-H', "X-XSRF-TOKEN:$($script:CsrfToken)") }
    $bodyJson = $null
    if ($null -ne $Body) {
        $bodyJson = $Body | ConvertTo-Json -Depth 16 -Compress
        $arguments += @('-H', 'Content-Type:application/json', '--data-binary', '@-')
    }
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    if ($null -ne $bodyJson) { $raw = $bodyJson | & curl.exe @arguments "$BaseUrl$Path" 2>&1 }
    else { $raw = & curl.exe @arguments "$BaseUrl$Path" 2>&1 }
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    if ($exit -ne 0) { throw "API request failed: $Method $Path - $($raw -join ' ')" }
    $response = ($raw -join "`n") | ConvertFrom-Json
    if ($response.code -ne 200) { throw "API failed: $Method $Path - $($response.message)" }
    return $response.data
}

function ConvertTo-Base64Url([byte[]]$Value) {
    return [Convert]::ToBase64String($Value).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$csrf = Invoke-AgentHubApi Get '/api/auth/csrf'
$script:CsrfToken = $csrf.token
$csrfCookie = Get-Content -LiteralPath $script:CookieJar | Where-Object { $_ -match 'XSRF-TOKEN' } | Select-Object -Last 1
$script:CsrfToken = ($csrfCookie -split "`t")[-1]
Assert-True ([bool]$script:CsrfToken) 'CSRF token'

$login = Invoke-AgentHubApi Post '/api/auth/login' @{ username = $Username; password = $Password }
Assert-True ($login.username -eq $Username) 'administrator login'

$overview = Invoke-AgentHubApi Get '/api/ecosystem/overview'
Assert-True ($overview.controls.Count -eq 7) 'seven ecosystem control groups'

$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$artifactBytes = [Text.Encoding]::UTF8.GetBytes("ecosystem-e2e-artifact-$suffix")
$package = Invoke-AgentHubApi Post '/api/ecosystem/packages' @{
    packageName = "ecosystem.e2e.$suffix"
    version = '1.0.0'
    packageType = 'tool'
    visibility = 'private'
    sourceUri = "registry://private/ecosystem.e2e/$suffix"
    artifactBase64 = [Convert]::ToBase64String($artifactBytes)
    manifest = @{ entrypoint = 'e2e:EchoTool'; permissions = @(); dependencies = @{ agenthub_sdk = '0.1.0' } }
    compatibility = @{ minPlatformVersion = '0.1.0' }
}
Assert-True ($package.signatureValid) 'server-side package signing'
$verification = Invoke-AgentHubApi Post "/api/ecosystem/packages/$($package.id)/verify"
Assert-True ($verification.valid) 'package signature verification'
$artifact = Invoke-AgentHubApi Get "/api/ecosystem/packages/$($package.id)/artifact"
Assert-True ($artifact.contentBase64 -eq [Convert]::ToBase64String($artifactBytes)) 'private artifact round trip'
$scan = Invoke-AgentHubApi Post "/api/ecosystem/packages/$($package.id)/scan"
Assert-True ($scan.status -eq 'passed') 'package source and supply-chain scan'

$sandboxAllowed = Invoke-AgentHubApi Post '/api/ecosystem/sandbox/evaluate' @{
    timeoutSeconds = 20; memoryMb = 256; cpuCores = 0.5; networkHosts = @('api.example.com'); mounts = @('data/read-only')
}
$sandboxBlocked = Invoke-AgentHubApi Post '/api/ecosystem/sandbox/evaluate' @{
    timeoutSeconds = 500; memoryMb = 4096; cpuCores = 4; networkHosts = @('*'); mounts = @('../../host')
}
Assert-True ($sandboxAllowed.allowed) 'valid sandbox profile'
Assert-True (-not $sandboxBlocked.allowed) 'unsafe sandbox profile blocked'

$mcp = Invoke-AgentHubApi Post '/api/ecosystem/mcp/connections' @{
    name = 'ecosystem-e2e-runtime'; direction = 'client'; transport = 'http'
    endpoint = "$($RuntimeUrl.TrimEnd('/'))/mcp"; protocolVersion = '2025-03-26'
}
$mcpProbe = Invoke-AgentHubApi Post "/api/ecosystem/mcp/connections/$($mcp.id)/probe"
Assert-True ($mcpProbe.status -eq 'healthy') 'MCP initialize handshake'

$developer = Invoke-AgentHubApi Post '/api/ecosystem/developer-apps' @{
    appName = "ecosystem-e2e-$suffix"; apiVersion = 'v1'; quotaPerMinute = 5
    tenantRoute = 'e2e-primary'; allowedOperations = @('platform.echo', 'platform.capabilities', 'agent.chat')
}
Assert-True ($developer.secretShownOnce) 'developer secret issued once'
$apps = Invoke-AgentHubApi Get '/api/ecosystem/developer-apps'
Assert-True (-not (($apps | ConvertTo-Json -Depth 10) -match [regex]::Escape($developer.secret))) 'developer secret is not listed'

$gatewayBody = @{ operation = 'platform.echo'; input = @{ message = 'signed gateway e2e' } } | ConvertTo-Json -Depth 6 -Compress
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$nonce = [Guid]::NewGuid().ToString('N')
$bodyBytes = [Text.Encoding]::UTF8.GetBytes($gatewayBody)
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $digest = ([BitConverter]::ToString($sha256.ComputeHash($bodyBytes))).Replace('-', '').ToLowerInvariant()
}
finally { $sha256.Dispose() }
$canonical = "POST`n/api/gateway/v1/invoke`n$timestamp`n$nonce`n$digest"
$hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($developer.secret))
try { $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))) }
finally { $hmac.Dispose() }
$gatewayHeaders = @{
    'X-Developer-Key' = $developer.publicKey; 'X-Timestamp' = "$timestamp"
    'X-Nonce' = $nonce; 'X-Signature' = $signature
}
$gatewayEnvelope = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/gateway/v1/invoke" -Headers $gatewayHeaders -ContentType 'application/json' -Body $gatewayBody
Assert-True ($gatewayEnvelope.data.output.message -eq 'signed gateway e2e') 'signed gateway invocation'
$replayBlocked = $false
try { Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/gateway/v1/invoke" -Headers $gatewayHeaders -ContentType 'application/json' -Body $gatewayBody | Out-Null }
catch { $replayBlocked = $_.Exception.Response.StatusCode.value__ -eq 409 }
Assert-True $replayBlocked 'gateway nonce replay protection'

$agentBody = @{ operation = 'agent.chat'; input = @{ agentId = 1; message = 'Reply with one short sentence confirming the platform gateway is working.' } } | ConvertTo-Json -Depth 6 -Compress
$agentTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$agentNonce = [Guid]::NewGuid().ToString('N')
$agentBodyBytes = [Text.Encoding]::UTF8.GetBytes($agentBody)
$agentSha256 = [Security.Cryptography.SHA256]::Create()
try { $agentDigest = ([BitConverter]::ToString($agentSha256.ComputeHash($agentBodyBytes))).Replace('-', '').ToLowerInvariant() }
finally { $agentSha256.Dispose() }
$agentCanonical = "POST`n/api/gateway/v1/invoke`n$agentTimestamp`n$agentNonce`n$agentDigest"
$agentHmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($developer.secret))
try { $agentSignature = ConvertTo-Base64Url ($agentHmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($agentCanonical))) }
finally { $agentHmac.Dispose() }
$agentHeaders = @{
    'X-Developer-Key' = $developer.publicKey; 'X-Timestamp' = "$agentTimestamp"
    'X-Nonce' = $agentNonce; 'X-Signature' = $agentSignature
}
$agentEnvelope = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/gateway/v1/invoke" -Headers $agentHeaders -ContentType 'application/json' -Body $agentBody
Assert-True ($agentEnvelope.data.output.agentId -eq 1) 'tenant Agent routed through gateway'
Assert-True (-not [string]::IsNullOrWhiteSpace($agentEnvelope.data.output.reply)) 'Agent gateway returned a real reply'

$document = Invoke-AgentHubApi Post '/api/ecosystem/multimodal/extract' @{
    fileName = 'incident.txt'; mediaType = 'text/plain'; semantic = $false
    contentBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('Contact ops@example.com or 13800138000'))
}
Assert-True ($document.status -eq 'completed') 'document structured extraction'
Assert-True (@($document.extraction.emails).Count -eq 1) 'document entity extraction'
$pixelPng = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAANSURBVBhXY2Bo+P8fAASCAn/IHfwsAAAAAElFTkSuQmCC'
$image = Invoke-AgentHubApi Post '/api/ecosystem/multimodal/extract' @{
    fileName = 'pixel.png'; mediaType = 'image/png'; semantic = $true; contentBase64 = $pixelPng
}
Assert-True ($image.status -eq 'needs_provider') 'semantic provider requirement is explicit'
Assert-True ($image.extraction.width -eq 1) 'image structural extraction'

$scale = Invoke-AgentHubApi Post '/api/ecosystem/worker-pools/scale-plan' @{
    poolName = 'ecosystem-e2e-worker'; region = 'local-primary'; minReplicas = 1; maxReplicas = 10
    targetQueueDepth = 10; currentReplicas = 1; queueDepth = 42
}
Assert-True ($scale.desiredReplicas -eq 5) 'worker scaling plan'
$drill = Invoke-AgentHubApi Post '/api/ecosystem/resilience/drills' @{
    drillType = 'dependency_probe'; sourceRegion = 'local-primary'; targetRegion = 'local-secondary'
}
Assert-True ($drill.status -eq 'passed') 'PostgreSQL and Redis dependency drill'

$portal = Invoke-AgentHubApi Get '/api/ecosystem/developer-portal'
Assert-True ($portal.authentication.algorithm -eq 'HMAC-SHA256') 'developer portal signing specification'
$health = Invoke-AgentHubApi Get '/api/ecosystem/health-report'
$healthJson = $health | ConvertTo-Json -Depth 20
Assert-True (-not ($healthJson -match [regex]::Escape($developer.secret))) 'health report redaction'
Assert-True (-not $health.redaction.secretValuesIncluded) 'health report declares no secret values'

[ordered]@{
    status = 'PASS'
    migration = 'V16'
    ecosystemControls = $overview.controls.Count
    packageSignature = $verification.valid
    privateArtifactRoundTrip = $true
    sandboxBlockedUnsafeProfile = -not $sandboxBlocked.allowed
    mcpHandshake = $mcpProbe.status
    gatewayHmac = 'verified'
    gatewayReplayBlocked = $replayBlocked
    gatewayAgentReply = $true
    documentExtraction = $document.status
    imageSemanticPhase = $image.status
    desiredWorkers = $scale.desiredReplicas
    resilienceDrill = $drill.status
    healthReportSecretsExposed = $health.redaction.secretValuesIncluded
} | ConvertTo-Json

Remove-Item -LiteralPath $script:CookieJar -Force -ErrorAction SilentlyContinue
