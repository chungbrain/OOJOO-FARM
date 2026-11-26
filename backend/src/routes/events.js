import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth, slaveAuthOptional } from '../middleware/auth.js';
import { notifyMasterEvent } from './commands.js';
import { pushMasterEvent } from '../lib/push.js';
import { validCommandNumbers, validEventType } from '../lib/validate.js';
const r = Router();

// 즉시 대응이 필요한 이벤트 — events 저장과 함께 master SSE 로 실시간 push.
// (그 외 이벤트는 알림 센터 폴링으로만 소비. F3 건강상태 변화 알림 포함)
const SSE_PUSH_TYPES = new Set(['health_change', 'water_low', 'pest_detected', 'harvest_ready']);
// FCM 푸시 대상 — SSE 대상 + 하드웨어 실패/배터리 (SSE 비연결 시에도 도달해야 할 알림)
const FCM_PUSH_TYPES = new Set([...SSE_PUSH_TYPES, 'water_failed', 'battery_low']);

// master 실시간 알림 분기 — SSE(연결 시) + FCM(설정 시) 병행.
// FCM 은 fire-and-forget (pushMasterEvent 는 절대 throw 하지 않음).
function dispatchMasterAlert(slaveId, msg) {
  if (SSE_PUSH_TYPES.has(msg.type)) notifyMasterEvent(slaveId, msg);
  if (FCM_PUSH_TYPES.has(msg.type)) void pushMasterEvent(slaveId, msg);
}

// 슬레이브: 이벤트 보고 (수확감지/해충탐지/캡처/이상 등)
r.post('/', slaveAuthOptional, (req, res) => {
  const { slaveId, plantId, type, payload = {} } = req.body;
  if (!slaveId || !validEventType(type)) return res.status(400).json({ error: 'slaveId, type required (type: string, max 50)' });
  const id = nanoid(12);
  db.prepare('INSERT INTO events(id, slave_id, plant_id, type, payload) VALUES(?,?,?,?,?)')
    .run(id, slaveId, plantId || null, type, JSON.stringify(payload));
  db.prepare("UPDATE slaves SET online=1, last_seen=datetime('now') WHERE id=?").run(slaveId);
  dispatchMasterAlert(slaveId, {
    type, eventId: id, slaveId,
    plantId: plantId || null, payload,
  });
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
      dispatchMasterAlert(slaveId, {
        type: ev.type, eventId: null, slaveId,
        plantId: ev.plantId || null, payload: ev.payload || {},
      });
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
//  - 기본: 최신 50건 (기존 계약 그대로)
//  - ?days=N (1..90): 최근 N일 이벤트 전부(최대 MAX_WINDOW_EVENTS) — 마스터 이벤트 통계/시계열용.
//    창 안 이벤트가 50건 미만이면 최신 50건으로 채워 '최근 기록' 목록이 비지 않게 한다.
//    응답에 days(적용된 창)와 truncated(창 안 이벤트가 상한에 걸림)를 함께 돌려준다.
const RECENT_LIMIT = 50;
const MAX_WINDOW_EVENTS = 1000;
r.get('/:slaveId', (req, res) => {
  const { slaveId } = req.params;
  const days = Number(req.query.days);
  const latest = () => db.prepare('SELECT * FROM events WHERE slave_id=? ORDER BY created_at DESC LIMIT ?')
    .all(slaveId, RECENT_LIMIT);
  if (!Number.isInteger(days) || days < 1 || days > 90) {
    return res.json({ events: latest() });
  }
  const windowRows = db.prepare(`
    SELECT * FROM events
    WHERE slave_id=? AND created_at >= datetime('now', ?)
    ORDER BY created_at DESC LIMIT ?
  `).all(slaveId, `-${days} days`, MAX_WINDOW_EVENTS);
  let rows = windowRows;
  if (windowRows.length < RECENT_LIMIT) {
    const seen = new Set(windowRows.map((e) => e.id));
    rows = windowRows.concat(latest().filter((e) => !seen.has(e.id)));
  }
  res.json({ events: rows, days, truncated: windowRows.length >= MAX_WINDOW_EVENTS });
});

export default r;
