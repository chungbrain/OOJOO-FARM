import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
const r = Router();

// 슬레이브에 할당된 식물 목록 (/:userId 보다 먼저 매칭되어야 함)
r.get('/slave/:slaveId', (req, res) => {
  const rows = db.prepare('SELECT * FROM plants WHERE slave_id=?').all(req.params.slaveId);
  res.json({ plants: rows });
});

// 식물 상세
r.get('/plant/:id', (req, res) => {
  const p = db.prepare('SELECT * FROM plants WHERE id=?').get(req.params.id);
  if (!p) return res.status(404).json({ error: 'not found' });
  res.json(p);
});

// 사용자의 식물 목록
r.get('/:userId', (req, res) => {
  const rows = db.prepare('SELECT * FROM plants WHERE user_id=?').all(req.params.userId);
  res.json({ plants: rows });
});

// 식물 등록 (마스터가 슬레이브에 연결)
r.post('/', (req, res) => {
  const { userId, slaveId, name, species, plantedAt, stage = 'seedling' } = req.body;
  if (!userId || !name) return res.status(400).json({ error: 'userId, name required' });
  const id = nanoid(12);
  db.prepare('INSERT INTO plants(id, user_id, slave_id, name, species, planted_at, stage) VALUES(?,?,?,?,?,?,?)')
    .run(id, userId, slaveId || null, name, species || null, plantedAt || null, stage);
  res.json({ plantId: id });
});

export default r;
