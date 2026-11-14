# Run from the repository root after clean package, compose startup and warm-up.
# This resets only the dedicated flashsale-atomic benchmark database and Redis.
$ErrorActionPreference = 'Continue'
$compose = @('-p', 'flashsale-atomic', '-f', 'load-test/results/redis-lock-baseline/compose.yml')
$results = 'load-test/results/atomic-lock-comparison'

function Reset-Benchmark {
    & docker compose @compose exec -T postgres psql -U benchmark -d flashsale -v ON_ERROR_STOP=1 -c 'TRUNCATE simulation_requests, simulation_runs, order_items, orders, inventory, products RESTART IDENTITY CASCADE;'
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark database reset failed' }
    & docker compose @compose exec -T redis redis-cli FLUSHDB
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark Redis reset failed' }
}

foreach ($run in 1..3) {
    Reset-Benchmark
    & k6 run --quiet --env BASE_URL=http://127.0.0.1:18080 --summary-trend-stats 'avg,min,med,max,p(95),p(99)' --summary-export "$results/comparison-$run-summary.json" --out "json=$results/comparison-$run.json.gz" load-test/comparison.js *> "$results/comparison-$run.txt"
    $k6Result = $LASTEXITCODE
    $query = @'
COPY (
    SELECT p.name, i.stock AS final_stock, COUNT(o.id) AS confirmed
    FROM products p JOIN inventory i ON i.product_id = p.id
    LEFT JOIN orders o ON o.product_id = p.id AND o.order_status = 'SUCCESS'
    GROUP BY p.id, p.name, i.stock ORDER BY p.id
) TO STDOUT WITH CSV HEADER;
'@
    & docker compose @compose exec -T postgres psql -U benchmark -d flashsale -v ON_ERROR_STOP=1 -c $query | Set-Content "$results/comparison-$run-stock.csv"
    if ($LASTEXITCODE -ne 0) { throw "Stock query failed for run $run" }
    foreach ($row in (Import-Csv "$results/comparison-$run-stock.csv")) {
        if ($row.name -notlike 'NONE *') {
            if ([int]$row.final_stock -lt 0 -or [int]$row.confirmed + [int]$row.final_stock -ne 5) {
                throw "Stock invariant failed for $($row.name), run $run"
            }
        }
    }
    "Run $run : k6 exit=$k6Result" | Tee-Object -FilePath "$results/run-status.txt" -Append
    if ($k6Result -ne 0) { throw "k6 failed for run $run; inspect its output" }
}
