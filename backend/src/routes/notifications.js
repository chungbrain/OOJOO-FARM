import { Router } from 'express';
import db from '../db.js';
import { householdMemberIds, inSql } from '../lib/household.js';
const r = Router();

// 읽음 상태 — 사용자의 전체 알림 센터(가구 집계) 기준 last_read_at.
// PRD 4.6 알림 센터: 읽지 않은 알림 배지 + "모두 읽음" 버튼.
db.exec(`
CREATE TABLE IF NOT EXISTS notification_state (
  user_id TEXT PRIMARY KEY,
  last_read_at TEXT
)`);

function unseenCount(ids, lastReadAt) {
  const sql = `
    SELECT COUNT(*) AS n
    FROM events e
    JOIN slaves s ON e.slave_id = s.id
    WHERE s.user_id IN (${inSql(ids)})
      ${lastReadAt ? 'AND e.created_at > ?' : ''}
  `;
  const args = lastReadAt ? [...ids, lastReadAt] : [...ids];
  return db.prepare(sql).get(...args).n;
}

// 마스터: 알림 센터 — 사용자의 모든 Farmer 이벤트를 집계 (수확/해충/관수/이상 등)
// 슬레이브명·식물명을 조인해 사람이 읽기 쉬운 형태로 반환한다. (PRD 4.6 알림 센터)
// unreadCount/lastReadAt: 읽지 않은 알림 수 (마스터 배지 표시용)
r.get('/:userId', (req, res) => {
  const ids = householdMemberIds(req.params.userId);
  const rows = db.prepare(`
    SELECT e.id, e.type, e.payload, e.created_at,
           e.slave_id, s.name AS slave_name,
           e.plant_id, p.name AS plant_name
    FROM events e
    JOIN slaves s ON e.slave_id = s.id
    LEFT JOIN plants p ON e.plant_id = p.id
    WHERE s.user_id IN (${inSql(ids)})
    ORDER BY e.created_at DESC
    LIMIT 100
  `).all(...ids);
  const state = db.prepare('SELECT last_read_at FROM notification_state WHERE user_id=?').get(req.params.userId);
  const lastReadAt = state?.last_read_at || null;
  res.json({ notifications: rows, unreadCount: unseenCount(ids, lastReadAt), lastReadAt });
});

// 마스터: 알림 모두 읽음 처리
r.post('/:userId/read', (req, res) => {
  db.prepare(`INSERT INTO notification_state(user_id, last_read_at) VALUES(?, datetime('now'))
    ON CONFLICT(user_id) DO UPDATE SET last_read_at=datetime('now')`)
    .run(req.params.userId);
  res.json({ ok: true, unreadCount: 0 });
});

export default r;
