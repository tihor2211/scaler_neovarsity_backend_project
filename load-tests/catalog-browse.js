// Customers browsing: VUS virtual users keep loading a store's product listing (random store,
// random category or "all", first page) for DURATION. Measures latency and requests per second.
//
//   k6 run -e LABEL=cache-on load-tests/catalog-browse.js
//
// Env: BASE_URL (default http://localhost:8080), VUS (100), DURATION (30s), LABEL, OUT (summary JSON path).
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const LABEL = __ENV.LABEL || 'run';
const VUS = parseInt(__ENV.VUS || '100', 10);

export const options = {
    scenarios: {
        browse: {
            executor: 'constant-vus',
            vus: VUS,
            duration: __ENV.DURATION || '30s',
        },
    },
    summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const STORES = [1, 2, 3];
const CATEGORIES = [null, 1, 2, 3, 4, 5, 6];
const pick = (list) => list[Math.floor(Math.random() * list.length)];

export default function () {
    const category = pick(CATEGORIES);
    const query = category === null ? '' : `?categoryId=${category}`;
    const res = http.get(`${BASE}/stores/${pick(STORES)}/products${query}`, { tags: { name: 'listing' } });
    check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    const m = data.metrics;
    const t = m.http_req_duration.values;
    const result = {
        label: LABEL,
        vus: VUS,
        requests: m.http_reqs.values.count,
        requestsPerSec: m.http_reqs.values.rate,
        failedRate: m.http_req_failed.values.rate,
        latencyMs: { min: t.min, median: t.med, p90: t['p(90)'], p95: t['p(95)'], p99: t['p(99)'], max: t.max },
    };
    const text = `${LABEL}: ${result.requests} requests, ${result.requestsPerSec.toFixed(0)} req/s, `
        + `median=${t.med.toFixed(1)}ms p95=${t['p(95)'].toFixed(1)}ms p99=${t['p(99)'].toFixed(1)}ms `
        + `failed=${(result.failedRate * 100).toFixed(2)}%\n`;
    const out = { stdout: text };
    if (__ENV.OUT) {
        out[__ENV.OUT] = JSON.stringify(result, null, 2);
    }
    return out;
}
