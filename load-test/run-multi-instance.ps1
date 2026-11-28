param(
    [int]$Rounds = 3,
    [int]$Users = 20,
    [int]$InitialStock = 10,
    [int]$Quantity = 1,
    [switch]$IncludeUnsafe,
    [string]$Results = 'load-test/results/multi-instance-proof'
)
# PowerShell 5 emits native stderr as error records; check every native exit code explicitly.
$ErrorActionPreference = 'Continue'
if ($Rounds -lt 1 -or $Rounds -gt 10 -or $Users -lt 2 -or $Users -gt 200 -or
    $InitialStock -lt 0 -or $Quantity -lt 1 -or $Quantity -gt 100) { throw 'Invalid benchmark parameters' }
if (-not (Test-Path 'pom.xml')) { throw 'Run this script from the repository root' }
. "$PSScriptRoot/helpers/verify-multi-instance.ps1"
$compose = @('-p', 'flashsale-multi-proof', '-f', 'docker-compose.yml', '-f', 'load-test/multi-instance.compose.yml')
New-Item -ItemType Directory -Force $Results -ErrorAction Stop | Out-Null
function Write-Utf8($Path, $Text) {
    [IO.File]::WriteAllText((Join-Path $PWD $Path), [string]$Text, [Text.UTF8Encoding]::new($false))
}
$existing = @(& docker compose @compose ps -aq)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect benchmark project' }
if ($existing.Count -gt 0) { throw 'flashsale-multi-proof already exists; inspect/clean that dedicated project before retrying' }
$rows = @()
$started = $false
try {
    $started = $true
    & docker compose @compose up -d --build *> "$Results/compose-build.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Compose startup/build failed; inspect compose-build.txt' }
    foreach ($url in @('http://127.0.0.1:18080','http://127.0.0.1:18081')) {
        $ready = $false
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            try { if ((Invoke-RestMethod "$url/actuator/health" -TimeoutSec 2 -ErrorAction Stop).status -eq 'UP') { $ready = $true; break } } catch {}
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "App did not become healthy: $url" }
    }
    $dbUser = (& docker compose @compose exec -T postgres printenv POSTGRES_USER)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read benchmark DB user' }
    $dbName = (& docker compose @compose exec -T postgres printenv POSTGRES_DB)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read benchmark DB name' }
    $modes = @('ATOMIC','OPTIMISTIC','PESSIMISTIC','REDIS')
    if ($IncludeUnsafe) { $modes += 'NONE' }
    foreach ($round in 0..$Rounds) {
        foreach ($mode in $modes) {
            $runId = [guid]::NewGuid().ToString('N')
            $stock = if ($mode -eq 'NONE') { 1 } else { $InitialStock }
            $qty = if ($mode -eq 'NONE') { 1 } else { $Quantity }
            $prefix = if ($round -eq 0) { "$Results/warmup-$($mode.ToLowerInvariant())" } else { "$Results/round-$round-$($mode.ToLowerInvariant())" }
            $unsafeFlag = if ($mode -eq 'NONE') { '1' } else { '0' }
            & k6 run --quiet --env 'BASE_URLS=http://127.0.0.1:18080,http://127.0.0.1:18081' `
                --env "LOCK_MODE=$mode" --env "RUN_UNSAFE=$unsafeFlag" --env "RUN_ID=$runId" `
                --env "INITIAL_STOCK=$stock" --env "QUANTITY=$qty" --env "CONCURRENT_USERS=$Users" `
                --summary-trend-stats 'avg,min,med,max,p(95),p(99)' --summary-export "$prefix-summary.json" `
                --out "json=$prefix.json.gz" load-test/multi-instance.js *> "$prefix.txt"
            if ($LASTEXITCODE -ne 0) { throw "k6 failed: $prefix.txt" }
            $summary = Get-Content -Raw "$prefix-summary.json" | ConvertFrom-Json -ErrorAction Stop
            $data = $summary.setup_data
            $productId = [long]$data.productId
            if ($productId -le 0 -or $data.runId -ne $runId -or $data.mode -ne $mode) { throw 'Invalid k6 setup evidence' }
            $metrics = $summary.metrics
            $confirmed = [int]$metrics.purchase_confirmed.count
            $rejected = [int]$metrics.purchase_rejected.count
            if ($confirmed + $rejected -ne $Users -or $metrics.checks.fails -ne 0) { throw 'Incomplete or failed HTTP evidence' }
            $query = @"
COPY (
 SELECT p.id, p.name, i.stock,
  (SELECT count(*) FROM orders o WHERE o.product_id=p.id) AS orders,
  (SELECT count(DISTINCT o.idempotency_key) FROM orders o WHERE o.product_id=p.id) AS keys,
  (SELECT coalesce(sum(oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
   WHERE o.product_id=p.id AND o.order_status='SUCCESS') AS units,
  (SELECT count(*) FROM orders o WHERE o.product_id=p.id AND o.idempotency_key LIKE 'mi-$runId-0-%') AS node0_orders,
  (SELECT count(*) FROM orders o WHERE o.product_id=p.id AND o.idempotency_key LIKE 'mi-$runId-1-%') AS node1_orders
 FROM products p JOIN inventory i ON i.product_id=p.id WHERE p.id=$productId
) TO STDOUT WITH CSV HEADER;
"@
            # Credentials come from the dedicated DB container, not from host assumptions.
            $csv = & docker compose @compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U $dbUser -d $dbName -c $query
            if ($LASTEXITCODE -ne 0) { throw 'Database evidence query failed' }
            Write-Utf8 "$prefix-stock.csv" (($csv -join "`n") + "`n")
            $records = @($csv | ConvertFrom-Csv -ErrorAction Stop)
            if ($records.Count -ne 1 -or $records[0].name -ne "multi-$mode-$runId") { throw 'Wrong/missing database product evidence' }
            $record = $records[0]
            Assert-MultiInstanceInventory $record $stock $qty $confirmed $mode $Users
            $nodeCounts = @()
            foreach ($node in 0..1) {
                $attempts = [int]$metrics.PSObject.Properties["purchase_attempts{node:$node}"].Value.count
                if ($attempts -le 0) { throw 'One instance received no purchases' }
                $url = $data.nodes[$node].url
                $view = Invoke-WebRequest "$url/api/v1/products/$productId" -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
                if (($view.Content | ConvertFrom-Json).stock -ne [int]$record.stock -or
                    $view.Headers['X-Instance-Id'] -ne $data.nodes[$node].instanceId) { throw 'HTTP view disagrees with persisted stock/instance' }
                $service = if ($node -eq 0) { 'app-a' } else { 'app-b' }
                $logs = @(& docker compose @compose logs --no-color --no-log-prefix $service)
                if ($LASTEXITCODE -ne 0) { throw 'Cannot verify instance request logs' }
                $marker = "requestId=mi-$runId-$node-"
                $received = @($logs | Where-Object { $_.Contains($marker) -and $_.Contains('HTTP POST /api/v1/orders status=') }).Count
                if ($received -ne $attempts) { throw "Instance log count $received differs from HTTP attempt count $attempts" }
                $nodeCounts += $received
            }
            $row = [pscustomobject]@{
                round=$round; mode=$mode; product_id=$productId; initial_stock=$stock; quantity=$qty
                confirmed=$confirmed; rejected=$rejected; purchased_units=[int]$record.units; final_stock=[int]$record.stock
                node0_received=$nodeCounts[0]; node1_received=$nodeCounts[1]
                node0_orders=[int]$record.node0_orders; node1_orders=[int]$record.node1_orders
                oversold_units=[math]::Max(0, [int]$record.units - $stock)
                unaccounted_units=$stock - [int]$record.units - [int]$record.stock
                http_rps=$metrics.http_reqs.rate; p50_ms=$metrics.http_req_duration.med
                p95_ms=$metrics.http_req_duration.'p(95)'; p99_ms=$metrics.http_req_duration.'p(99)'
            }
            $rows += $row
            Write-Utf8 "$Results/comparison.csv" (($rows | ConvertTo-Csv -NoTypeInformation) -join "`n")
            "round=$round mode=$mode confirmed=$confirmed stock=$($record.stock) received=$($nodeCounts -join '/')" |
                Tee-Object -FilePath "$Results/run-status.txt" -Append
        }
    }
    if ($IncludeUnsafe -and -not ($rows | Where-Object { $_.round -gt 0 -and $_.mode -eq 'NONE' -and $_.oversold_units -gt 0 })) {
        throw 'NONE overselling was not reproduced in measured rounds; no unsafe proof claimed'
    }
} finally {
    if ($started) {
        foreach ($service in @('app-a','app-b')) {
            $logs = & docker compose @compose logs --no-color --no-log-prefix $service
            Write-Utf8 "$Results/requests-$service.txt" ($logs -join "`n")
        }
        $ids = @(& docker compose @compose ps -aq)
        if ($ids.Count -gt 0) {
            & docker inspect --format '{{.Name}} image={{.Image}} cpus={{.HostConfig.NanoCpus}} memory={{.HostConfig.Memory}}' @ids |
                Set-Content "$Results/containers.txt"
        }
        & docker compose @compose down -v *> "$Results/cleanup.txt"
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark cleanup failed; inspect cleanup.txt' }
    }
}
