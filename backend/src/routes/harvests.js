import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';

const r = Router();

// 수확 기록 (PRD 4.7: 수량/날짜/사진 저장, 커뮤니티 자랑 연계의 데이터 원천)
db.exec(`
CREATE TABLE IF NOT EXISTS harvests (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  slave_id TEXT,
  plant_id TEXT NOT NULL,
  quantity REAL NOT NULL,
  unit TEXT,
  note TEXT,
  photo TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_harvests_plant ON harvests(plant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_harvests_user ON harvests(user_id, created_at);
`);

// 마스터: 수확 기록 저장. date 는 ISO 타임스탬프 (사후 기록 허용), 없으면 현재 시각.
r.post('/', (req, res) => {
  const { userId, plantId, slaveId, quantity, unit, note, photo, date } = req.body;
  if (!userId || !plantId) return res.status(400).json({ error: 'userId, plantId required' });
  if (!Number.isFinite(quantity) || quantity <= 0) return res.status(400).json({ error: 'quantity must be a positive number' });
  let harvestedAt = null;
  if (date != null) {
    const t = Date.parse(date);
    if (Number.isNaN(t)) return res.status(400).json({ error: 'date must be an ISO timestamp' });
    harvestedAt = new Date(t).toISOString();
  }
  const id = nanoid(12);
  db.prepare(`INSERT INTO harvests(id, user_id, slave_id, plant_id, quantity, unit, note, photo, created_at)
    VALUES(?,?,?,?,?,?,?,?, COALESCE(?, datetime('now')))`)
    .run(id, userId, slaveId || null, plantId, quantity, unit || null, note || null, photo || null, harvestedAt);
  res.json({ harvestId: id });
});

// 식물별 수확 이력
r.get('/plant/:plantId', (req, res) => {
  const rows = db.prepare('SELECT * FROM harvests WHERE plant_id=? ORDER BY created_at DESC LIMIT 100').all(req.params.plantId);
  res.json({ harvests: rows });
});

// 사용자 수확 요약 (프로필/커뮤니티 자랑용 — 식물명 조인)
r.get('/user/:userId', (req, res) => {
  const rows = db.prepare(`
    SELECT h.*, p.name AS plant_name
    FROM harvests h
    LEFT JOIN plants p ON h.plant_id = p.id
    WHERE h.user_id=?
    ORDER BY h.created_at DESC
    LIMIT 100
  `).all(req.params.userId);
  res.json({ harvests: rows });
});

export default r;
