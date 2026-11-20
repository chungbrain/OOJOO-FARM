// PG(결제 게이트웨이) 추상 — demo / Toss Payments.
//
// 설정 (env, 호출 시점에 읽으므로 테스트에서 토글 가능):
//   PG_MODE            — 'toss' 면 Toss 플로우, 그 외(기본)는 demo (즉시 'paid')
//   TOSS_SECRET_KEY    — 서버사이드 결제 확인(confirm)용 시크릿 키
//   TOSS_WEBHOOK_SECRET — 웹훅 서명 검증용 시크릿 (포털에서 생성한 tss_v1 키)
//
// Toss 플로우 (docs 미달성 확인분은 주석으로 명시 — 전액 주입 가능하여 테스트 가능):
//   1. 마켓 체크아웃 → 주문이 'pending_payment'로 생성 (재고 차감 없음)
//   2. 클라이언트가 Toss PG SDK(publishable key)로 결제 → paymentKey 확보
//   3. 서버사이드 확인: tossConfirm() — POST /gateway/payments/{paymentKey}
//      (Authorization: Basic base64(SECRET_KEY:) — Toss 표준 서버 API)
//   또는 Toss 포털 웹훅: tossWebhookHandler — 서명 tss_v1:hmac_sha256(secret, Toss-Timestamp + rawBody)
//   4. markOrderPaid() — 멱등: pending_payment → paid, 재고 차감도 여기서만.
import crypto from 'node:crypto';
import db from '../db.js';

// ---- 모드 / 설정 ----

export function pgMode() {
  return process.env.PG_MODE === 'toss' ? 'toss' : 'demo';
}

export function tossSecretKey() {
  return process.env.TOSS_SECRET_KEY || '';
}

export function tossWebhookSecret() {
  return process.env.TOSS_WEBHOOK_SECRET || '';
}

let fetchImpl = globalThis.fetch;
let apiBase = 'https://api.tosspayments.com';
export const _test = {
  setFetch(fn) { fetchImpl = fn; },
  setApiBase(url) { apiBase = url; },
};

// ---- 웹훅 서명 (Toss 표준: tss_v1:hmac_sha256(secret, Toss-Timestamp + rawBody)) ----

export function computeTossWebhookSignature(secret, timestamp, rawBody) {
  const raw = Buffer.isBuffer(rawBody) ? rawBody.toString('utf8') : String(rawBody);
  const mac = crypto.createHmac('sha256', secret).update(String(timestamp) + raw, 'utf8').digest('hex');
  return `tss_v1:${mac}`;
}

export function verifyTossWebhook(headers, rawBody) {
  const secret = tossWebhookSecret();
  if (!secret) return false;
  const signature = headers['toss-signature'];
  const timestamp = headers['toss-timestamp'];
  if (!signature || !timestamp) return false;
  const expected = computeTossWebhookSignature(secret, timestamp, rawBody);
  const a = Buffer.from(String(signature), 'utf8');
  const b = Buffer.from(expected, 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

// ---- 서버사이드 결제 확인 ----
// Toss 표준 API: POST {apiBase}/gateway/payments/{paymentKey}
// 응답: { status: 'PAID'|'CANCELED'|'AWAITING', amount, approvedAt, ... }
export async function tossConfirm(paymentKey, { orderId, amount } = {}) {
  const secret = tossSecretKey();
  if (!secret) throw new Error('toss secret key not configured');
  const body = {};
  if (orderId) body.orderId = orderId;
  if (amount != null) body.amount = amount;
  const resp = await fetchImpl(`${apiBase}/gateway/payments/${paymentKey}`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${Buffer.from(secret + ':').toString('base64')}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  const j = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(`toss confirm http ${resp.status}${j.message ? ` (${j.message})` : ''}`);
  return j;
}

// ---- 서버사이드 결제 생성 (브라우저 approval 페이지용) ----
// Toss 표준 REST: POST {apiBase}/v1/payments/requests (secret key)
// 응답: { approvalUrl, paymentKey, status: 'READY', ... }
// 클라이언트는 approvalUrl 만을 받아 브라우저로 열면 된다 — 앱에는 어떤 Toss 키도 없다.
export async function tossCreatePayment(orderId, { orderName, amount }) {
  const secret = tossSecretKey();
  if (!secret) throw new Error('toss secret key not configured');
  const resp = await fetchImpl(`${apiBase}/v1/payments/requests`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${Buffer.from(secret + ':').toString('base64')}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ orderId, orderName, amount, currency: 'KRW', methodName: 'card' }),
  });
  const j = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(`toss create http ${resp.status}${j.message ? ` (${j.message})` : ''}`);
  if (!j.approvalUrl) throw new Error('toss create: no approvalUrl');
  return j;
}

// ---- 결제 상태 조회 (폴링용, 부효과 없음) ----
// Toss 표준 REST: GET {apiBase}/gateway/payments/{paymentKey}
export async function tossQueryPayment(paymentKey) {
  const secret = tossSecretKey();
  if (!secret) throw new Error('toss secret key not configured');
  const resp = await fetchImpl(`${apiBase}/gateway/payments/${paymentKey}`, {
    method: 'GET',
    headers: { Authorization: `Basic ${Buffer.from(secret + ':').toString('base64')}` },
  });
  const j = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(`toss query http ${resp.status}${j.message ? ` (${j.message})` : ''}`);
  return j;
}

// ---- 주문 paid 마킹 (멱등) ----
// pending_payment → paid 전환 + 자체상품 재고 차감은 "여기서만" 한다.
// confirm과 webhook가 동시에 와도 (경쟁 포함) 재고는 딱 한 번만 차감된다.
export function markOrderPaid(orderId, { paymentKey = null } = {}) {
  const order = db.prepare('SELECT * FROM orders WHERE id=?').get(orderId);
  if (!order) return { changed: false, reason: 'no-order' };
  if (order.status === 'paid') return { changed: false, reason: 'already-paid' };
  if (order.status !== 'pending_payment') return { changed: false, reason: `status-${order.status}` };
  db.exec('BEGIN');
  try {
    // WHERE 조건에 status 포함 — 동시 확인 시 한쪽만 UPDATE 1행을 건드린다.
    const upd = db.prepare("UPDATE orders SET status='paid', pg_payment_key=COALESCE(?, pg_payment_key) WHERE id=? AND status='pending_payment'").run(paymentKey, orderId);
    if (upd.changes === 0) { db.exec('ROLLBACK'); return { changed: false, reason: 'already-paid' }; }
    for (const it of db.prepare('SELECT product_id, qty FROM order_items WHERE order_id=?').all(orderId)) {
      const p = db.prepare('SELECT vendor FROM products WHERE id=?').get(it.product_id);
      if (p?.vendor === 'self') {
        db.prepare('UPDATE products SET stock = MAX(0, stock - ?) WHERE id=?').run(it.qty, it.product_id);
      }
    }
    db.exec('COMMIT');
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
  return { changed: true };
}

// ---- 주문 취소 마킹 (멱등) — 재고 영향 없음 ----
export function markOrderCanceled(orderId) {
  const order = db.prepare('SELECT * FROM orders WHERE id=?').get(orderId);
  if (!order) return { changed: false, reason: 'no-order' };
  if (order.status !== 'pending_payment') return { changed: false, reason: `status-${order.status}` };
  const upd = db.prepare("UPDATE orders SET status='canceled' WHERE id=? AND status='pending_payment'").run(orderId);
  return { changed: upd.changes > 0 };
}
