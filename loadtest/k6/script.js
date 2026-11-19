// Same workload for both architectures. Usage (from the repository root):
//   docker compose run --rm -e ARCH=monolith      k6
//   docker compose run --rm -e ARCH=microservices k6
// Optional: -e TEST_PROFILE=smoke (30 s, 10 VUs) | load (default, ~6.5 min up to 100 VUs)
import http from 'k6/http';
import { check, sleep } from 'k6';

const ARCH = __ENV.ARCH || 'microservices';
const TEST_PROFILE = __ENV.TEST_PROFILE || 'load';

const TARGETS = {
    monolith: {
        user: 'http://ecommerce-mono:8080',
        product: 'http://ecommerce-mono:8080',
        order: 'http://ecommerce-mono:8080',
    },
    microservices: {
        user: 'http://user-service:8081',
        product: 'http://product-service:8082',
        order: 'http://order-service:8083',
    },
};
if (!TARGETS[ARCH]) {
    throw new Error(`ARCH must be "monolith" or "microservices", got "${ARCH}"`);
}
const URL = TARGETS[ARCH];

const PROFILES = {
    smoke: [{ duration: '30s', target: 10 }],
    load: [
        { duration: '30s', target: 20 },   // warm-up (JIT, connection pools)
        { duration: '1m', target: 20 },
        { duration: '30s', target: 50 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 100 },
        { duration: '1m30s', target: 100 },
        { duration: '30s', target: 0 },
    ],
};
const MAX_VUS = Math.max(...PROFILES[TEST_PROFILE].map((s) => s.target));
const SEED_USERS = Math.max(MAX_VUS, 10);   // one user per VU: no concurrent writes on the same cart
const SEED_PRODUCTS = 50;
const THINK_TIME = Number(__ENV.THINK_TIME || 0.5);

const ENDPOINTS = [
    'GET /products', 'GET /products/{id}', 'GET /products/search',
    'GET /users/{id}', 'POST /carts', 'GET /carts', 'POST /orders',
];

export const options = {
    scenarios: {
        shopping: { executor: 'ramping-vus', startVUs: 0, stages: PROFILES[TEST_PROFILE], gracefulRampDown: '10s' },
    },
    tags: { testid: ARCH },
    setupTimeout: '2m',
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    // one (always passing) threshold per endpoint so that the summary contains per-endpoint latencies
    thresholds: Object.fromEntries([
        ['http_req_failed', ['rate<1']],
        ...ENDPOINTS.map((e) => [`http_req_duration{name:${e}}`, ['max>=0']]),
    ]),
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function ids(res) {
    return JSON.parse(res.body).map((x) => Number(x.id));
}

export function setup() {
    const runId = Date.now();
    for (let i = 0; i < SEED_USERS; i++) {
        http.post(`${URL.user}/api/v1/users`, JSON.stringify({
            firstName: `load${i}`, lastName: `run${runId}`, email: `load${i}.${runId}@test.local`, phone: '000',
            address: { street: 's', city: 'c', zipCode: 'z', state: 'st', country: 'tn' },
        }), { headers: JSON_HEADERS, tags: { name: 'setup' } });
    }
    for (let i = 0; i < SEED_PRODUCTS; i++) {
        http.post(`${URL.product}/api/v1/products`, JSON.stringify({
            name: `product ${runId}-${i}`, description: 'load test product', price: (10 + i) + 0.99,
            stockQuantity: 1000000000, category: 'loadtest', imageUrl: 'http://img/x.png',
        }), { headers: JSON_HEADERS, tags: { name: 'setup' } });
    }
    // only use the rows created by this run
    const users = ids(http.get(`${URL.user}/api/v1/users`, { tags: { name: 'setup' } })).slice(-SEED_USERS);
    const products = ids(http.get(`${URL.product}/api/v1/products`, { tags: { name: 'setup' } })).slice(-SEED_PRODUCTS);
    if (users.length < SEED_USERS || products.length < SEED_PRODUCTS) {
        throw new Error(`seeding failed: ${users.length} users, ${products.length} products`);
    }
    console.log(`[${ARCH}] seeded ${users.length} users and ${products.length} products`);
    return { users, products };
}

function pick(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

export default function (data) {
    const userId = data.users[(__VU - 1) % data.users.length];
    const userHeaders = { 'X-User-ID': String(userId) };
    const productId = pick(data.products);

    check(http.get(`${URL.product}/api/v1/products`, { tags: { name: 'GET /products' } }),
        { 'list products 200': (r) => r.status === 200 });
    check(http.get(`${URL.product}/api/v1/products/${productId}`, { tags: { name: 'GET /products/{id}' } }),
        { 'get product 200': (r) => r.status === 200 });
    check(http.get(`${URL.product}/api/v1/products/search?keyword=product`, { tags: { name: 'GET /products/search' } }),
        { 'search 200': (r) => r.status === 200 });
    check(http.get(`${URL.user}/api/v1/users/${userId}`, { tags: { name: 'GET /users/{id}' } }),
        { 'get user 200': (r) => r.status === 200 });

    for (const pid of [productId, pick(data.products)]) {
        check(http.post(`${URL.order}/api/v1/carts`, JSON.stringify({ productId: pid, quantity: 1 }),
            { headers: { ...JSON_HEADERS, ...userHeaders }, tags: { name: 'POST /carts' } }),
            { 'add to cart 201': (r) => r.status === 201 });
    }
    check(http.get(`${URL.order}/api/v1/carts`, { headers: userHeaders, tags: { name: 'GET /carts' } }),
        { 'get cart 200': (r) => r.status === 200 });
    check(http.post(`${URL.order}/api/v1/orders`, null, { headers: userHeaders, tags: { name: 'POST /orders' } }),
        { 'create order 201': (r) => r.status === 201 });

    sleep(THINK_TIME);
}

// ---------------------------------------------------------------- summary
function metricValues(data, name) {
    const m = data.metrics[name];
    return m ? m.values : null;
}

function fmt(v) {
    return v === undefined ? '-' : v.toFixed(1).padStart(8);
}

export function handleSummary(data) {
    const lines = [];
    const dur = metricValues(data, 'http_req_duration{expected_response:true}') || metricValues(data, 'http_req_duration');
    const reqs = metricValues(data, 'http_reqs');
    const failed = metricValues(data, 'http_req_failed');
    const iters = metricValues(data, 'iterations');
    lines.push(`\n===== ${ARCH} (${TEST_PROFILE}) =====`);
    lines.push(`requests: ${reqs.count}  (${reqs.rate.toFixed(1)} req/s)   iterations: ${iters.count} (${iters.rate.toFixed(2)}/s)`);
    lines.push(`failed:   ${(failed.rate * 100).toFixed(2)} %`);
    lines.push(`latency ms   avg ${fmt(dur.avg)}  med ${fmt(dur.med)}  p95 ${fmt(dur['p(95)'])}  p99 ${fmt(dur['p(99)'])}  max ${fmt(dur.max)}`);
    lines.push('\nper endpoint (ms)            avg      med      p95      p99      max');
    for (const e of ENDPOINTS) {
        const v = metricValues(data, `http_req_duration{name:${e}}`);
        if (v) lines.push(`${e.padEnd(24)} ${fmt(v.avg)} ${fmt(v.med)} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v.max)}`);
    }
    lines.push('');
    return {
        stdout: lines.join('\n'),
        [`/results/${ARCH}-${TEST_PROFILE}-summary.json`]: JSON.stringify(data, null, 2),
        [`/results/${ARCH}-${TEST_PROFILE}-summary.txt`]: lines.join('\n'),
    };
}
