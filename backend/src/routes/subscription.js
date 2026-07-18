import { Router } from 'express';
import db from '../db.js';
const r = Router();

const PLANS = {
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

// 구독 변경(결제 시뮬레이션)
r.post('/', (req, res) => {
  const { userId, plan } = req.body;
  if (!userId || !PLANS[plan]) return res.status(400).json({ error: 'userId and valid plan required' });
  db.prepare('INSERT OR IGNORE INTO users(id) VALUES(?)').run(userId);
  db.prepare('UPDATE users SET plan=? WHERE id=?').run(plan, userId);
  res.json({ userId, ...PLANS[plan] });
});

export default r;
