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
// pairing_code: 이 farmer를 페어링할 때 사용한 코드 (pairings.slave_id 연결).
// scalar subquery — LEFT JOIN보다 안전: 같은 slave_id 행이 중복되도 목록이
// 안 벌어지고, unpair 후 잔존(오르반) 행도 slave_id 매칭으로 다른 farmer에 안 샌다.
r.get('/:userId', (req, res) => {
  const ids = householdMemberIds(req.params.userId);
  const rows = db.prepare(`
    SELECT s.id, s.name, s.online, s.last_seen, s.battery, s.stream_url,
           s.lat, s.lon, s.region, s.icon,
           s.user_id AS owner_id,
           u.nickname AS owner_name, u.email AS owner_email,
           (SELECT code FROM pairings WHERE slave_id = s.id
            ORDER BY created_at DESC LIMIT 1) AS pairing_code
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

// 슬레이브: 하트비트 (온라인 상태 + 배터리 + 스트림 URL 갱신) — 세션키 인증
// stream_url: slave가 직접 듣는 MJPEG 라이브 스트림 주소 (마스터가 LAN으로 직결).
// http(s)만 허용, 200자 이하. 미보고/유효하지 않으면 기존 값 유지.
function cleanStreamUrl(v) {
  return typeof v === 'string' && v.length > 0 && v.length <= 200 && /^https?:\/\//.test(v) ? v : null;
}
// farmer(농장) 위치 — 좌표는 유효 범위 검사, region은 짧은 문자열만.
// 미보고/무효이면 기존 값 유지 (마스터가 farmer 날씨를 조회할 데이터).
// Number(null)===0 함정 — null/빈 문자열/비숫자 문자열은 num()에서 거부.
function num(v) {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v);
  return null;
}
function cleanCoords(lat, lon) {
  const a = num(lat); const b = num(lon);
  if (a == null || b == null) return null;
  if (a < -90 || a > 90 || b < -180 || b > 180) return null;
  return { lat: a, lon: b };
}
function cleanRegion(v) {
  return typeof v === 'string' && v.length > 0 && v.length <= 100 ? v : null;
}
r.post('/heartbeat', slaveAuth, (req, res) => {
  const { slaveId, battery, streamUrl, lat, lon, region } = req.body;
  const sets = ["online=1", "last_seen=datetime('now')"];
  const args = [];
  if (battery != null) { sets.push('battery=?'); args.push(Number(battery)); }
  const stream = cleanStreamUrl(streamUrl);
  if (stream) { sets.push('stream_url=?'); args.push(stream); }
  const coords = cleanCoords(lat, lon);
  if (coords) { sets.push('lat=?', 'lon=?'); args.push(coords.lat, coords.lon); }
  const reg = cleanRegion(region);
  if (reg) { sets.push('region=?'); args.push(reg); }
  db.prepare(`UPDATE slaves SET ${sets.join(', ')} WHERE id=?`).run(...args, slaveId);
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

// 마스터: 슬레이브 아이콘 변경 — 마스터의 farmer 아이콘 선택을 서버에 저장해
// 다른 기기/가구(가족 공유)에서도 같은 아이콘이 보이게 한다.
// icon: 이모지 문자열(1..8자) 또는 null(자동 배정 복원).
r.put('/:slaveId/icon', (req, res) => {
  const { icon } = req.body || {};
  const slave = db.prepare('SELECT id FROM slaves WHERE id=?').get(req.params.slaveId);
  if (!slave) return res.status(404).json({ error: 'not found' });
  if (icon !== null && (typeof icon !== 'string' || icon.length === 0 || icon.length > 8)) {
    return res.status(400).json({ error: 'icon must be 1..8 chars or null' });
  }
  db.prepare('UPDATE slaves SET icon=? WHERE id=?').run(icon, req.params.slaveId);
  res.json({ ok: true, slaveId: req.params.slaveId, icon: icon ?? null });
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
