import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuthOptional } from '../middleware/auth.js';
const r = Router();

// 슬레이브: 이벤트 보고 (수확감지/해충탐지/캡처/이상 등)
r.post('/', slaveAuthOptional, (req, res) => {
  const { slaveId, plantId, type, payload = {} } = req.body;
  if (!slaveId || !type) return res.status(400).json({ error: 'slaveId, type required' });
  const id = nanoid(12);
  db.prepare('INSERT INTO events(id, slave_id, plant_id, type, payload) VALUES(?,?,?,?,?)')
    .run(id, slaveId, plantId || null, type, JSON.stringify(payload));
  db.prepare("UPDATE slaves SET online=1, last_seen=datetime('now') WHERE id=?").run(slaveId);
  res.json({ eventId: id });
});

// 슬레이브별 최근 이벤트 (마스터 조회)
r.get('/:slaveId', (req, res) => {
  const rows = db.prepare('SELECT * FROM events WHERE slave_id=? ORDER BY created_at DESC LIMIT 50').all(req.params.slaveId);
  res.json({ events: rows });
});

export default r;
