import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { householdMemberIds, inSql } from '../lib/household.js';
import { plantDetectorIds } from '../lib/detectors.js';
import { queueDetectorSync } from './detectors.js';
const r = Router();

// ---------- RoI (관심영역) ----------
// 정규화 좌표 {x,y,w,h} 0..1 — 프레임 왼쪽 위 기준. 슬레이브 RoiStore.isValid 와
// 같은 최소 크기(2%) 규칙. roi:null → 삭제, 생략 → 유지.
function cleanRoi(roi) {
  if (roi === null) return null;
  if (roi === undefined) return undefined;
  if (typeof roi !== 'object' || Array.isArray(roi)) return { error: 'roi must be an object or null' };
  const n = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : NaN);
  const { x, y, w, h } = { x: n(roi.x), y: n(roi.y), w: n(roi.w), h: n(roi.h) };
  if ([x, y, w, h].some((v) => Number.isNaN(v))) return { error: 'roi x/y/w/h must be numbers' };
  if (x < 0 || y < 0 || x + w > 1 || y + h > 1 || w < 0.02 || h < 0.02) {
    return { error: 'roi must be a normalized box inside the frame, min 2% per side' };
  }
  return { x, y, w, h };
}

function withRoi(row) {
  let roi = null;
  if (row.roi_json) {
    try { roi = JSON.parse(row.roi_json); } catch { roi = null; }
  }
  // detectors: 켜진 온디바이스 검출 모듈 id — farmer 가 이 목록으로 모델을 받고 판독한다
  const { detectors_json: _dj, ...rest } = row;
  return { ...rest, roi, detectors: plantDetectorIds(row) };
}

// 슬레이브에 할당된 식물 목록 (/:userId 보다 먼저 매칭되어야 함)
r.get('/slave/:slaveId', (req, res) => {
  const rows = db.prepare('SELECT * FROM plants WHERE slave_id=?').all(req.params.slaveId);
  res.json({ plants: rows.map(withRoi) });
});

// 식물 상세
r.get('/plant/:id', (req, res) => {
  const p = db.prepare('SELECT * FROM plants WHERE id=?').get(req.params.id);
  if (!p) return res.status(404).json({ error: 'not found' });
  res.json(withRoi(p));
});

// 사용자의 식물 목록 — 가구(가족/친구) 공유 식물 포함
r.get('/:userId', (req, res) => {
  const ids = householdMemberIds(req.params.userId);
  const rows = db.prepare(`
    SELECT p.*, u.nickname AS owner_name, u.email AS owner_email
    FROM plants p
    LEFT JOIN users u ON u.id = p.user_id
    WHERE p.user_id IN (${inSql(ids)})
  `).all(...ids);
  res.json({
    plants: rows.map((p) => ({
      ...withRoi(p),
      shared: p.user_id && p.user_id !== req.params.userId ? 1 : 0,
    })),
  });
});

// 식물 등록 (마스터가 슬레이브에 연결)
r.post('/', (req, res) => {
  const { userId, slaveId, name, species, plantedAt, stage = 'seedling' } = req.body;
  if (!userId || !name) return res.status(400).json({ error: 'userId, name required' });
  const id = nanoid(12);
  db.prepare('INSERT INTO plants(id, user_id, slave_id, name, species, planted_at, stage) VALUES(?,?,?,?,?,?,?)')
    .run(id, userId, slaveId || null, name, species || null, plantedAt || null, stage);
  // farmer 가 새 식물의 검출 모듈 모델을 바로 준비하도록
  if (slaveId) queueDetectorSync({ id, slave_id: slaveId });
  res.json({ plantId: id });
});

// 식물 수정 (Farmer 배정/해제, 이름/종류/단계/식재일 변경, RoI 설정/삭제)
r.put('/plant/:id', (req, res) => {
  const { slaveId, name, species, plantedAt, stage, roi } = req.body;
  const cur = db.prepare('SELECT * FROM plants WHERE id=?').get(req.params.id);
  if (!cur) return res.status(404).json({ error: 'not found' });
  const cleaned = cleanRoi(roi);
  if (cleaned && typeof cleaned === 'object' && cleaned.error) return res.status(400).json({ error: cleaned.error });
  const roiJson = roi === undefined ? cur.roi_json : (cleaned === null ? null : JSON.stringify(cleaned));
  db.prepare(`UPDATE plants SET slave_id=?, name=?, species=?, planted_at=?, stage=?, roi_json=? WHERE id=?`)
    .run(
      slaveId !== undefined ? (slaveId || null) : cur.slave_id,
      name ?? cur.name,
      species ?? cur.species,
      plantedAt ?? cur.planted_at,
      stage ?? cur.stage,
      roiJson,
      req.params.id
    );
  // RoI·farmer 배정·종이 바뀌면 farmer 가 5분 주기를 기다리지 않고 바로 재동기화
  // (RoI 없는 식물은 판독 대기, 종이 바뀌면 관수 모듈의 종별 모델이 바뀐다)
  const next = db.prepare('SELECT * FROM plants WHERE id=?').get(req.params.id);
  if (next.roi_json !== cur.roi_json || next.slave_id !== cur.slave_id || next.species !== cur.species) {
    queueDetectorSync(next);
    if (cur.slave_id && cur.slave_id !== next.slave_id) queueDetectorSync(cur);
  }
  res.json({ ok: true, plantId: req.params.id });
});

// 식물 삭제
r.delete('/plant/:id', (req, res) => {
  const cur = db.prepare('SELECT * FROM plants WHERE id=?').get(req.params.id);
  if (!cur) return res.status(404).json({ error: 'not found' });
  db.prepare('DELETE FROM plants WHERE id=?').run(req.params.id);
  // 관련 데이터도 정리 (이벤트, 관수 기록)
  try { db.prepare('DELETE FROM events WHERE plant_id=?').run(req.params.id); } catch (_) {}
  try { db.prepare('DELETE FROM waterings WHERE plant_id=?').run(req.params.id); } catch (_) {}
  try { db.prepare('DELETE FROM detector_results WHERE plant_id=?').run(req.params.id); } catch (_) {}
  try { db.prepare('DELETE FROM detector_states WHERE plant_id=?').run(req.params.id); } catch (_) {}
  res.json({ ok: true });
});

export default r;
