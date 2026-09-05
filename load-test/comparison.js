/**
 * COMPARISON TEST — chạy tuần tự 4 mode với cùng config, in bảng so sánh.
 *
 * Chạy: k6 run load-test/comparison.js --out json=load-test/results/comparison.json
 */
import http from 'k6/http';
import { sleep } from 'k6';
import { createProduct, resetStock, getCurrentStock, BASE } from './helpers/setup.js';
import { checkPurchaseResponse } from './helpers/checks.js';

const INITIAL_STOCK = 5;
const CONCURRENT_VUS = 20;
const DURATION_EACH = '15s';

function buildStages(peakVUs, duration) {
    return [
        { duration: '3s',      target: peakVUs },  // ramp up
        { duration: duration,  target: peakVUs },  // peak
        { duration: '3s',      target: 0        },  // ramp down
    ];
}

export const options = {
    scenarios: {
        none_mode: {
            executor: 'ramping-vus',
            startTime: '0s',
            stages: buildStages(CONCURRENT_VUS, DURATION_EACH),
            env: { LOCK_MODE: 'NONE' },
            tags: { mode: 'none' },
        },
        optimistic_mode: {
            executor: 'ramping-vus',
            startTime: '25s',
            stages: buildStages(CONCURRENT_VUS, DURATION_EACH),
            env: { LOCK_MODE: 'OPTIMISTIC' },
            tags: { mode: 'optimistic' },
        },
        pessimistic_mode: {
            executor: 'ramping-vus',
            startTime: '50s',
            stages: buildStages(CONCURRENT_VUS, DURATION_EACH),
            env: { LOCK_MODE: 'PESSIMISTIC' },
            tags: { mode: 'pessimistic' },
        },
        redis_mode: {
            executor: 'ramping-vus',
            startTime: '75s',
            stages: buildStages(CONCURRENT_VUS, DURATION_EACH),
            env: { LOCK_MODE: 'REDIS' },
            tags: { mode: 'redis' },
        },
    },
    thresholds: {
        'http_req_failed': ['rate<0.01'],
    },
};

export function setup() {
    const products = {};
    for (const mode of ['NONE', 'OPTIMISTIC', 'PESSIMISTIC', 'REDIS']) {
        const id = createProduct(`${mode} Comparison Item`, 999000, INITIAL_STOCK);
        resetStock(id, INITIAL_STOCK);
        products[mode] = id;
    }
    console.log(`\n Comparison test: ${INITIAL_STOCK} items, ${CONCURRENT_VUS} VU each mode\n`);
    return { products };
}

export default function (data) {
    const { products } = data;
    const lockMode = __ENV.LOCK_MODE || 'NONE';
    const productId = products[lockMode];

    if (!productId) return;

    const res = http.post(`${BASE}/api/v1/orders`,
        JSON.stringify({ productId, quantity: 1, lockMode }),
        { headers: { 'Content-Type': 'application/json' }, tags: { lockMode } }
    );

    checkPurchaseResponse(res, lockMode);
}

export function teardown(data) {
    const { products } = data;

    const results = {};
    for (const [mode, productId] of Object.entries(products)) {
        results[mode] = getCurrentStock(productId);
    }

    console.log('\n');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log('     FLASH SALE — K6 LOAD TEST COMPARISON RESULTS              ');
    console.log(`     Config: ${INITIAL_STOCK} items, ${CONCURRENT_VUS} concurrent users each`);
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`${'Mode'.padEnd(15)} ${'FinalStock'.padEnd(12)} ${'Oversell?'.padEnd(12)} Notes`);
    console.log('─────────────────────────────────────────────────────────────');

    for (const [mode, finalStock] of Object.entries(results)) {
        const oversell = finalStock < 0 ? ' YES' : ' NO';
        const notes = mode === 'NONE'
            ? `(oversell expected — bug demo)`
            : `(lock prevents oversell)`;
        console.log(`${mode.padEnd(15)} ${String(finalStock).padEnd(12)} ${oversell.padEnd(12)} ${notes}`);
    }

    console.log('═══════════════════════════════════════════════════════════════\n');
}
