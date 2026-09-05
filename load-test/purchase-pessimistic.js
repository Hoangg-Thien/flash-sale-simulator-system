/**
 * LOAD TEST — mode=PESSIMISTIC (SELECT FOR UPDATE).
 */
import http from 'k6/http';
import { createProduct, resetStock, getCurrentStock, BASE } from './helpers/setup.js';
import { checkPurchaseResponse } from './helpers/checks.js';

const INITIAL_STOCK = 10;
const LOCK_MODE = 'PESSIMISTIC';

export const options = {
    stages: [
        { duration: '10s', target: 10  },
        { duration: '20s', target: 20  },
        { duration: '20s', target: 50  },
        { duration: '20s', target: 100 },
        { duration: '10s', target: 0   },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<2000', 'p(99)<3000'],
        'http_req_failed': ['rate<0.01'],
        'checks': ['rate>0.95'],
    },
};

export function setup() {
    const productId = createProduct('PESSIMISTIC Lock Test Item', 999000, INITIAL_STOCK);
    resetStock(productId, INITIAL_STOCK);
    console.log(`\n PESSIMISTIC mode test starting. Initial stock: ${INITIAL_STOCK}`);
    return { productId, initialStock: INITIAL_STOCK };
}

export default function (data) {
    const { productId } = data;
    const res = http.post(`${BASE}/api/v1/orders`,
        JSON.stringify({ productId, quantity: 1, lockMode: LOCK_MODE }),
        { headers: { 'Content-Type': 'application/json' }, tags: { lockMode: LOCK_MODE } }
    );
    checkPurchaseResponse(res, LOCK_MODE);
}

export function teardown(data) {
    const { productId, initialStock } = data;
    const finalStock = getCurrentStock(productId);
    const oversell = finalStock < 0;

    console.log('\n══════════════════════════════════════');
    console.log('  PESSIMISTIC MODE — RESULT SUMMARY');
    console.log('══════════════════════════════════════');
    console.log(`  Initial stock:  ${initialStock}`);
    console.log(`  Final DB stock: ${finalStock}`);
    console.log(oversell ? ` OVERSELL: ${finalStock}` : ` CORRECT: no oversell`);
    console.log('══════════════════════════════════════\n');
}
