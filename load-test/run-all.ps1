# PowerShell script for running all k6 load tests
$ErrorActionPreference = "Stop"

$BASE_URL = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8080" }
$RESULTS_DIR = "load-test/results"
$TIMESTAMP = (Get-Date -Format "yyyyMMdd_HHmmss")

Write-Host "════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  FLASH SALE — K6 LOAD TEST SUITE" -ForegroundColor Cyan
Write-Host "  App: $BASE_URL" -ForegroundColor Cyan
Write-Host "  Time: $TIMESTAMP" -ForegroundColor Cyan
Write-Host "════════════════════════════════════════" -ForegroundColor Cyan

Write-Host "`nChecking app health..."
try {
    $health = Invoke-RestMethod -Uri "$BASE_URL/actuator/health" -Method Get
    if ($health.status -ne "UP") {
        throw "App returned status: $($health.status)"
    }
    Write-Host "App is healthy (UP)" -ForegroundColor Green
} catch {
    Write-Host "App is not running at $BASE_URL. Please run: docker compose up -d" -ForegroundColor Red
    exit 1
}

if (!(Test-Path $RESULTS_DIR)) {
    New-Item -ItemType Directory -Path $RESULTS_DIR | Out-Null
}

Write-Host "`n📋 Step 1/5: Smoke test..." -ForegroundColor Yellow
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/smoke_$TIMESTAMP.json" load-test/smoke-test.js

Write-Host "`n📋 Step 2/5: NONE mode (oversell bug demo)..." -ForegroundColor Yellow
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/none_$TIMESTAMP.json" load-test/purchase-none.js

Write-Host "`n📋 Step 3/5: PESSIMISTIC mode..." -ForegroundColor Yellow
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/pessimistic_$TIMESTAMP.json" load-test/purchase-pessimistic.js

Write-Host "`n📋 Step 4/5: REDIS mode (main solution)..." -ForegroundColor Yellow
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/redis_$TIMESTAMP.json" load-test/purchase-redis.js

Write-Host "`n📋 Step 5/5: Comparison (all 4 modes)..." -ForegroundColor Yellow
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/comparison_$TIMESTAMP.json" load-test/comparison.js

Write-Host "`n════════════════════════════════════════" -ForegroundColor Green
Write-Host "  ALL LOAD TESTS COMPLETED" -ForegroundColor Green
Write-Host "  Results saved to: $RESULTS_DIR/" -ForegroundColor Green
Write-Host "════════════════════════════════════════" -ForegroundColor Green
