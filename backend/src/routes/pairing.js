import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';
import { householdMemberIds, inSql } from '../lib/household.js';
import { PLANS } from './subscription.js';
const r = Router();

// PRD 4.10/8 — 플랜별 Farmer 등록 상한 (free 2대 / premium 999)
export function farmerLimit(userId) {
  const u = db.prepare('SELECT plan FROM users WHERE id=?').get(userId);
  return (PLANS[(u && u.plan) || 'free'] || PLANS.free).maxFarmers;
}
function slaveCount(userId) {
  return db.prepare('SELECT COUNT(*) AS n FROM slaves WHERE user_id=?').get(userId).n;
}

// 마스터: 페어링 코드 생성 (6자리 무작위, 10분 유효)
r.post('/code', (req, res) => {
  const { userId } = req.body;
  if (!userId) return res.status(400).json({ error: 'userId required' });
  // 없으면 유저 생성(Phase1 임시)
  db.prepare('INSERT OR IGNORE INTO users(id) VALUES(?)').run(userId);
  if (slaveCount(userId) >= farmerLimit(userId)) {
    return res.status(403).json({ error: 'farmer limit reached for this plan', limit: farmerLimit(userId) });
  }
  // 혼동 방지: 대문자/숫자만, 만료된 pending 코드는 정리
  db.prepare("DELETE FROM pairings WHERE status='pending' AND expires_at < datetime('now')").run();
  const code = nanoid(6).toUpperCase();
  const expires = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  db.prepare('INSERT INTO pairings(code, user_id, status, expires_at) VALUES(?,?,?,?)').run(code, userId, 'pending', expires);
  res.json({ code, expiresAt: expires });
});

// 슬레이브: 코드 검증 → 세션키 발급, slave 레코드 + 기본 정책 생성
r.post('/verify', (req, res) => {
  const { code, name } = req.body;
  if (!code) return res.status(400).json({ error: 'code required' });
  const p = db.prepare('SELECT * FROM pairings WHERE code=?').get(code.toUpperCase());
  if (!p) return res.status(404).json({ error: 'invalid code' });
  if (p.status === 'used') return res.status(410).json({ error: 'code already used' });
  if (p.expires_at && new Date(p.expires_at) < new Date()) return res.status(410).json({ error: 'code expired' });
  // 코드 생성 이후 상한 도달(동시 페어링/플랜 변경) 대비 2차 점검
  if (slaveCount(p.user_id) >= farmerLimit(p.user_id)) {
    return res.status(409).json({ error: 'farmer limit reached for this plan', limit: farmerLimit(p.user_id) });
  }
  const slaveId = nanoid(12);
  const sessionKey = nanoid(32);
  const slaveName = name || 'Farmer-' + slaveId.slice(0, 4);
  db.prepare('INSERT INTO slaves(id, user_id, session_key, name, online, last_seen) VALUES(?,?,?,?,1,datetime(\'now\'))')
    .run(slaveId, p.user_id, sessionKey, slaveName);
  db.prepare('INSERT OR IGNORE INTO policies(slave_id) VALUES(?)').run(slaveId);
  db.prepare('UPDATE pairings SET status=?, slave_id=? WHERE code=?').run('used', slaveId, p.code);
  res.json({ slaveId, sessionKey, userId: p.user_id, name: slaveName });
});

// 마스터: 사용자의 슬레이브 목록 — 가구(가족/친구) 공유 Farmer 포함
r.get('/:userId', (req, res) => {
  const ids = householdMemberIds(req.params.userId);
  const rows = db.prepare(`
    SELECT s.id, s.name, s.online, s.last_seen, s.battery, s.user_id AS owner_id,
           u.nickname AS owner_name, u.email AS owner_email
    FROM slaves s
    LEFT JOIN users u ON u.id = s.user_id
    WHERE s.user_id IN (${inSql(ids)})
  `).all(...ids);
  res.json({
    slaves: rows.map((s) => ({
      ...s,
      shared: s.owner_id && s.owner_id !== req.params.userId ? 1 : 0,
    })),
  });
});

// 슬레이브: 하트비트 (온라인 상태 + 배터리 갱신) — 세션키 인증
r.post('/heartbeat', slaveAuth, (req, res) => {
  const { slaveId, battery } = req.body;
  if (battery != null) {
    db.prepare("UPDATE slaves SET online=1, last_seen=datetime('now'), battery=? WHERE id=?").run(Number(battery), slaveId);
  } else {
    db.prepare("UPDATE slaves SET online=1, last_seen=datetime('now') WHERE id=?").run(slaveId);
  }
  res.json({ ok: true });
});

// 마스터: 슬레이브 이름 변경 (PRD 4.1 연결 후 기기 식별/별명 부여)
r.put('/:slaveId', (req, res) => {
  const { name } = req.body || {};
  const slave = db.prepare('SELECT id FROM slaves WHERE id=?').get(req.params.slaveId);
  if (!slave) return res.status(404).json({ error: 'not found' });
  if (typeof name !== 'string' || name.trim().length === 0 || name.trim().length > 50) {
    return res.status(400).json({ error: 'name must be 1..50 chars' });
  }
  db.prepare('UPDATE slaves SET name=? WHERE id=?').run(name.trim(), req.params.slaveId);
  res.json({ ok: true, slaveId: req.params.slaveId, name: name.trim() });
});

// 마스터: 슬레이브 연결 해제(이관/교체) — 관련 행 전부 정리 (오르팬 방지).
// slaves 삭제만 하면 plants/events/waterings/commands/영상·분석·진단이
// 고아 행으로 남는다 (해당 테이블에 FK/cascade 없음).
r.delete('/:slaveId', (req, res) => {
  const { slaveId } = req.params;
  const slave = db.prepare('SELECT id FROM slaves WHERE id=?').get(slaveId);
  if (!slave) return res.status(404).json({ error: 'not found' });

  db.exec('BEGIN');
  try {
    // 이 슬레이브의 식물 — 식물 단위 미디어/분석도 함께
    const plantIds = db.prepare('SELECT id FROM plants WHERE slave_id=?').all(slaveId).map((p) => p.id);
    if (plantIds.length) {
      const ph = plantIds.map(() => '?').join(',');
      db.prepare(`DELETE FROM plant_photos WHERE plant_id IN (${ph})`).run(...plantIds);
      db.prepare(`DELETE FROM videos WHERE plant_id IN (${ph})`).run(...plantIds);
      db.prepare(`DELETE FROM plant_analysis WHERE plant_id IN (${ph})`).run(...plantIds);
    }
    db.prepare(`DELETE FROM diagnosis_frames WHERE session_id IN (SELECT id FROM diagnosis_sessions WHERE slave_id=?)`).run(slaveId);
    db.prepare('DELETE FROM diagnosis_sessions WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM plant_analysis WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM plant_photos WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM videos WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM harvests WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM plants WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM events WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM waterings WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM commands WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM policies WHERE slave_id=?').run(slaveId);
    db.prepare('DELETE FROM slaves WHERE id=?').run(slaveId);
    db.exec('COMMIT');
  } catch (e) {
    try { db.exec('ROLLBACK'); } catch { /* noop */ }
    console.error('unpair cleanup failed:', e);
    return res.status(500).json({ error: 'unpair failed' });
  }
  res.json({ ok: true });
});

export default r;
