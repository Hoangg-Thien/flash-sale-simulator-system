/** LOAD TEST — mode=ATOMIC (conditional SQL UPDATE). */
import http from 'k6/http';
import { check } from 'k6';
import { createProduct, resetStock, getCurrentStock, BASE } from './helpers/setup.js';
import { checkPurchaseResponse } from './helpers/checks.js';

const INITIAL_STOCK = 10;
const LOCK_MODE = 'ATOMIC';

export const options = {
    stages: [
        { duration: '10s', target: 10 },
        { duration: '20s', target: 20 },
        { duration: '20s', target: 50 },
        { duration: '20s', target: 100 },
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<2000', 'p(99)<3000'],
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.95'],
    },
};

export function setup() {
    const productId = createProduct('ATOMIC Lock Test Item', 999000, INITIAL_STOCK);
    if (!resetStock(productId, INITIAL_STOCK)) {
        throw new Error('Failed to reset ATOMIC inventory');
    }
    return { productId };
}

export default function (data) {
    const res = http.post(`${BASE}/api/v1/orders`,
        JSON.stringify({ productId: data.productId, quantity: 1, lockMode: LOCK_MODE }),
        { headers: { 'Content-Type': 'application/json' }, tags: { lockMode: LOCK_MODE } }
    );
    checkPurchaseResponse(res, LOCK_MODE);
}

export function teardown(data) {
    const finalStock = getCurrentStock(data.productId);
    check(finalStock, { 'ATOMIC stock exhausted': (stock) => stock === 0 });
    if (finalStock !== 0) {
        throw new Error(`ATOMIC final stock must be 0, got ${finalStock}`);
    }
    console.log(`ATOMIC initial stock=${INITIAL_STOCK}, final stock=${finalStock}`);
}
