import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
const r = Router();

// 마스터: 페어링 코드 생성 (6자리 무작위, 10분 유효)
r.post('/code', (req, res) => {
  const { userId } = req.body;
  if (!userId) return res.status(400).json({ error: 'userId required' });
  // 없으면 유저 생성(Phase1 임시)
  db.prepare('INSERT OR IGNORE INTO users(id) VALUES(?)').run(userId);
  const code = nanoid(6).toUpperCase();
  const expires = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  db.prepare('INSERT INTO pairings(code, user_id, status, expires_at) VALUES(?,?,?,?)').run(code, userId, 'pending', expires);
  res.json({ code, expiresAt: expires });
});

// 슬레이브: 코드 검증 → 세션키 발급, slave 레코드 생성
r.post('/verify', (req, res) => {
  const { code } = req.body;
  if (!code) return res.status(400).json({ error: 'code required' });
  const p = db.prepare('SELECT * FROM pairings WHERE code=?').get(code);
  if (!p) return res.status(404).json({ error: 'invalid code' });
  if (p.status === 'used') return res.status(410).json({ error: 'code already used' });
  if (p.expires_at && new Date(p.expires_at) < new Date()) return res.status(410).json({ error: 'code expired' });
  const slaveId = nanoid(12);
  const sessionKey = nanoid(32);
  db.prepare('INSERT INTO slaves(id, user_id, session_key, name) VALUES(?,?,?,?)').run(slaveId, p.user_id, sessionKey, 'Farmer-' + slaveId.slice(0, 4));
  db.prepare('UPDATE pairings SET status=?, slave_id=? WHERE code=?').run('used', slaveId, code);
  res.json({ slaveId, sessionKey, userId: p.user_id });
});

// 마스터: 사용자의 슬레이브 목록
r.get('/:userId', (req, res) => {
  const rows = db.prepare('SELECT id, name, online, last_seen FROM slaves WHERE user_id=?').all(req.params.userId);
  res.json({ slaves: rows });
});

// 슬레이브: 하트비트 (온라인 상태 갱신)
r.post('/heartbeat', (req, res) => {
  const { slaveId } = req.body;
  db.prepare("UPDATE slaves SET online=1, last_seen=datetime('now') WHERE id=?").run(slaveId);
  res.json({ ok: true });
});

export default r;
