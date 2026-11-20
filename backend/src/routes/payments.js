// Toss Payments 서버 라우트 — 결제 확인(confirm) + 웹훅.
//
// 웹훅은 HMAC 서명이 "로우 바이트"로 계산되므로 express.json() 이전에서
// express.raw 로 등록한다 (app.js). req.body 는 Buffer.
import { Router } from 'express';
import db from '../db.js';
import {
  verifyTossWebhook, tossConfirm, tossCreatePayment, tossQueryPayment,
  markOrderPaid, markOrderCanceled, tossWebhookSecret,
} from '../lib/pg.js';
const r = Router();

// ---- Toss 웹훅 (app.js 가 express.raw 와 함께 마운트) ----
// Toss 포털에 등록한 콜백. 바디: 이벤트 배열 [{ type, data: { paymentKey, orderId, amount, status, ... } }]
export function tossWebhookHandler(req, res) {
  if (!tossWebhookSecret()) return res.status(400).json({ error: 'webhook secret not configured' });
  if (!verifyTossWebhook(req.headers, req.body)) {
    return res.status(401).json({ error: 'invalid signature' });
  }
  let events;
  try {
    events = JSON.parse(Buffer.isBuffer(req.body) ? req.body.toString('utf8') : String(req.body));
  } catch {
    return res.status(400).json({ error: 'bad json' });
  }
  const list = Array.isArray(events) ? events : [events];
  let paid = 0;
  let canceled = 0;
  for (const ev of list) {
    if (!ev?.data?.orderId) continue;
    if (ev.type === 'payment_paid') {
      const { changed } = markOrderPaid(ev.data.orderId, { paymentKey: ev.data.paymentKey || null });
      if (changed) paid++;
    } else if (ev.type === 'payment_canceled') {
      if (markOrderCanceled(ev.data.orderId).changed) canceled++;
    }
  }
  res.json({ ok: true, paid, canceled });
}

// ---- 결제 시작: Toss approval 페이지 URL 발급 ----
// 서버가 secret 키로 결제를 생성한다 (Toss REST POST /v1/payments/requests).
// 클라이언트는 approvalUrl 만 받아 브라우저로 연다 — 앱에는 Toss 키가 없다.
r.post('/toss/start', async (req, res) => {
  const { userId, orderId } = req.body || {};
  if (!orderId) return res.status(400).json({ error: 'orderId required' });
  const order = db.prepare('SELECT * FROM orders WHERE id=?').get(orderId);
  if (!order || (userId && order.user_id !== userId)) return res.status(404).json({ error: 'order not found' });
  if (order.status === 'paid') return res.status(400).json({ error: 'order already paid' });
  if (order.status !== 'pending_payment') return res.status(400).json({ error: `order is ${order.status}` });
  try {
    const j = await tossCreatePayment(orderId, { orderName: 'OOJOO FARM 마켓 주문', amount: order.total });
    if (j.paymentKey) db.prepare('UPDATE orders SET pg_payment_key=? WHERE id=?').run(j.paymentKey, orderId);
    res.json({ approvalUrl: j.approvalUrl });
  } catch (e) {
    res.status(502).json({ error: `toss create failed: ${e.message}` });
  }
});

// ---- 결제 상태 폴링: Toss 조회 → paid/canceled 전환 ----
// 포털에 웹훅을 등록하지 않은 환경에서도 이 폴링으로 루프가 닫힌다.
r.post('/toss/status', async (req, res) => {
  const { userId, orderId } = req.body || {};
  if (!orderId) return res.status(400).json({ error: 'orderId required' });
  const order = db.prepare('SELECT * FROM orders WHERE id=?').get(orderId);
  if (!order || (userId && order.user_id !== userId)) return res.status(404).json({ error: 'order not found' });
  if (order.status !== 'pending_payment') return res.json({ status: order.status });
  if (!order.pg_payment_key) return res.json({ status: 'pending_payment' });
  try {
    const j = await tossQueryPayment(order.pg_payment_key);
    if (j.status === 'PAID') markOrderPaid(orderId, { paymentKey: order.pg_payment_key });
    else if (j.status === 'CANCELED') markOrderCanceled(orderId);
    const now = db.prepare('SELECT status FROM orders WHERE id=?').get(orderId);
    res.json({ status: now?.status || order.status });
  } catch (e) {
    res.status(502).json({ error: `toss status failed: ${e.message}` });
  }
});

// ---- 클라이언트 확인 (Toss PG SDK 결제 완료 → paymentKey 전달) ----
// demo 모드 주문은 'paid'라서 여기서 오지 않는다 — pending_payment(=toss 모드)만 대상.
r.post('/toss/confirm', async (req, res) => {
  const { userId, orderId, paymentKey } = req.body || {};
  if (!orderId || !paymentKey) return res.status(400).json({ error: 'orderId and paymentKey required' });
  const order = db.prepare('SELECT * FROM orders WHERE id=?').get(orderId);
  if (!order || (userId && order.user_id !== userId)) return res.status(404).json({ error: 'order not found' });
  if (order.status === 'paid') return res.json({ ok: true, changed: false, reason: 'already-paid' });
  if (order.status !== 'pending_payment') return res.status(400).json({ error: `order is ${order.status}` });
  db.prepare('UPDATE orders SET pg_payment_key=? WHERE id=?').run(paymentKey, orderId);
  try {
    const j = await tossConfirm(paymentKey, { orderId, amount: order.total });
    if (j.status !== 'PAID') return res.status(400).json({ error: `payment not confirmed (status=${j.status})` });
    if (j.amount != null && Number(j.amount) !== order.total) {
      return res.status(400).json({ error: 'amount mismatch' });
    }
    const { changed } = markOrderPaid(orderId, { paymentKey });
    res.json({ ok: true, changed, status: 'paid' });
  } catch (e) {
    res.status(502).json({ error: `toss confirm failed: ${e.message}` });
  }
});

export default r;
