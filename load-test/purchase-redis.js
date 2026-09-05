/**
 * LOAD TEST — mode=REDIS (giải pháp chính).
 *
 * Kịch bản: 4 stage tăng dần VU.
 * Mục đích: chứng minh Redis distributed lock ngăn oversell dưới tải HTTP thật.
 *
 * Chạy: k6 run load-test/purchase-redis.js --out json=load-test/results/redis.json
 */
import http from 'k6/http';
import { createProduct, resetStock, getCurrentStock, BASE } from './helpers/setup.js';
import { checkPurchaseResponse } from './helpers/checks.js';

const INITIAL_STOCK = 10;
const LOCK_MODE = 'REDIS';

export const options = {
    stages: [
        { duration: '10s', target: 10  },  // 10 VU
        { duration: '20s', target: 20  },  // 20 VU (peak — 2x stock)
        { duration: '20s', target: 50  },  // 50 VU (burst — 5x stock)
        { duration: '20s', target: 100 },  // 100 VU (stress — 10x stock)
        { duration: '10s', target: 0   },  // cool down
    ],
    thresholds: {
        'http_req_duration': [
            'p(95)<3000',
            'p(99)<5000',
        ],
        'http_req_failed': ['rate<0.01'],
        'checks': ['rate>0.95'],
    },
};

export function setup() {
    const productId = createProduct('REDIS Lock Test Item', 999000, INITIAL_STOCK);
    resetStock(productId, INITIAL_STOCK);
    console.log(`\nREDIS mode test starting. Initial stock: ${INITIAL_STOCK}`);
    console.log('   Expected: successCount = 10, finalStock = 0\n');
    return { productId, initialStock: INITIAL_STOCK };
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
    const { productId, initialStock } = data;
    const finalStock = getCurrentStock(productId);

    console.log('\n══════════════════════════════════════');
    console.log('  REDIS MODE — RESULT SUMMARY');
    console.log('══════════════════════════════════════');
    console.log(`  Initial stock:  ${initialStock}`);
    console.log(`  Final DB stock: ${finalStock}`);

    if (finalStock < 0) {
        console.log(` OVERSELL: finalStock=${finalStock} — REDIS lock FAILED!`);
    } else if (finalStock === 0) {
        console.log(` CORRECT: All ${initialStock} items sold, no oversell`);
    } else {
        console.log(` CORRECT: ${initialStock - finalStock} items sold, ${finalStock} remaining`);
    }
    console.log('══════════════════════════════════════\n');
}
