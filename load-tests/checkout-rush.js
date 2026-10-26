// Flash-sale rush: BUYERS customers, each with 1 unit of the same product in their cart, all press
// "checkout" at the same moment while only STOCK units exist.
//
//   k6 run -e STRATEGY=PESSIMISTIC load-tests/checkout-rush.js
//
// Env: BASE_URL (default http://localhost:8080), STRATEGY, BUYERS (500), STOCK (100), STORE_ID (1),
//      PINCODE (560076), ADMIN_EMAIL, ADMIN_PASSWORD, OUT (summary JSON path).
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = __ENV.STRATEGY || 'PESSIMISTIC';
const BUYERS = parseInt(__ENV.BUYERS || '500', 10);
const STOCK = parseInt(__ENV.STOCK || '100', 10);
const STORE_ID = parseInt(__ENV.STORE_ID || '1', 10);
const PINCODE = __ENV.PINCODE || '560076';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@dashcart.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin12345';

const created = new Counter('checkout_created');
const rejected = new Counter('checkout_rejected');
const failed = new Counter('checkout_failed');
const checkoutTime = new Trend('checkout_time', true);
const finalAvailable = new Gauge('final_available');
const finalReserved = new Gauge('final_reserved');

export const options = {
    setupTimeout: '10m',
    scenarios: {
        rush: { executor: 'per-vu-iterations', vus: BUYERS, iterations: 1, maxDuration: '5m' },
    },
    summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const json = { 'Content-Type': 'application/json' };
const auth = (token) => ({ ...json, Authorization: `Bearer ${token}` });

function login(email, password) {
    const res = http.post(`${BASE}/auth/login`, JSON.stringify({ email, password }), { headers: json });
    check(res, { 'login ok': (r) => r.status === 200 });
    return res.json('accessToken');
}

// Runs requests in parallel batches so setup doesn't take forever.
function inBatches(requests, size) {
    const responses = [];
    for (let i = 0; i < requests.length; i += size) {
        responses.push(...http.batch(requests.slice(i, i + size)));
    }
    return responses;
}

export function setup() {
    const admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
    const adminHeaders = { headers: auth(admin) };
    http.put(`${BASE}/admin/checkout/strategy`, JSON.stringify({ strategy: STRATEGY }), adminHeaders);

    const runId = `${STRATEGY}-${Date.now()}`;
    const category = http.post(`${BASE}/admin/categories`, JSON.stringify({ name: `Flash sale ${runId}` }),
        adminHeaders).json('id');
    const product = http.post(`${BASE}/admin/products`, JSON.stringify({
        categoryId: category, name: `Flash sale phone ${runId}`, unit: '1 pc', mrp: 19999, price: 9999,
    }), adminHeaders).json('id');
    http.put(`${BASE}/admin/stores/${STORE_ID}/inventory`,
        JSON.stringify({ items: [{ productId: product, availableQty: STOCK }] }), adminHeaders);

    const emails = [];
    for (let i = 0; i < BUYERS; i++) {
        emails.push(`buyer-${runId}-${i}@load.test`);
    }
    inBatches(emails.map((email) => ['POST', `${BASE}/auth/register`,
        JSON.stringify({ name: 'Load Buyer', email, password: 'load-test-pass' }), { headers: json }]), 50);
    const tokens = inBatches(emails.map((email) => ['POST', `${BASE}/auth/login`,
        JSON.stringify({ email, password: 'load-test-pass' }), { headers: json }]), 50)
        .map((r) => r.json('accessToken'));
    inBatches(tokens.map((token) => ['PUT', `${BASE}/cart/items/${product}`,
        JSON.stringify({ storeId: STORE_ID, quantity: 1 }), { headers: auth(token) }]), 50);

    return { tokens, product, admin };
}

export default function (data) {
    const token = data.tokens[__VU - 1];
    const res = http.post(`${BASE}/orders/checkout`,
        JSON.stringify({ deliveryAddress: '1 Rush Lane, BTM Layout', deliveryPincode: PINCODE }),
        { headers: auth(token), tags: { name: 'checkout' } });
    checkoutTime.add(res.timings.duration);
    if (res.status === 201) {
        created.add(1);
    } else if (res.status === 409) {
        rejected.add(1);
    } else {
        failed.add(1);
    }
}

export function teardown(data) {
    const rows = http.get(`${BASE}/admin/stores/${STORE_ID}/inventory`, { headers: auth(data.admin) }).json();
    const row = rows.find((r) => r.productId === data.product);
    finalAvailable.add(row.availableQty);
    finalReserved.add(row.reservedQty);
}

export function handleSummary(data) {
    const m = data.metrics;
    const count = (name) => (m[name] ? m[name].values.count : 0);
    const t = m.checkout_time.values;
    const result = {
        strategy: STRATEGY,
        buyers: BUYERS,
        stock: STOCK,
        created: count('checkout_created'),
        rejected: count('checkout_rejected'),
        failed: count('checkout_failed'),
        oversold: Math.max(0, count('checkout_created') - STOCK),
        finalAvailable: m.final_available.values.value,
        finalReserved: m.final_reserved.values.value,
        latencyMs: { min: t.min, median: t.med, p90: t['p(90)'], p95: t['p(95)'], p99: t['p(99)'], max: t.max },
        // Everyone starts together, so the slowest response is when the last buyer was served.
        requestsPerSec: BUYERS / (t.max / 1000),
    };
    const text = `${STRATEGY}: created=${result.created} rejected=${result.rejected} failed=${result.failed} `
        + `oversold=${result.oversold} available=${result.finalAvailable} reserved=${result.finalReserved} `
        + `median=${t.med.toFixed(0)}ms p95=${t['p(95)'].toFixed(0)}ms max=${t.max.toFixed(0)}ms\n`;
    const out = { stdout: text };
    if (__ENV.OUT) {
        out[__ENV.OUT] = JSON.stringify(result, null, 2);
    }
    return out;
}
