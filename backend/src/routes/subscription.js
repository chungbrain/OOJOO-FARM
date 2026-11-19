import { Router } from 'express';
import db from '../db.js';
const r = Router();

export const PLANS = {
  free: { plan: 'free', name: '무료', maxFarmers: 2, detailedReport: false, priorityCs: false, price: 0 },
  premium: { plan: 'premium', name: '프리미엄', maxFarmers: 999, detailedReport: true, priorityCs: true, price: 4900 },
};

// 플랜 목록
r.get('/plans', (req, res) => res.json({ plans: Object.values(PLANS) }));

// 사용자 구독 상태
r.get('/:userId', (req, res) => {
  const u = db.prepare('SELECT plan FROM users WHERE id=?').get(req.params.userId);
  const key = (u && u.plan) || 'free';
  res.json({ userId: req.params.userId, ...(PLANS[key] || PLANS.free) });
});

// 구독 변경 (결제 시뮬레이션 — PG 연동 전까지 데모. PRD 4.10)
// 기존: 존재하지 않는 userId 로도 갱신되며 고스트 유저가 생성됐다.
r.post('/', (req, res) => {
  const { userId, plan } = req.body;
  if (!userId || !PLANS[plan]) return res.status(400).json({ error: 'userId and valid plan required' });
  const u = db.prepare('SELECT id FROM users WHERE id=?').get(userId);
  if (!u) return res.status(404).json({ error: 'user not found' });
  db.prepare('UPDATE users SET plan=? WHERE id=?').run(plan, userId);
  res.json({ userId, ...PLANS[plan], demo: true });
});

export default r;
