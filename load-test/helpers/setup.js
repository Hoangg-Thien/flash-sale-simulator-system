import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/**
 * Tạo product mới và trả về productId.
 * Gọi trong setup() của mỗi test script.
 */
export function createProduct(name = 'Flash Sale Item', price = 999000, initialStock = 10) {
    const payload = JSON.stringify({ name, price, initialStock });
    const params = { headers: { 'Content-Type': 'application/json' } };

    const res = http.post(`${BASE_URL}/api/v1/products`, payload, params);

    check(res, {
        'product created (201)': (r) => r.status === 201,
    });

    if (res.status !== 201) {
        throw new Error(`Failed to create product: ${res.status} ${res.body}`);
    }

    const body = JSON.parse(res.body);
    console.log(`Product created: id=${body.id}, name=${body.name}, stock=${body.stock}`);
    return body.id;
}

/**
 * Reset stock của product về giá trị ban đầu.
 * Gọi giữa các stage để đảm bảo stock đủ cho mỗi stage.
 */
export function resetStock(productId, stock) {
    const payload = JSON.stringify({ newStock: stock });
    const params = { headers: { 'Content-Type': 'application/json' } };

    const res = http.post(
        `${BASE_URL}/api/v1/products/${productId}/inventory/reset`,
        payload,
        params
    );

    check(res, {
        'stock reset (200)': (r) => r.status === 200,
    });

    console.log(`🔄 Stock reset: productId=${productId}, stock=${stock}`);
    return res.status === 200;
}

/**
 * Lấy stock hiện tại của product.
 * Dùng để verify sau load test.
 */
export function getCurrentStock(productId) {
    const res = http.get(`${BASE_URL}/api/v1/products/${productId}`);
    if (res.status === 200) {
        const body = JSON.parse(res.body);
        return body.stock ?? -999;
    }
    return -999;
}

export const BASE = BASE_URL;
