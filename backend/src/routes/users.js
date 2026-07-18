import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
const r = Router();

// 계정 생성/갱신 (Phase1: 익명 로컬 계정 — 닉네임/지역만).
// id 를 주면 upsert, 없으면 새 id 발급.
r.post('/', (req, res) => {
  const { id, nickname, region } = req.body;
  const userId = id || nanoid(12);
  db.prepare(`INSERT INTO users(id, nickname, region) VALUES(?,?,?)
    ON CONFLICT(id) DO UPDATE SET
      nickname = COALESCE(excluded.nickname, users.nickname),
      region   = COALESCE(excluded.region, users.region)`)
    .run(userId, nickname || null, region || null);
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(userId);
  res.json(user);
});

// 프로필 조회
r.get('/:id', (req, res) => {
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(req.params.id);
  if (!user) return res.status(404).json({ error: 'not found' });
  res.json(user);
});

export default r;
