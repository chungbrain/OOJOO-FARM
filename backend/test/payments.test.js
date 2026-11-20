// Toss Payments (PG) 통합 테스트 — 모드 전환 / confirm / 웹훅 / 멱등성.
// 실제 네트워크 없음: Toss confirm API 는 fetch 스텁, 웹훅 서명 은 node:crypto 로 계산.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-payments-${process.pid}.db`);
process.env.DB_PATH = tmpDb;

const WEBHOOK_SECRET = 'tss-test-webhook-secret';
process.env.TOSS_SECRET_KEY = 'sk-test-secret-key';
process.env.TOSS_WEBHOOK_SECRET = WEBHOOK_SECRET;

let server;
let base;
let app;
let pg;

// Toss API 스텁 — 엔드포인트별 응답 큐 (create / query / confirm)
let confirmResponses = [];
let createResponses = [];
let queryResponses = [];
let tossCalls = [];
let tossShouldThrow = false;

before(async () => {
  ({ default: app } = await import('../src/app.js'));
  pg = await import('../src/lib/pg.js');
  pg._test.setFetch(async (url, opts) => {
    const u = String(url);
    tossCalls.push({ url: u, opts });
    if (tossShouldThrow) throw new Error('toss network down');
    let next;
    if (u.includes('/v1/payments/requests')) {
      next = createResponses.shift() || { approvalUrl: 'https://pay.toss.test/approve', paymentKey: 'pk-stub-create', status: 'READY' };
    } else if (u.includes('/gateway/payments/')) {
      const q = opts?.method === 'GET' ? queryResponses : confirmResponses;
      next = q.shift() || { status: 'PAID' };
    } else {
      next = { status: 'PAID' };
    }
    return { ok: true, status: 200, json: async () => next };
  });
  await new Promise((resolve) => { server = app.listen(0, resolve); });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  try { server?.close(); } catch {}
  delete process.env.PG_MODE;
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) { try { fs.rmSync(f); } catch {} }
});

const get = (p) => fetch(base + p);
const post = (p, b, headers = {}) =>
  fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(b) });

async function makeTossOrder(n = 1) {
  process.env.PG_MODE = 'toss';
  const u = await (await post('/api/users', { region: 'Seoul' })).json();
  const order = await (await post('/api/market/orders', {
    userId: u.id,
    items: [{ productId: 'prd_seed_01', qty: n }],
  })).json();
  return { user: u, order };
}

async function stockOf(productId) {
  return (await (await get(`/api/market/products/${productId}`)).json()).stock;
}

// Toss 웹훅 헤더 — 서명 = tss_v1:hmac_sha256(secret, Toss-Timestamp + rawBody)
function webhookHeaders(rawBody, { secret = WEBHOOK_SECRET, tamper = false } = {}) {
  const ts = String(Date.now());
  const sig = pg.computeTossWebhookSignature(secret, ts, tamper ? rawBody + 'x' : rawBody);
  return { 'Content-Type': 'application/json', 'Toss-Timestamp': ts, 'Toss-Signature': sig };
}

async function sendWebhook(raw) {
  return fetch(base + '/api/payments/toss/webhook', { method: 'POST', headers: webhookHeaders(raw), body: raw });
}

test('toss checkout: pending_payment + 재고 미차감', async () => {
  const before = await stockOf('prd_seed_01');
  const { order } = await makeTossOrder(2);
  assert.equal(order.status, 'pending_payment');
  assert.equal(order.total, 3900 * 2);
  const after = await stockOf('prd_seed_01');
  assert.equal(after, before, '결제 확인 전에는 재고가 차감되어서는 안 된다');
});

test('toss confirm: PAID + 금액 일치 → paid + 재고 차감 (1회만)', async () => {
  const { user, order } = await makeTossOrder(1);
  const before = await stockOf('prd_seed_01');
  confirmResponses = [{ status: 'PAID', amount: order.total, approvedAt: '2026-11-20T00:00:00Z' }];
  tossCalls = [];
  const r1 = await post('/api/payments/toss/confirm', { userId: user.id, orderId: order.orderId, paymentKey: 'pk-001' });
  assert.equal(r1.status, 200);
  assert.deepEqual(await r1.json(), { ok: true, changed: true, status: 'paid' });

  // 실제 API 호출 형태: Basic auth + paymentKey 경로 + 금액 검증 필드
  assert.equal(tossCalls.length, 1);
  assert.ok(tossCalls[0].url.endsWith('/gateway/payments/pk-001'));
  assert.ok(tossCalls[0].opts.headers.Authorization.startsWith('Basic '));
  assert.equal(JSON.parse(tossCalls[0].opts.body).amount, order.total);
  assert.equal(await stockOf('prd_seed_01'), before - 1);

  // 재확인 (멱등): 상태 변화 없음, 재고 재차감 없음
  confirmResponses = [];
  const r2 = await post('/api/payments/toss/confirm', { userId: user.id, orderId: order.orderId, paymentKey: 'pk-001' });
  const j2 = await r2.json();
  assert.equal(j2.changed, false);
  assert.equal(j2.reason, 'already-paid');
  assert.equal(await stockOf('prd_seed_01'), before - 1);
});

test('toss confirm: 금액 불일치 → pending 유지', async () => {
  const { user, order } = await makeTossOrder(1);
  const before = await stockOf('prd_seed_01');
  confirmResponses = [{ status: 'PAID', amount: order.total + 1 }];
  const r = await post('/api/payments/toss/confirm', { userId: user.id, orderId: order.orderId, paymentKey: 'pk-002' });
  assert.equal(r.status, 400);
  const hist = await (await get(`/api/market/orders/${user.id}`)).json();
  assert.equal(hist.orders[0].status, 'pending_payment');
  assert.equal(await stockOf('prd_seed_01'), before);
});

test('toss confirm: Toss API 실패 → 502, 주문 pending 유지', async () => {
  const { user, order } = await makeTossOrder(1);
  const before = await stockOf('prd_seed_01');
  tossShouldThrow = true;
  try {
    const r = await post('/api/payments/toss/confirm', { userId: user.id, orderId: order.orderId, paymentKey: 'pk-003' });
    assert.equal(r.status, 502);
  } finally { tossShouldThrow = false; }
  const hist = await (await get(`/api/market/orders/${user.id}`)).json();
  assert.equal(hist.orders[0].status, 'pending_payment');
  assert.equal(await stockOf('prd_seed_01'), before);
});

test('toss confirm: 시크릿 키 미설정 → 502', async () => {
  const saved = process.env.TOSS_SECRET_KEY;
  delete process.env.TOSS_SECRET_KEY;
  try {
    const { user, order } = await makeTossOrder(1);
    const r = await post('/api/payments/toss/confirm', { userId: user.id, orderId: order.orderId, paymentKey: 'pk-004' });
    assert.equal(r.status, 502);
  } finally { process.env.TOSS_SECRET_KEY = saved; }
});

test('toss webhook: 유효 서명 → paid + 재고 차감', async () => {
  const { user, order } = await makeTossOrder(3);
  const before = await stockOf('prd_seed_01');
  const raw = JSON.stringify([{
    type: 'payment_paid',
    data: { paymentKey: 'pk-wh-1', orderId: order.orderId, amount: order.total, status: 'PAID' },
  }]);
  const r = await sendWebhook(raw);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true, paid: 1, canceled: 0 });
  const hist = await (await get(`/api/market/orders/${user.id}`)).json();
  assert.equal(hist.orders[0].status, 'paid');
  assert.equal(await stockOf('prd_seed_01'), before - 3);
});

test('toss webhook: 변조 바디 → 401, 변화 없음', async () => {
  const { user, order } = await makeTossOrder(1);
  const before = await stockOf('prd_seed_01');
  const raw = JSON.stringify([{
    type: 'payment_paid',
    data: { paymentKey: 'pk-wh-2', orderId: order.orderId, amount: order.total },
  }]);
  const r = await fetch(base + '/api/payments/toss/webhook', { method: 'POST', headers: webhookHeaders(raw, { tamper: true }), body: raw });
  assert.equal(r.status, 401);
  const hist = await (await get(`/api/market/orders/${user.id}`)).json();
  assert.equal(hist.orders[0].status, 'pending_payment');
  assert.equal(await stockOf('prd_seed_01'), before);
});

test('toss webhook: 시크릿 미설정 → 400', async () => {
  const saved = process.env.TOSS_WEBHOOK_SECRET;
  delete process.env.TOSS_WEBHOOK_SECRET;
  try {
    const r = await sendWebhook('[]');
    assert.equal(r.status, 400);
  } finally { process.env.TOSS_WEBHOOK_SECRET = saved; }
});

test('confirm + webhook 중복 발화: 재고 1회만 차감 (멱등)', async () => {
  const { user, order } = await makeTossOrder(2);
  const before = await stockOf('prd_seed_01');
  confirmResponses = [{ status: 'PAID', amount: order.total }];
  const r1 = await post('/api/payments/toss/confirm', { userId: user.id, orderId: order.orderId, paymentKey: 'pk-dup-1' });
  assert.equal((await r1.json()).changed, true);

  // 같은 주문의 webhook 이 뒤늦게 와도 — paid 0, 재고 변화 없음
  const raw = JSON.stringify([{
    type: 'payment_paid',
    data: { paymentKey: 'pk-dup-1', orderId: order.orderId, amount: order.total },
  }]);
  const r2 = await sendWebhook(raw);
  assert.equal(r2.status, 200);
  assert.deepEqual(await r2.json(), { ok: true, paid: 0, canceled: 0 });
  assert.equal(await stockOf('prd_seed_01'), before - 2);
});

test('toss start: pending 주문 → approvalUrl (Toss 생성 호출 형태 + paymentKey 저장)', async () => {
  const { user, order } = await makeTossOrder(1);
  createResponses = [{ approvalUrl: 'https://pay.toss.test/approve-x', paymentKey: 'pk-start-1', status: 'READY' }];
  tossCalls = [];
  const r = await post('/api/payments/toss/start', { userId: user.id, orderId: order.orderId });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { approvalUrl: 'https://pay.toss.test/approve-x' });
  // Toss 호출 형태: Basic auth + 생성 경로 + 금액/통화 검증 필드
  assert.ok(tossCalls.length === 1 && tossCalls[0].url.endsWith('/v1/payments/requests'));
  assert.ok(tossCalls[0].opts.headers.Authorization.startsWith('Basic '));
  const body = JSON.parse(tossCalls[0].opts.body);
  assert.equal(body.orderId, order.orderId);
  assert.equal(body.amount, order.total);
  assert.equal(body.currency, 'KRW');
  // paymentKey 저장 (audit + status 폴링용)
  const hist = await (await get(`/api/market/orders/${user.id}`)).json();
  assert.equal(hist.orders[0].pg_payment_key, 'pk-start-1');
});

test('toss start: 이미 paid 주문 → 400', async () => {
  const { user, order } = await makeTossOrder(1);
  confirmResponses = [{ status: 'PAID', amount: order.total }];
  await post('/api/payments/toss/confirm', { userId: user.id, orderId: order.orderId, paymentKey: 'pk-paid-1' });
  const r = await post('/api/payments/toss/start', { userId: user.id, orderId: order.orderId });
  assert.equal(r.status, 400);
});

test('toss status: Toss PAID → paid + 재고 차감', async () => {
  const { user, order } = await makeTossOrder(2);
  createResponses = [{ approvalUrl: 'https://pay.toss.test/a', paymentKey: 'pk-status-1', status: 'READY' }];
  await post('/api/payments/toss/start', { userId: user.id, orderId: order.orderId });
  const before = await stockOf('prd_seed_01');
  queryResponses = [{ status: 'PAID', amount: order.total }];
  const r = await post('/api/payments/toss/status', { userId: user.id, orderId: order.orderId });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { status: 'paid' });
  assert.equal(await stockOf('prd_seed_01'), before - 2);
});

test('toss status: Toss CANCELED → canceled, 재고 미차감', async () => {
  const { user, order } = await makeTossOrder(1);
  createResponses = [{ approvalUrl: 'https://pay.toss.test/b', paymentKey: 'pk-status-2', status: 'READY' }];
  await post('/api/payments/toss/start', { userId: user.id, orderId: order.orderId });
  const before = await stockOf('prd_seed_01');
  queryResponses = [{ status: 'CANCELED' }];
  const r = await post('/api/payments/toss/status', { userId: user.id, orderId: order.orderId });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { status: 'canceled' });
  assert.equal(await stockOf('prd_seed_01'), before);
});

test('toss status: Toss READY → pending 유지', async () => {
  const { user, order } = await makeTossOrder(1);
  createResponses = [{ approvalUrl: 'https://pay.toss.test/c', paymentKey: 'pk-status-3', status: 'READY' }];
  await post('/api/payments/toss/start', { userId: user.id, orderId: order.orderId });
  queryResponses = [{ status: 'READY' }];
  const r = await post('/api/payments/toss/status', { userId: user.id, orderId: order.orderId });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { status: 'pending_payment' });
});

test('toss webhook: payment_canceled → canceled, 재고 미차감', async () => {
  const { user, order } = await makeTossOrder(1);
  const before = await stockOf('prd_seed_01');
  const raw = JSON.stringify([{
    type: 'payment_canceled',
    data: { paymentKey: 'pk-wh-c', orderId: order.orderId, amount: order.total },
  }]);
  const r = await sendWebhook(raw);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true, paid: 0, canceled: 1 });
  const hist = await (await get(`/api/market/orders/${user.id}`)).json();
  assert.equal(hist.orders[0].status, 'canceled');
  assert.equal(await stockOf('prd_seed_01'), before);
});

test('demo 모드 (PG_MODE 미설정): 즉시 paid — 회귀 방지', async () => {
  delete process.env.PG_MODE;
  const u = await (await post('/api/users', { region: 'Seoul' })).json();
  const before = await stockOf('prd_ctrl_01');
  const order = await (await post('/api/market/orders', {
    userId: u.id,
    items: [{ productId: 'prd_ctrl_01', qty: 1 }],
  })).json();
  assert.equal(order.status, 'paid');
  assert.equal(await stockOf('prd_ctrl_01'), before - 1);
});
