// Toss Payments 서버 라우트 — 결제 확인(confirm) + 웹훅.
//
// 웹훅은 HMAC 서명이 "로우 바이트"로 계산되므로 express.json() 이전에서
// express.raw 로 등록한다 (app.js). req.body 는 Buffer.
import { Router } from 'express';
import db from '../db.js';
import { verifyTossWebhook, tossConfirm, markOrderPaid, tossWebhookSecret } from '../lib/pg.js';
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
  for (const ev of list) {
    if (ev?.type === 'payment_paid' && ev.data?.orderId) {
      const { changed } = markOrderPaid(ev.data.orderId, { paymentKey: ev.data.paymentKey || null });
      if (changed) paid++;
    }
  }
  res.json({ ok: true, paid });
}

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
