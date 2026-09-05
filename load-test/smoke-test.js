/**
 * SMOKE TEST — verify API còn sống trước khi load test.
 * Chạy: k6 run load-test/smoke-test.js
 * Thời gian: ~15 giây
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { createProduct, resetStock, BASE } from './helpers/setup.js';

http.setResponseCallback(http.expectedStatuses(201, 409));

export const options = {
    vus: 1,
    iterations: 5,
    thresholds: {
        http_req_failed: ['rate<0.01'],        // < 1% fail
        http_req_duration: ['p(95)<2000'],     // 95th percentile < 2s
    },
};

export function setup() {
    const productId = createProduct('Smoke Test Item', 100000, 100);
    resetStock(productId, 100);
    return { productId };
}

export default function (data) {
    const { productId } = data;

    const payload = JSON.stringify({
        productId,
        quantity: 1,
        lockMode: 'PESSIMISTIC',
    });

    const res = http.post(`${BASE}/api/v1/orders`, payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    check(res, {
        'smoke: status 201 or 409': (r) => r.status === 201 || r.status === 409,
        'smoke: no 500':            (r) => r.status < 500,
        'smoke: response body ok':  (r) => r.body && r.body.length > 0,
    });

    sleep(1);
}

export function teardown(data) {
    console.log(' Smoke test completed. App is alive.');
}
