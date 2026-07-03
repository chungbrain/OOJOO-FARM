import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
const r = Router();

// 마스터: 원격 관수 지시 (슬레이브에 전달될 명령 기록)
r.post('/command', (req, res) => {
  const { slaveId, plantId, amountMl = 300, weatherFactor = 1 } = req.body;
  if (!slaveId) return res.status(400).json({ error: 'slaveId required' });
  const id = nanoid(12);
  db.prepare('INSERT INTO waterings(id, slave_id, plant_id, amount_ml, source, weather_factor) VALUES(?,?,?,?,?,?)')
    .run(id, slaveId, plantId || null, amountMl, 'manual', weatherFactor);
  res.json({ commandId: id, slaveId, amountMl, weatherFactor, status: 'queued' });
});

// 슬레이브: 관수 실행 보고 (자율/수동)
r.post('/log', (req, res) => {
  const { slaveId, plantId, amountMl = 300, source = 'auto', weatherFactor = 1 } = req.body;
  if (!slaveId) return res.status(400).json({ error: 'slaveId required' });
  const id = nanoid(12);
  db.prepare('INSERT INTO waterings(id, slave_id, plant_id, amount_ml, source, weather_factor) VALUES(?,?,?,?,?,?)')
    .run(id, slaveId, plantId || null, amountMl, source, weatherFactor);
  res.json({ logId: id });
});

// 식물별 관수 이력
r.get('/:plantId', (req, res) => {
  const rows = db.prepare('SELECT * FROM waterings WHERE plant_id=? ORDER BY created_at DESC LIMIT 50').all(req.params.plantId);
  res.json({ waterings: rows });
});

export default r;
