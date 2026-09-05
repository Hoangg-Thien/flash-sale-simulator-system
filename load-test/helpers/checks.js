import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Mark 201 (Created) and 409 (Conflict - Stock Exceeded/Lock timeout) as expected business statuses
http.setResponseCallback(http.expectedStatuses(201, 409));

// Custom metrics
export const purchaseSuccess = new Counter('purchase_success_total');
export const purchaseFailed  = new Counter('purchase_failed_total');
export const oversellRisk    = new Counter('oversell_risk_total');
export const purchaseRate    = new Rate('purchase_success_rate');
export const purchaseLatency = new Trend('purchase_latency_ms', true);

/**
 * Kiểm tra response của POST /api/v1/orders và record metrics.
 */
export function checkPurchaseResponse(res, lockMode) {
    const success = res.status === 201;
    const conflict = res.status === 409;
    const isError = !success && !conflict;

    // Record custom metrics
    if (success) {
        purchaseSuccess.add(1, { lockMode });
        purchaseRate.add(true, { lockMode });
    } else {
        purchaseFailed.add(1, { lockMode });
        purchaseRate.add(false, { lockMode });
    }

    purchaseLatency.add(res.timings.duration, { lockMode });

    // k6 built-in checks
    check(res, {
        'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
        'no 5xx errors':        (r) => r.status < 500,
        'response time < 5s':   (r) => r.timings.duration < 5000,
    });

    if (isError) {
        console.error(` Unexpected error: status=${res.status}, body=${res.body ? res.body.substring(0, 200) : ''}`);
    }

    return success;
}
