import { Router } from 'express';
import { nanoid } from 'nanoid';
import { scryptSync, randomBytes, timingSafeEqual } from 'node:crypto';
import db from '../db.js';

const r = Router();

// 기존 호환 — 익명 로컬 계정 (닉네임/지역만). id 주면 upsert, 없으면 새 id 발급.
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

// 회원가입 — email + password (+ optional nickname, region)
// 성공 시 user json 반환. 이메일 중복 시 409.
r.post('/register', (req, res) => {
  const { email, password, nickname, region } = req.body || {};
  if (!email || typeof email !== 'string' || !email.includes('@')) {
    return res.status(400).json({ error: 'invalid email' });
  }
  if (!password || typeof password !== 'string' || password.length < 4) {
    return res.status(400).json({ error: 'password must be at least 4 chars' });
  }
  const normalizedEmail = email.trim().toLowerCase();
  const exists = db.prepare('SELECT id FROM users WHERE email = ?').get(normalizedEmail);
  if (exists) return res.status(409).json({ error: 'email already registered' });

  const userId = nanoid(12);
  const salt = randomBytes(16).toString('hex');
  const hash = scryptSync(password, salt, 64).toString('hex');
  const passwordHash = `${salt}$${hash}`;
  db.prepare(
    `INSERT INTO users(id, email, password_hash, nickname, region) VALUES(?,?,?,?,?)`
  ).run(userId, normalizedEmail, passwordHash, nickname || null, region || null);
  const user = db.prepare('SELECT id, email, nickname, region, plan, created_at FROM users WHERE id=?').get(userId);
  res.json(user);
});

// 로그인 — email + password 검증 후 user 반환.
r.post('/login', (req, res) => {
  const { email, password } = req.body || {};
  if (!email || !password) return res.status(400).json({ error: 'email and password required' });
  const normalizedEmail = email.trim().toLowerCase();
  const row = db.prepare('SELECT * FROM users WHERE email = ?').get(normalizedEmail);
  if (!row || !row.password_hash) return res.status(401).json({ error: 'invalid credentials' });

  const [salt, hash] = row.password_hash.split('$');
  const computed = scryptSync(password, salt, 64).toString('hex');
  const match = timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(computed, 'hex'));
  if (!match) return res.status(401).json({ error: 'invalid credentials' });

  const user = db.prepare('SELECT id, email, nickname, region, plan, created_at FROM users WHERE id=?').get(row.id);
  res.json(user);
});

// 프로필 조회
r.get('/:id', (req, res) => {
  const user = db.prepare('SELECT id, email, nickname, region, plan, created_at FROM users WHERE id=?').get(req.params.id);
  if (!user) return res.status(404).json({ error: 'not found' });
  res.json(user);
});

export default r;