import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';
const r = Router();

// 마스터: 슬레이브에 원격 지시 등록 (관수/퇴치/모드변경 등)
r.post('/', (req, res) => {
  const { slaveId, plantId, action = 'water', amountMl = 300, weatherFactor = 1 } = req.body;
  if (!slaveId) return res.status(400).json({ error: 'slaveId required' });
  const id = nanoid(12);
  db.prepare('INSERT INTO commands(id, slave_id, plant_id, action, amount_ml, weather_factor) VALUES(?,?,?,?,?,?)')
    .run(id, slaveId, plantId || null, action, amountMl, weatherFactor);
  res.json({ commandId: id, status: 'queued' });
});

// 슬레이브: 대기 중 명령 조회 (폴링) — 세션키 인증
r.get('/pending/:slaveId', slaveAuth, (req, res) => {
  const rows = db.prepare("SELECT * FROM commands WHERE slave_id=? AND status='queued' ORDER BY created_at ASC").all(req.params.slaveId);
  res.json({ commands: rows });
});

// 슬레이브: 명령 실행 완료 보고
r.post('/:id/done', (req, res) => {
  const { id } = req.params;
  const cmd = db.prepare('SELECT * FROM commands WHERE id=?').get(id);
  if (!cmd) return res.status(404).json({ error: 'not found' });
  db.prepare("UPDATE commands SET status='done', executed_at=datetime('now') WHERE id=?").run(id);
  res.json({ ok: true });
});

// 마스터: 명령 이력 조회
r.get('/history/:slaveId', (req, res) => {
  const rows = db.prepare('SELECT * FROM commands WHERE slave_id=? ORDER BY created_at DESC LIMIT 50').all(req.params.slaveId);
  res.json({ commands: rows });
});

export default r;
