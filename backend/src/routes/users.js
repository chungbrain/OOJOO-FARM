import { Router } from 'express';
import { nanoid } from 'nanoid';
import { scryptSync, randomBytes, timingSafeEqual } from 'node:crypto';
import db from '../db.js';

const r = Router();

// 응답용 user 변환 — password_hash 는 절대 안 나가며,
// email 은 자기 자신의 등록/로그인 응답(includeEmail)에만 포함.
// interests 는 DB 에서 JSON 문자열 → 배열로 변환.
function userResponse(row, { includeEmail = false } = {}) {
  let interests = [];
  if (row.interests) {
    try { interests = JSON.parse(row.interests); } catch { /* 손상된 JSON 은 빈 배열로 */ }
  }
  const out = {
    id: row.id,
    nickname: row.nickname,
    region: row.region,
    plan: row.plan,
    created_at: row.created_at,
    interests,
  };
  if (includeEmail) out.email = row.email;
  return out;
}

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
  res.json(userResponse(user));
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
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(userId);
  res.json(userResponse(user, { includeEmail: true }));
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

  res.json(userResponse(row, { includeEmail: true }));
});

// 관심 작물 검증: 1..50자 문자열 배열, 최대 20개.
function validInterests(interests) {
  return Array.isArray(interests) && interests.length <= 20 &&
    interests.every((i) => typeof i === 'string' && i.length > 0 && i.length <= 50);
}

// 프로필 수정 (PRD 4.9: 재배 지역 / 관심 작물). 부분 업데이트 — 준 필드만 변경.
r.put('/:id', (req, res) => {
  const { nickname, region, interests } = req.body || {};
  const cur = db.prepare('SELECT * FROM users WHERE id=?').get(req.params.id);
  if (!cur) return res.status(404).json({ error: 'not found' });
  if (interests !== undefined && !validInterests(interests)) {
    return res.status(400).json({ error: 'interests must be an array of 1..50-char strings (max 20)' });
  }
  db.prepare(`UPDATE users SET
      nickname = ?, region = ?, interests = ?
    WHERE id=?`)
    .run(
      nickname !== undefined ? (nickname || null) : cur.nickname,
      region !== undefined ? (region || null) : cur.region,
      interests !== undefined ? JSON.stringify(interests) : cur.interests,
      req.params.id
    );
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(req.params.id);
  res.json(userResponse(user));
});

// 프로필 조회 — PII 최소화: 비인증 조회라 email은 반환하지 않는다.
r.get('/:id', (req, res) => {
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(req.params.id);
  if (!user) return res.status(404).json({ error: 'not found' });
  res.json(userResponse(user));
});

export default r;
