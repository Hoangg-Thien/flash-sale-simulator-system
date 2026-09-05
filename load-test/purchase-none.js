/**
 * LOAD TEST — mode=NONE (cố ý KHÔNG lock, tái hiện oversell bug).
 *
 * Kịch bản: 4 stage, tăng dần VU.
 * Mục đích: chứng minh với k6 (HTTP thật) rằng oversell xảy ra.
 * stock=20, tất cả VU đều cố mua → successCount > 20 là oversell.
 *
 * Chạy: k6 run load-test/purchase-none.js --out json=load-test/results/none.json
 */
import http from 'k6/http';
import { createProduct, resetStock, getCurrentStock, BASE } from './helpers/setup.js';
import { checkPurchaseResponse } from './helpers/checks.js';

const INITIAL_STOCK = 20;
const LOCK_MODE = 'NONE';

export const options = {
    stages: [
        // Stage 1: 10 VU × 10s — warm up
        { duration: '10s', target: 10 },
        // Stage 2: 20 VU × 15s — stock=20, mỗi VU cố mua 1 lần
        { duration: '15s', target: 20 },
        // Stage 3: 50 VU × 15s — burst load, chắc chắn oversell
        { duration: '15s', target: 50 },
        // Stage 4: cool down
        { duration: '5s',  target: 0 },
    ],
    thresholds: {
        'http_req_failed': ['rate<0.01'],  // < 1% network error
        'checks': ['rate>0.95'],
    },
};

export function setup() {
    const productId = createProduct('NONE Lock Test Item', 999000, INITIAL_STOCK);
    resetStock(productId, INITIAL_STOCK);
    console.log(`\n NONE mode test starting. Initial stock: ${INITIAL_STOCK}`);
    console.log('   Expected: successCount > initialStock (oversell bug)\n');
    return { productId };
}

export default function (data) {
    const { productId } = data;

    const payload = JSON.stringify({
        productId,
        quantity: 1,
        lockMode: LOCK_MODE,
    });

    const res = http.post(`${BASE}/api/v1/orders`, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { lockMode: LOCK_MODE },
    });

    checkPurchaseResponse(res, LOCK_MODE);
}

export function teardown(data) {
    const { productId } = data;
    const finalStock = getCurrentStock(productId);

    console.log('\n══════════════════════════════════════');
    console.log('  NONE MODE — RESULT SUMMARY');
    console.log('══════════════════════════════════════');
    console.log(`  Final DB stock: ${finalStock}`);
    if (finalStock < 0) {
        console.log(`  OVERSELL CONFIRMED: stock = ${finalStock} (negative!)`);
    } else {
        console.log(`  No oversell detected this run (race condition may not have triggered)`);
    }
    console.log('══════════════════════════════════════\n');
}
