#!/usr/bin/env bash
set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
RESULTS_DIR="load-test/results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo "════════════════════════════════════════"
echo "  FLASH SALE — K6 LOAD TEST SUITE"
echo "  App: $BASE_URL"
echo "  Time: $TIMESTAMP"
echo "════════════════════════════════════════"

echo ""
echo "🔍 Checking app health..."
if ! curl -sf "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then
    echo " App is not running at $BASE_URL"
    echo " Start with: docker compose up -d"
    exit 1
fi
echo " App is healthy"

mkdir -p "$RESULTS_DIR"

echo ""
echo "📋 Step 1/5: Smoke test..."
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/smoke_$TIMESTAMP.json" load-test/smoke-test.js

echo ""
echo "📋 Step 2/5: NONE mode (oversell bug demo)..."
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/none_$TIMESTAMP.json" load-test/purchase-none.js

echo ""
echo "📋 Step 3/5: PESSIMISTIC mode..."
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/pessimistic_$TIMESTAMP.json" load-test/purchase-pessimistic.js

echo ""
echo "📋 Step 4/5: REDIS mode (main solution)..."
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/redis_$TIMESTAMP.json" load-test/purchase-redis.js

echo ""
echo "📋 Step 5/5: Comparison (all 4 modes)..."
k6 run --env BASE_URL="$BASE_URL" --out json="$RESULTS_DIR/comparison_$TIMESTAMP.json" load-test/comparison.js

echo ""
echo "════════════════════════════════════════"
echo "  ALL LOAD TESTS COMPLETED"
echo "  Results saved to: $RESULTS_DIR/"
echo "════════════════════════════════════════"
