param([switch]$SkipStack)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $script:PSScriptRoot)
if (-not (Test-Path '.env')) { Copy-Item '.env.example' '.env' }

if (-not $SkipStack) {
    Write-Host '==> Building and starting the stand'
    docker compose up -d --build
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host '==> Waiting for Gateway health'
for ($i = 0; $i -lt 150; $i++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing 'http://localhost:8080/health' -TimeoutSec 3
        if ($response.StatusCode -eq 200) { break }
    } catch { Start-Sleep -Seconds 2 }
}

& mvn -B -f services/pom.xml -Papi-tests test
exit $LASTEXITCODE
