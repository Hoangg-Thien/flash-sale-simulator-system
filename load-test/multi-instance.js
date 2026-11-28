/** Concurrent purchases split across independent application instances. */
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const urls = (__ENV.BASE_URLS || 'http://localhost:8080,http://localhost:8081')
    .split(',').map((url) => url.trim().replace(/\/$/, ''));
const mode = __ENV.LOCK_MODE || 'ATOMIC';
const users = Number(__ENV.CONCURRENT_USERS || 20);
const stock = Number(__ENV.INITIAL_STOCK || 10);
const quantity = Number(__ENV.QUANTITY || 1);
const runId = __ENV.RUN_ID || `local-${Date.now()}`;
if (urls.length !== 2 || new Set(urls).size !== 2 || urls.some((url) => !/^https?:\/\//.test(url))) {
    throw new Error('BASE_URLS must contain two distinct HTTP(S) URLs');
}
if (!['ATOMIC', 'OPTIMISTIC', 'PESSIMISTIC', 'REDIS', 'NONE'].includes(mode)
    || (mode === 'NONE' && __ENV.RUN_UNSAFE !== '1')) {
    throw new Error('Unsupported mode; NONE requires explicit RUN_UNSAFE=1');
}
if (!Number.isInteger(users) || users < 2 || users > 200 || !Number.isInteger(stock) || stock < 0
    || !Number.isInteger(quantity) || quantity < 1 || quantity > 100 || !/^[A-Za-z0-9-]{1,40}$/.test(runId)) {
    throw new Error('Invalid users/stock/quantity/run ID');
}

http.setResponseCallback(http.expectedStatuses(200, 201, 409));
const attempts = new Counter('purchase_attempts');
const confirmed = new Counter('purchase_confirmed');
const rejected = new Counter('purchase_rejected');

export const options = {
    scenarios: { purchases: { executor: 'per-vu-iterations', vus: users, iterations: 1, maxDuration: '30s' } },
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        purchase_attempts: [`count==${users}`],
        'purchase_attempts{node:0}': ['count>0'],
        'purchase_attempts{node:1}': ['count>0'],
    },
};

export function setup() {
    const nodes = urls.map((url) => {
        const response = http.get(`${url}/actuator/health`);
        if (response.status !== 200 || response.json('status') !== 'UP' || !response.headers['X-Instance-Id']) {
            throw new Error(`Instance is not healthy/identified: ${url}`);
        }
        return { url, instanceId: response.headers['X-Instance-Id'] };
    });
    if (nodes[0].instanceId === nodes[1].instanceId) {
        throw new Error('BASE_URLS do not identify two different instances');
    }
    const response = http.post(`${urls[0]}/api/v1/products`,
        JSON.stringify({ name: `multi-${mode}-${runId}`, price: 100, initialStock: stock }),
        { headers: { 'Content-Type': 'application/json' } });
    if (response.status !== 201) {
        throw new Error(`Product creation failed: ${response.status} ${response.body}`);
    }
    return { productId: response.json('id'), initialStock: stock, quantity, mode, runId, nodes, users };
}

export default function (data) {
    const node = (exec.vu.idInTest - 1) % data.nodes.length;
    const requestId = `mi-${data.runId}-${node}-${exec.scenario.iterationInTest}`;
    const response = http.post(`${data.nodes[node].url}/api/v1/orders`,
        JSON.stringify({ productId: data.productId, quantity: data.quantity, lockMode: data.mode }), {
            headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId, 'Idempotency-Key': requestId },
            tags: { node: String(node), lockMode: data.mode },
        });
    attempts.add(1, { node: String(node) });
    confirmed.add(response.status === 201 ? 1 : 0);
    rejected.add(response.status === 409 ? 1 : 0);
    check(response, {
        'purchase status 201 or 409': (r) => r.status === 201 || r.status === 409,
        'correct instance received purchase': (r) => r.headers['X-Instance-Id'] === data.nodes[node].instanceId,
        'request ID propagated': (r) => r.headers['X-Request-Id'] === requestId,
        'error body contains request ID': (r) => r.status !== 409 || r.json('requestId') === requestId,
    });
}

export function teardown(data) {
    const stocks = data.nodes.map((node) => {
        const response = http.get(`${node.url}/api/v1/products/${data.productId}`);
        if (response.status !== 200) {
            throw new Error(`Final stock read failed: ${node.url}`);
        }
        return response.json('stock');
    });
    const valid = check(stocks, {
        'both instances read the same final stock': (values) => values[0] === values[1],
        'final stock is nonnegative': (values) => values.every((value) => Number.isInteger(value) && value >= 0),
        'safe stock does not exceed initial stock': (values) => data.mode === 'NONE' || values[0] <= data.initialStock,
    });
    if (!valid) {
        throw new Error(`Invalid final stocks: ${JSON.stringify(stocks)}`);
    }
    console.log(JSON.stringify({ productId: data.productId, mode: data.mode, finalStock: stocks[0], nodes: data.nodes }));
    // Persisted order/unit conservation is independently checked by run-multi-instance.ps1.
}
