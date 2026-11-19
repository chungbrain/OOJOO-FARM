import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth, slaveAuthOptional } from '../middleware/auth.js';
import { notifyMasterEvent } from './commands.js';
import { validCommandNumbers, validEventType } from '../lib/validate.js';
const r = Router();

// 즉시 대응이 필요한 이벤트 — events 저장과 함께 master SSE 로 실시간 push.
// (그 외 이벤트는 알림 센터 폴링으로만 소비. F3 건강상태 변화 알림 포함)
const SSE_PUSH_TYPES = new Set(['health_change', 'water_low', 'pest_detected', 'harvest_ready']);

// 슬레이브: 이벤트 보고 (수확감지/해충탐지/캡처/이상 등)
r.post('/', slaveAuthOptional, (req, res) => {
  const { slaveId, plantId, type, payload = {} } = req.body;
  if (!slaveId || !validEventType(type)) return res.status(400).json({ error: 'slaveId, type required (type: string, max 50)' });
  const id = nanoid(12);
  db.prepare('INSERT INTO events(id, slave_id, plant_id, type, payload) VALUES(?,?,?,?,?)')
    .run(id, slaveId, plantId || null, type, JSON.stringify(payload));
  db.prepare("UPDATE slaves SET online=1, last_seen=datetime('now') WHERE id=?").run(slaveId);
  if (SSE_PUSH_TYPES.has(type)) {
    notifyMasterEvent(slaveId, {
      type, eventId: id, slaveId,
      plantId: plantId || null, payload,
    });
  }
  res.json({ eventId: id });
});

// 슬레이브: 오프라인 누적 일괄 동기화 (PRD 5.5 — 재연결 시 큐 비우기용).
// 단일 이벤트 POST / 와 같은 저장/알림 경로로, 한 요청에 큐 전체.
// batch 는 신규 슬레이브 코드만 호출하므로 인증은 strict (slaveAuth).
const MAX_BATCH = 500;
r.post('/batch', slaveAuth, (req, res) => {
  const { slaveId, events = [], waterings = [] } = req.body;
  if (!Array.isArray(events) || !Array.isArray(waterings)) return res.status(400).json({ error: 'events/waterings must be arrays' });
  if (events.length === 0 && waterings.length === 0) return res.status(400).json({ error: 'nothing to sync' });
  if (events.length > MAX_BATCH || waterings.length > MAX_BATCH) return res.status(400).json({ error: `batch limit is ${MAX_BATCH}` });

  const insEvent = db.prepare('INSERT INTO events(id, slave_id, plant_id, type, payload) VALUES(?,?,?,?,?)');
  const insWater = db.prepare('INSERT INTO waterings(id, slave_id, plant_id, amount_ml, source, weather_factor) VALUES(?,?,?,?,?,?)');
  let nEvents = 0, nWaterings = 0;

  // 부분 성공 금지: 첫 잘못된 항목이 있으면 전부 롤백.
  db.exec('BEGIN');
  try {
    for (const ev of events) {
      if (!ev || !validEventType(ev.type)) throw Object.assign(new Error('each event needs a valid type'), { status: 400 });
      insEvent.run(nanoid(12), slaveId, ev.plantId || null, ev.type, JSON.stringify(ev.payload || {}));
      nEvents++;
      if (SSE_PUSH_TYPES.has(ev.type)) {
        notifyMasterEvent(slaveId, {
          type: ev.type, eventId: null, slaveId,
          plantId: ev.plantId || null, payload: ev.payload || {},
        });
      }
    }
    for (const w of waterings) {
      if (!w) throw Object.assign(new Error('each watering must be an object'), { status: 400 });
      const bad = validCommandNumbers(w.amountMl ?? 300, w.weatherFactor ?? 1);
      if (bad) throw Object.assign(new Error(bad), { status: 400 });
      insWater.run(nanoid(12), slaveId, w.plantId || null, w.amountMl ?? 300, w.source || 'auto', w.weatherFactor ?? 1);
      nWaterings++;
    }
    db.prepare("UPDATE slaves SET online=1, last_seen=datetime('now') WHERE id=?").run(slaveId);
    db.exec('COMMIT');
  } catch (e) {
    try { db.exec('ROLLBACK'); } catch { /* noop */ }
    return res.status(e.status || 500).json({ error: e.message });
  }
  res.json({ accepted: nEvents + nWaterings, events: nEvents, waterings: nWaterings });
});

// 슬레이브별 최근 이벤트 (마스터 조회)
r.get('/:slaveId', (req, res) => {
  const rows = db.prepare('SELECT * FROM events WHERE slave_id=? ORDER BY created_at DESC LIMIT 50').all(req.params.slaveId);
  res.json({ events: rows });
});

export default r;
