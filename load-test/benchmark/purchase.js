import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const outcomes = new Counter('purchase_outcomes');
const wall = new Trend('purchase_wall_ms', true);
const started = new Trend('first_request_epoch_ms');
const finished = new Trend('response_epoch_ms');
const urls = __ENV.URLS.split(',');
export const options = {
  scenarios: { purchases: { executor: 'constant-vus', vus: Number(__ENV.VUS),
    duration: __ENV.DURATION, gracefulStop: '45s' } },
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};

export default function () {
  const seq = exec.scenario.iterationInTest;
  const before = Date.now();
  const response = http.post(urls[seq % urls.length] + '/api/v1/orders', JSON.stringify({
    productId: 1 + (seq % 32), quantity: 1, lockMode: __ENV.MODE,
  }), { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': __ENV.KEY + '-' + seq },
    tags: { name: 'POST orders' }, timeout: '35s' });
  const end = Date.now();
  const outcome = ({201:'confirmed',202:'pending',409:'rejected'})[response.status] || 'unexpected';
  let orderId = '';
  if (response.status === 200 || response.status === 201 || response.status === 202) {
    try { orderId = String(response.json('id')); } catch (_) { orderId = 'invalid'; }
  }
  const tags = { outcome, code: String(response.status), sequence: String(seq), order_id: orderId };
  if (response.status === 202) tags.accepted_ms = String(end);
  outcomes.add(1, tags);
  wall.add(end - before);
  started.add(before);
  finished.add(end);
}

export function handleSummary(data) {
  return {'/out/summary.json': JSON.stringify(data.metrics)};
}
